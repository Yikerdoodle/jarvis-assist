"""Who may talk to this program, and how it knows.

Every caller is sorted into one of four kinds by the address it connects from:

  loopback  a program on this PC itself
  tailnet   a device on Tailscale, and `tailscale whois` says it belongs to the same Tailscale user as this PC.
            Tailscale has already proved who it is, so no secret is needed.
  lan       a device on the home network. Nothing proves who it is, so it must sign each request with the secret.
  (none)    everything else, including the internet and other people's tailnet devices: refused.

A signed request never sends the secret itself, only a short-lived proof made from it, so someone listening on
the home Wi-Fi learns nothing they can reuse.
"""

import hmac
import ipaddress
import json
import os
import pathlib
import re
import secrets
import shutil
import subprocess
import threading
import time
from hashlib import sha256

LOOPBACK = ("127.0.0.1", "::1")
TAILNET_NET = ipaddress.ip_network("100.64.0.0/10")
HOME_NETS = tuple(ipaddress.ip_network(net) for net in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
SIGNATURE_WINDOW_SECONDS = 60


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


# ---------- Signed requests ----------

def sign(token, method, path, timestamp, nonce):
    message = f"{method.upper()}\n{path}\n{timestamp}\n{nonce}".encode("utf-8")
    return hmac.new(token.encode("utf-8"), message, sha256).hexdigest()


def auth_header(token, method, path, timestamp=None, nonce=None):
    """What a caller puts in the Authorization header. (The phone does the same thing in Signer.java.)"""
    timestamp = int(time.time()) if timestamp is None else timestamp
    nonce = secrets.token_hex(8) if nonce is None else nonce
    return f"StartClaude t={timestamp},n={nonce},s={sign(token, method, path, timestamp, nonce)}"


_HEADER = re.compile(r"StartClaude t=(\d{1,12}),n=([0-9a-f]{16}),s=([0-9a-f]{64})")


class SignatureChecker:
    """Accepts a signed request once: it must be made with the token, be fresh, and never have been seen before."""

    def __init__(self, clock=time.time):
        self._clock = clock
        self._seen = {}
        self._lock = threading.Lock()

    def check(self, header_value, token, method, path):
        match = _HEADER.fullmatch(header_value or "")
        if not match:
            return False
        timestamp, nonce, signature = int(match.group(1)), match.group(2), match.group(3)
        now = self._clock()
        if abs(now - timestamp) > SIGNATURE_WINDOW_SECONDS:
            return False
        if not hmac.compare_digest(signature, sign(token, method, path, timestamp, nonce)):
            return False
        with self._lock:
            self._seen = {sig: expires for sig, expires in self._seen.items() if expires > now}
            if signature in self._seen:
                return False  # the very same request again: someone replaying it
            self._seen[signature] = now + 2 * SIGNATURE_WINDOW_SECONDS
        return True


# ---------- Who is calling? ----------

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


class PeerCheck:
    """Sorts a caller's address into "loopback", "tailnet" or "lan", or (None, reason) to refuse it."""

    def __init__(self, tailscale=None, ttl=60.0, allowed_nodes=None, run_json=_run_json, allow_lan=True):
        self.tailscale = tailscale or find_tailscale()
        self.ttl = ttl
        self.allowed_nodes = allowed_nodes  # optional set of short node names
        self.run_json = run_json
        self.allow_lan = allow_lan
        self._cache = {}
        self._own = (0.0, [])

    def own_ips(self):
        """This PC's own Tailscale addresses (cached for a minute: it is asked on every request)."""
        now = time.monotonic()
        if now - self._own[0] < self.ttl and self._own[1]:
            return self._own[1]
        ips = []
        if self.tailscale:
            try:
                ips = list(self.run_json([self.tailscale, "status", "--json"])["Self"]["TailscaleIPs"])
            except Exception:
                ips = []
        self._own = (now, ips)
        return ips

    def tailscale_ipv4(self):
        for ip in self.own_ips():
            if "." in ip:
                return ip
        return None

    def __call__(self, ip):
        if ip in LOOPBACK:
            return "loopback", "loopback"
        try:
            address = ipaddress.ip_address(ip)
        except ValueError:
            return None, "unreadable address"
        if address in TAILNET_NET:
            if ip in self.own_ips():
                return "loopback", "this computer"  # a program here that used the Tailscale address
            return self._tailnet_peer(ip)
        if self.allow_lan and any(address in net for net in HOME_NETS):
            return "lan", ip
        return None, "not a Tailscale or home-network address"

    def _tailnet_peer(self, ip):
        if not self.tailscale:
            return None, "tailscale not found"
        now = time.monotonic()
        hit = self._cache.get(ip)
        if hit and now - hit[0] < self.ttl:
            return hit[1], hit[2]
        try:
            me = self.run_json([self.tailscale, "status", "--json"])["Self"]["UserID"]
            node = self.run_json([self.tailscale, "whois", "--json", ip])["Node"]
            short = node.get("Name", "").split(".")[0]
            if node.get("User") != me:
                result = (None, "peer belongs to a different tailnet user")
            elif self.allowed_nodes and short not in self.allowed_nodes:
                result = (None, "peer is not on the allowed device list")
            else:
                result = ("tailnet", short)
        except Exception as exc:  # fail closed
            result = (None, f"whois failed: {exc}")
        self._cache[ip] = (now, result[0], result[1])
        return result
