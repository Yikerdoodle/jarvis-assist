"""Get Claude going on this PC, so the Claude app on the phone can carry on from here.

Two ways, chosen by "mode" in launcher.json:

  desktop  open the Claude desktop app (the window you click on) if it is not already open. The app has to be
           running for the phone to reach it: the phone talks to it through Remote Control or Dispatch, which
           are switched on once inside the app.
  cli      start `claude remote-control` hidden, with the official claude command: a small server that keeps
           running, from which the phone starts new sessions.
  auto     (the default) the desktop app if it is installed, otherwise the claude command.

Claude and its tools always run on this PC; the phone only shows them.

What a request may ask for is deliberately tiny: "start it". The folder and the options come from
launcher.json on the PC, never from the request, so a request can never choose what runs or where.
"""

import csv
import glob
import io
import json
import logging
import os
import pathlib
import re
import subprocess
import threading
import time

from .auth import app_dir
from .claude import clean_env, find_claude

LOG = logging.getLogger("start_claude_pc")
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
NEW_GROUP = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)

STARTUP_CHECK_SECONDS = 4.0   # a start that is still alive after this long is treated as working
LOG_LIMIT = 1_000_000         # the child's output file is started afresh once it passes this size
MODES = ("auto", "desktop", "cli")
SPAWN_MODES = ("same-dir", "worktree")
# "bypassPermissions" is left out on purpose: sessions started by a tap on a phone should not run unchecked.
# Anyone who really wants that can start `claude remote-control` by hand.
PERMISSION_MODES = ("default", "acceptEdits", "auto", "dontAsk", "plan")
CONFIG_KEYS = ("mode", "desktop_app_id", "dir", "name", "spawn", "capacity", "permission_mode")


class LaunchError(Exception):
    """Something the owner can act on. The message is shown on the phone, so keep it plain."""

    def __init__(self, message, status=500):
        super().__init__(message)
        self.status = status


# ---------- Settings (launcher.json) ----------

def load_config(path=None):
    """Read and check launcher.json. Every key is optional; no file at all means "all defaults"."""
    path = pathlib.Path(path) if path else app_dir() / "launcher.json"
    raw = {}
    if path.exists():
        try:
            raw = json.loads(path.read_text(encoding="utf-8-sig"))  # -sig: Notepad may add a byte-order mark
        except (OSError, ValueError) as exc:
            raise LaunchError(f"launcher.json on the PC cannot be read: {exc}")
        if not isinstance(raw, dict):
            raise LaunchError('launcher.json must look like {"dir": "C:\\\\Users\\\\you\\\\code"}')
    unknown = sorted(set(raw) - set(CONFIG_KEYS))
    if unknown:
        raise LaunchError("launcher.json has settings this program does not know: " + ", ".join(unknown)
                          + ". Allowed: " + ", ".join(CONFIG_KEYS))

    run_mode = raw.get("mode", "auto")
    if run_mode not in MODES:
        raise LaunchError(f'launcher.json "mode" must be one of: {", ".join(MODES)}')
    app_id = raw.get("desktop_app_id")
    if app_id is not None and (not isinstance(app_id, str) or not app_id.strip() or len(app_id) > 300
                               or any(ord(ch) < 32 for ch in app_id)):
        raise LaunchError('launcher.json "desktop_app_id" must be the Claude app\'s id from Get-StartApps')

    folder = pathlib.Path(os.path.expanduser(str(raw.get("dir") or pathlib.Path.home())))
    if not folder.is_dir():
        raise LaunchError(f"The folder in launcher.json does not exist on the PC: {folder}")
    spawn = raw.get("spawn")
    if spawn is not None and spawn not in SPAWN_MODES:
        raise LaunchError(f'launcher.json "spawn" must be one of: {", ".join(SPAWN_MODES)}')
    capacity = raw.get("capacity")
    if capacity is not None and (isinstance(capacity, bool) or not isinstance(capacity, int)
                                 or not 1 <= capacity <= 32):
        raise LaunchError('launcher.json "capacity" must be a whole number from 1 to 32')
    mode = raw.get("permission_mode")
    if mode is not None and mode not in PERMISSION_MODES:
        raise LaunchError(f'launcher.json "permission_mode" must be one of: {", ".join(PERMISSION_MODES)}')
    name = raw.get("name")
    if name is not None and (not isinstance(name, str) or not name.strip() or len(name) > 60):
        raise LaunchError('launcher.json "name" must be 1 to 60 characters')
    return {"mode": run_mode, "desktop_app_id": app_id.strip() if app_id else None, "dir": folder,
            "name": name.strip() if name else None, "spawn": spawn, "capacity": capacity, "permission_mode": mode}


