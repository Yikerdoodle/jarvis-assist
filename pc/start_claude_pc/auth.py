"""Who may talk to this program: a device of the owner's own tailnet, and a secret token."""

import hmac
import json
import os
import pathlib
import secrets
import shutil
import subprocess
import time

LOOPBACK = ("127.0.0.1", "::1")


def app_dir():
    """Where the token, launcher.json and the logs live.

    Defaults to a `data` folder next to the program (ignored by git). It is deliberately not under
    %LOCALAPPDATA%: Windows redirects that for packaged apps such as the Claude desktop app, so a helper
    running inside one would see different files from this program.
    """
    override = os.environ.get("START_CLAUDE_DATA")
    path = pathlib.Path(override) if override else pathlib.Path(__file__).resolve().parents[1] / "data"
    path.mkdir(parents=True, exist_ok=True)
    return path


def load_or_create_token(path=None):
    """The token lives in a file; it is created once and never printed."""
    path = pathlib.Path(path) if path else app_dir() / "token"
    if path.exists():
        value = path.read_text(encoding="utf-8").strip()
        if value:
            return value
    value = secrets.token_urlsafe(32)
    path.write_text(value, encoding="utf-8")
    return value


def token_ok(header_value, expected):
    if not header_value or not header_value.startswith("Bearer "):
        return False
    return hmac.compare_digest(header_value[len("Bearer "):].strip().encode(), expected.encode())


def find_tailscale():
    found = shutil.which("tailscale")
    if found:
        return found
    default = r"C:\Program Files\Tailscale\tailscale.exe"
    return default if os.path.exists(default) else None


def _run_json(args):
    done = subprocess.run(args, capture_output=True, text=True, timeout=10,
                          creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip() or "tailscale failed")
    return json.loads(done.stdout)


class TailnetPeerCheck:
    """Accept loopback, or a tailnet node that belongs to the same user as this PC."""

    def __init__(self, tailscale=None, ttl=60.0, allowed_nodes=None, run_json=_run_json):
        self.tailscale = tailscale or find_tailscale()
        self.ttl = ttl
        self.allowed_nodes = allowed_nodes  # optional set of short node names
        self.run_json = run_json
        self._cache = {}

    def own_ips(self):
        if not self.tailscale:
            return []
        try:
            return self.run_json([self.tailscale, "status", "--json"])["Self"]["TailscaleIPs"]
        except Exception:
            return []

    def tailscale_ipv4(self):
        for ip in self.own_ips():
            if "." in ip:
                return ip
        return None

    def __call__(self, ip):
        if ip in LOOPBACK:
            return True, "loopback"
        if not self.tailscale:
            return False, "tailscale not found"
        now = time.monotonic()
        hit = self._cache.get(ip)
        if hit and now - hit[0] < self.ttl:
            return hit[1], hit[2]
        try:
            me = self.run_json([self.tailscale, "status", "--json"])["Self"]["UserID"]
            who = self.run_json([self.tailscale, "whois", "--json", ip])
            node = who["Node"]
            short = node.get("Name", "").split(".")[0]
            if node.get("User") != me:
                result = (False, "peer belongs to a different tailnet user")
            elif self.allowed_nodes and short not in self.allowed_nodes:
                result = (False, "peer is not on the allowed device list")
            else:
                result = (True, short)
        except Exception as exc:  # fail closed
            result = (False, f"whois failed: {exc}")
        self._cache[ip] = (now, result[0], result[1])
        return result
