"""Start Claude's Remote Control on this PC, so the Claude app on the phone can start sessions here.

`claude remote-control` is the official command: it keeps a small server running on this PC, and the Claude
app (or claude.ai/code) lists the PC and starts new sessions on it. Claude and its tools run on this PC; the
phone only shows them. This module starts that command hidden, once, when the phone asks.

What a request may ask for is deliberately tiny: "start it". The folder and the options come from
launcher.json on the PC, never from the request, so a request can never choose what runs or where.
"""

import csv
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
from .engines import clean_env, find_claude

LOG = logging.getLogger("jarvis_bridge")
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
NEW_GROUP = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)

STARTUP_CHECK_SECONDS = 4.0   # a start that is still alive after this long is treated as working
LOG_LIMIT = 1_000_000         # the child's output file is started afresh once it passes this size
SPAWN_MODES = ("same-dir", "worktree")
# "bypassPermissions" is left out on purpose: sessions started by a tap on a phone should not run unchecked.
# Anyone who really wants that can start `claude remote-control` by hand.
PERMISSION_MODES = ("default", "acceptEdits", "auto", "dontAsk", "plan")
CONFIG_KEYS = ("dir", "name", "spawn", "capacity", "permission_mode")


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
        raise LaunchError("launcher.json has settings the bridge does not know: " + ", ".join(unknown)
                          + ". Allowed: " + ", ".join(CONFIG_KEYS))

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
    return {"dir": folder, "name": name.strip() if name else None, "spawn": spawn, "capacity": capacity,
            "permission_mode": mode}


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


# ---------- The launcher ----------

_ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")


class RemoteLauncher:
    def __init__(self, config=load_config, find=find_claude, prefix=None, processes=list_processes,
                 popen=subprocess.Popen, startup_check=STARTUP_CHECK_SECONDS, log_path=None):
        self._config = config
        self._find = find
        self._prefix = prefix          # tests start a fake command instead of the real claude
        self._processes = processes
        self._popen = popen
        self._startup_check = startup_check
        self._log_path = log_path
        self._lock = threading.Lock()  # two quick taps must not start two copies
        self._proc = None
        self._log_offset = 0

    def status(self):
        """Cheap enough for the health check: is there a claude command to start?"""
        return {"available": bool(self._prefix or self._find())}

    def start(self):
        """Start Remote Control unless it is already running. Returns {"state", "folder"}; raises LaunchError."""
        with self._lock:
            config = self._config()
            binary = None if self._prefix else self._find()
            prefix = self._prefix or ([binary] if binary else None)
            if not prefix:
                raise LaunchError("The claude command was not found on the PC.", 503)
            if self._running_already():
                return {"state": "already-running", "folder": self._folder_name(config)}

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
            return {"state": "started", "folder": self._folder_name(config)}

    def _running_already(self):
        if self._proc is not None and self._proc.poll() is None:
            return True
        try:  # also catches one started by hand, or by an earlier run of this bridge
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


# ---------- Finding the PC's network card address (for Wake-on-LAN) ----------

_VIRTUAL = re.compile(r"tailscale|vethernet|virtual|vpn|tap-|wintun|wireguard|loopback|bluetooth|hyper-v|"
                      r"vmware|vbox|npcap|wan miniport", re.I)
_WIRELESS = re.compile(r"wi-?fi|wireless|wlan|802\.11", re.I)
_MAC = re.compile(r"([0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2}")


def parse_getmac(text):
    """Turn `getmac /v /fo csv /nh` output into [{"name", "adapter", "mac"}], wired cards first.

    Only cards that are connected right now are listed (an unplugged or disabled card has no
    \\Device\\Tcpip_ transport), and virtual adapters such as Tailscale, VPNs and Hyper-V are left out.
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
        cards.append({"name": name, "adapter": adapter, "mac": mac.replace("-", ":").upper()})
    cards.sort(key=lambda card: bool(_WIRELESS.search(card["name"] + " " + card["adapter"])))
    return cards


def local_macs(run=subprocess.run):
    """The PC's network cards, for the phone's "get it from the PC" button. Windows only."""
    if os.name != "nt":
        return []
    done = run(["getmac", "/v", "/fo", "csv", "/nh"], capture_output=True, text=True, errors="replace",
               timeout=10, creationflags=NO_WINDOW)
    return parse_getmac(done.stdout)