def build_argv(prefix, config):
    argv = list(prefix) + ["remote-control"]
    if config["name"]:
        argv += ["--name", config["name"]]
    if config["spawn"]:
        argv += ["--spawn", config["spawn"]]
    if config["capacity"]:
        argv += ["--capacity", str(config["capacity"])]
    if config["permission_mode"]:
        argv += ["--permission-mode", config["permission_mode"]]
    return argv


# ---------- Is it already running? ----------

_TOKEN = re.compile(r'"([^"]*)"|(\S+)')


def is_remote_control(command_line):
    """True for `claude remote-control ...`, whether it is the native claude.exe or `node .../claude-code/cli.js`.

    The subcommand must be a whole argument, so a question that merely mentions remote-control, or the
    PowerShell command that is looking for it, never counts.
    """
    tokens = [quoted or plain for quoted, plain in _TOKEN.findall(command_line or "")]
    if len(tokens) < 2:
        return False
    exe = re.split(r"[\\/]", tokens[0])[-1].lower()
    exe = exe[:-4] if exe.endswith(".exe") else exe
    if exe == "claude":
        return "remote-control" in tokens[1:]
    if exe == "node":  # the npm install: node <...>claude-code<...>cli.js remote-control
        return "claude" in tokens[1].lower() and "remote-control" in tokens[2:]
    return False


def list_processes():
    """[(pid, command line)] of processes whose command line mentions remote-control."""
    if os.name == "nt":
        script = ("Get-CimInstance Win32_Process -Filter \"CommandLine LIKE '%remote-control%'\" | "
                  "Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress")
        done = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                              capture_output=True, text=True, errors="replace", timeout=25,
                              creationflags=NO_WINDOW)
        if done.returncode != 0:
            raise RuntimeError(done.stderr.strip()[:200] or "powershell failed")
        if not done.stdout.strip():
            return []  # PowerShell prints nothing when nothing matches
        data = json.loads(done.stdout)
        if isinstance(data, dict):  # a single match is an object, several are a list
            data = [data]
        return [(int(item["ProcessId"]), item.get("CommandLine") or "") for item in data]
    done = subprocess.run(["ps", "-eo", "pid=,args="], capture_output=True, text=True, errors="replace",
                          timeout=10)
    found = []
    for line in done.stdout.splitlines():
        pid, _, args = line.strip().partition(" ")
        if pid.isdigit() and "remote-control" in args:
            found.append((int(pid), args.strip()))
    return found


# ---------- The Claude desktop app ----------

# Opens the Claude desktop app the way the Start menu does, unless it is already open, and waits up to ten
# seconds for its window process. It prints one word: RUNNING, LAUNCHED, NOT_INSTALLED or NOT_STARTED.
# "claude-code" is left out of the search on purpose: the desktop app carries a command-line claude.exe of its
# own, which is not the app. The app's id (Start menu entry) can be given in START_CLAUDE_APP_ID.
_DESKTOP_SCRIPT = (
    "$ErrorActionPreference='SilentlyContinue'; "
    "function ClaudeApp { Get-Process -Name Claude | Where-Object { -not ($_.Path -match 'claude-code') } }; "
    "if (ClaudeApp) { 'RUNNING'; exit 0 }; "
    "$id = $env:START_CLAUDE_APP_ID; "
    "if (-not $id) { "
    "$app = Get-StartApps | Where-Object { $_.Name -eq 'Claude' } | Select-Object -First 1; "
    "if (-not $app) { $app = Get-StartApps | Where-Object { $_.Name -like 'Claude*' } | Select-Object -First 1 }; "
    "if ($app) { $id = $app.AppID } }; "
    "if (-not $id) { 'NOT_INSTALLED'; exit 0 }; "
    "Start-Process explorer.exe -ArgumentList ('\"shell:AppsFolder\\' + $id + '\"'); "
    "for ($i = 0; $i -lt 20; $i++) { Start-Sleep -Milliseconds 500; if (ClaudeApp) { 'LAUNCHED'; exit 0 } }; "
    "'NOT_STARTED'"
)
_DESKTOP_ANSWERS = {"RUNNING": "running", "LAUNCHED": "launched", "NOT_INSTALLED": "not-installed",
                    "NOT_STARTED": "not-started"}


def launch_desktop(app_id=None, run=subprocess.run):
    """Open the Claude desktop app if it is not open. Returns "running", "launched", "not-installed" or
    "not-started". Windows only; elsewhere "not-installed". Raises if PowerShell itself fails."""
    if os.name != "nt":
        return "not-installed"
    env = dict(os.environ)
    env["START_CLAUDE_APP_ID"] = app_id or ""  # by environment, never pasted into the script
    done = run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", _DESKTOP_SCRIPT],
               capture_output=True, text=True, errors="replace", timeout=40, creationflags=NO_WINDOW, env=env)
    lines = (done.stdout or "").strip().splitlines()
    answer = _DESKTOP_ANSWERS.get(lines[-1].strip().upper()) if lines else None
    if answer is None:
        raise RuntimeError((done.stderr or "").strip()[:200] or "PowerShell gave no answer")
    return answer


def looks_installed():
    """A quick look, with no process started, for whether the Claude desktop app is on this computer."""
    if os.name != "nt":
        return False
    local = os.environ.get("LOCALAPPDATA", "")
    return bool(local) and (bool(glob.glob(os.path.join(local, "Packages", "Claude_*")))
                            or os.path.isdir(os.path.join(local, "AnthropicClaude")))


# ---------- The launcher ----------

_ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")


class RemoteLauncher:
    def __init__(self, config=load_config, find=find_claude, prefix=None, processes=list_processes,
                 popen=subprocess.Popen, startup_check=STARTUP_CHECK_SECONDS, log_path=None,
                 desktop=launch_desktop, desktop_probe=looks_installed):
        self._config = config
        self._find = find
        self._prefix = prefix          # tests start a fake command instead of the real claude
        self._processes = processes
        self._popen = popen
        self._startup_check = startup_check
        self._log_path = log_path
        self._desktop = desktop        # opens the desktop app
        self._desktop_probe = desktop_probe
        self._lock = threading.Lock()  # two quick taps must not start two copies
        self._proc = None
        self._log_offset = 0

    def status(self):
        """Cheap enough for the health check: is there a desktop app or a claude command to start?"""
        return {"available": bool(self._prefix or self._find() or self._desktop_probe())}

    def start(self):
        """Get Claude going unless it already is. Returns {"state", "via", "folder"}; raises LaunchError."""
        with self._lock:
            config = self._config()
            if config["mode"] in ("auto", "desktop"):
                result = self._start_desktop(config)
                if result is not None:
                    return result
            return self._start_cli(config)

    def _start_desktop(self, config):
        """Open the Claude desktop app. None means "not possible here, try the claude command" (auto mode only)."""
        strict = config["mode"] == "desktop"
        try:
            state = self._desktop(config["desktop_app_id"])
        except Exception as exc:
            LOG.warning("could not open the Claude desktop app: %s", exc)
            if strict:
                raise LaunchError(f"Could not open the Claude desktop app: {exc}", 502)
            return None
        if state == "running":
            return {"state": "already-running", "via": "desktop", "folder": ""}
        if state == "launched":
            LOG.info("opened the Claude desktop app")
            return {"state": "started", "via": "desktop", "folder": ""}
        if state == "not-started":
            raise LaunchError("The Claude desktop app did not open within 10 seconds. Open it by hand once, "
                              "check that it starts, then try again.", 502)
        if strict:
            raise LaunchError('The Claude desktop app was not found on this computer. Install it from '
                              'claude.com/download, or put {"mode": "cli"} in launcher.json to use the claude '
                              'command instead.', 503)
        return None

    def _start_cli(self, config):
        binary = None if self._prefix else self._find()
        prefix = self._prefix or ([binary] if binary else None)
        if not prefix:
            if config["mode"] == "auto":
                raise LaunchError("Neither the Claude desktop app nor the claude command was found on this "
                                  "computer. Install the desktop app from claude.com/download.", 503)
            raise LaunchError("The claude command was not found on the PC.", 503)
        if self._running_already():
            return {"state": "already-running", "via": "cli", "folder": self._folder_name(config)}

        log_file = self._open_log()
        try:
            self._proc = self._popen(
                build_argv(prefix, config), cwd=str(config["dir"]), env=clean_env(os.environ),
                stdin=subprocess.DEVNULL, stdout=log_file, stderr=subprocess.STDOUT,
                creationflags=NO_WINDOW | NEW_GROUP, **({} if os.name == "nt" else {"start_new_session": True}))
        except OSError as exc:
            raise LaunchError(f"Could not start Claude on the PC: {exc}", 502)
        finally:
            log_file.close()  # the child keeps its own copy of the handle

        deadline = time.monotonic() + self._startup_check
        while time.monotonic() < deadline and self._proc.poll() is None:
            time.sleep(0.1)
        if self._proc.poll() is not None:
            tail = self._log_tail()
            LOG.warning("remote-control exited at once, code=%s", self._proc.returncode)
            raise LaunchError(
                "Claude stopped right after starting." + (f" It said: {tail}" if tail else "")
                + " If it is asking whether to trust the folder, run `claude` once in that folder on the PC.",
                502)
        LOG.info("remote-control started pid=%s", self._proc.pid)
        return {"state": "started", "via": "cli", "folder": self._folder_name(config)}

    def _running_already(self):
        if self._proc is not None and self._proc.poll() is None:
            return True
        try:  # also catches one started by hand, or by an earlier run of this program
            return any(is_remote_control(command) for _, command in self._processes())
        except Exception as exc:  # a broken lookup must not make the button do nothing
            LOG.warning("could not look for a running remote-control: %s", exc)
            return False

    @staticmethod
    def _folder_name(config):
        return config["dir"].name or str(config["dir"])

    def _open_log(self):
        path = pathlib.Path(self._log_path) if self._log_path else app_dir() / "remote-control.log"
        try:
            mode = "wb" if path.exists() and path.stat().st_size > LOG_LIMIT else "ab"
        except OSError:
            mode = "ab"
        handle = open(path, mode)
        handle.write(f"\n--- {time.strftime('%Y-%m-%d %H:%M:%S')} starting ---\n".encode("utf-8"))
        handle.flush()
        self._log_path = path
        self._log_offset = handle.tell()
        return handle

    def _log_tail(self, limit=300):
        """The last bit of what the command printed during this start, without colour codes."""
        try:
            with open(self._log_path, "rb") as handle:
                handle.seek(self._log_offset)
                text = handle.read(20000).decode("utf-8", errors="replace")
        except OSError:
            return ""
        return " ".join(_ANSI.sub("", text).split())[-limit:]


# ---------- The PC's network cards (for Wake-on-LAN and the home-network fallback) ----------

_VIRTUAL = re.compile(r"tailscale|vethernet|virtual|vpn|tap-|wintun|wireguard|loopback|bluetooth|hyper-v|"
                      r"vmware|vbox|npcap|wan miniport", re.I)
_WIRELESS = re.compile(r"wi-?fi|wireless|wlan|802\.11", re.I)
_MAC = re.compile(r"([0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2}")
_IPV4 = re.compile(r"\d{1,3}(\.\d{1,3}){3}")


def _wired_first(cards):
    return sorted(cards, key=lambda card: bool(_WIRELESS.search(card["name"] + " " + card["adapter"])))


def parse_getmac(text):
    """Turn `getmac /v /fo csv /nh` output into [{"name", "adapter", "mac", "ip", "prefix"}], wired cards first.

    Only cards that are connected right now are listed (an unplugged or disabled card has no
    \\Device\\Tcpip_ transport), and virtual adapters such as Tailscale, VPNs and Hyper-V are left out.
    This lists no addresses ("ip" is None); it is the fallback for `parse_cards_json`.
    """
    cards = []
    for row in csv.reader(io.StringIO(text)):
        if len(row) < 4:
            continue
        name, adapter, mac, transport = (cell.strip() for cell in row[:4])
        if not _MAC.fullmatch(mac) or not transport.startswith("\\Device\\Tcpip_"):
            continue
        if _VIRTUAL.search(f"{name} {adapter}"):
            continue
        cards.append({"name": name, "adapter": adapter, "mac": mac.replace("-", ":").upper(), "ip": None,
                      "prefix": None})
    return _wired_first(cards)


def parse_cards_json(text):
    """Turn the PowerShell output below into the same list as parse_getmac, with each card's IPv4 address."""
    if not text.strip():
        return []
    data = json.loads(text)
    if isinstance(data, dict):  # one card is an object, several are a list
        data = [data]
    cards = []
    for item in data:
        name, adapter, mac = (str(item.get(key) or "").strip() for key in ("Name", "Adapter", "Mac"))
        if not _MAC.fullmatch(mac) or _VIRTUAL.search(f"{name} {adapter}"):
            continue
        ip = str(item.get("Ip") or "")
        prefix = item.get("Prefix")
        cards.append({"name": name, "adapter": adapter, "mac": mac.replace("-", ":").upper(),
                      "ip": ip if _IPV4.fullmatch(ip) else None,
                      "prefix": prefix if isinstance(prefix, int) and 0 <= prefix <= 32 else None})
    return _wired_first(cards)


# Physical cards that are up, with their MAC and IPv4 address. Locale-proof: it asks for properties, not text.
_CARDS_SCRIPT = (
    "$ErrorActionPreference='SilentlyContinue'; "
    "Get-NetAdapter -Physical | Where-Object { $_.Status -eq 'Up' } | ForEach-Object { "
    "$a = Get-NetIPAddress -InterfaceIndex $_.ifIndex -AddressFamily IPv4 | Select-Object -First 1; "
    "[pscustomobject]@{ Name = $_.Name; Adapter = $_.InterfaceDescription; Mac = $_.MacAddress; "
    "Ip = $a.IPAddress; Prefix = $a.PrefixLength } } | ConvertTo-Json -Compress"
)


def local_cards(run=subprocess.run):
    """This PC's connected physical network cards, wired first. Windows only; elsewhere an empty list."""
    if os.name != "nt":
        return []
    try:
        done = run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", _CARDS_SCRIPT],
                   capture_output=True, text=True, errors="replace", timeout=25, creationflags=NO_WINDOW)
        cards = parse_cards_json(done.stdout)
        if cards:
            return cards
    except Exception as exc:  # fall back to the older, address-less way
        LOG.warning("could not list network cards with PowerShell: %s", exc)
    done = run(["getmac", "/v", "/fo", "csv", "/nh"], capture_output=True, text=True, errors="replace",
               timeout=10, creationflags=NO_WINDOW)
    return parse_getmac(done.stdout)
