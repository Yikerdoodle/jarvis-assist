"""The listener the phone talks to.

GET  /v1/health        is this computer up, what is it called, can `claude` be found?
POST /v1/remote/start  start Claude Remote Control on this computer (does nothing if it already runs)
POST /v1/pair          hand the phone everything it needs to use this computer: name, secret, network cards

Who may do what depends on how the caller got here (see auth.py):

                  loopback        tailnet (your own device)   home network
  health          yes             yes                         signed request
  remote/start    signed request  yes                         signed request
  pair            no              yes                         no

Pairing is only offered over Tailscale, where the caller is proven to be one of your own devices. The home
network is only a fallback for when Tailscale is not available.
"""

import json
import logging
import os
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import __version__
from .auth import PeerCheck, SignatureChecker, app_dir, load_or_create_token
from .launcher import LaunchError, RemoteLauncher, local_cards

LOG = logging.getLogger("start_claude_pc")
MAX_BODY = 64 * 1024
PAIR_LIMIT = 10  # pairings per minute: it hands out the secret, so it is not for hammering


def computer_name():
    """What this computer is called on the phone. START_CLAUDE_NAME overrides the Windows computer name."""
    return os.environ.get("START_CLAUDE_NAME") or socket.gethostname()


class PairLimiter:
    def __init__(self, clock=time.monotonic):
        self._clock = clock
        self._times = []
        self._lock = threading.Lock()

    def allow(self):
        now = self._clock()
        with self._lock:
            self._times = [t for t in self._times if now - t < 60]
            if len(self._times) >= PAIR_LIMIT:
                return False
            self._times.append(now)
            return True


def make_handler(peer_check, token, launcher, name=None, checker=None, cards=local_cards):
    name = name or computer_name()
    checker = checker or SignatureChecker()
    limiter = PairLimiter()

    class Handler(BaseHTTPRequestHandler):
        server_version = f"StartClaudePC/{__version__}"
        protocol_version = "HTTP/1.0"

        def log_message(self, fmt, *args):  # metadata only
            LOG.info("%s %s", self.address_string(), fmt % args)

        def _send_json(self, status, payload):
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _caller(self):
            """The kind of caller, or None after answering 403."""
            kind, who = peer_check(self.client_address[0])
            if kind is None:
                LOG.warning("refused %s: %s", self.client_address[0], who)
                self._send_json(403, {"error": "device not allowed"})
            return kind

        def _signed(self, method):
            if checker.check(self.headers.get("Authorization"), token, method, self.path):
                return True
            self._send_json(401, {"error": "missing or bad signature"})
            return False

        def do_GET(self):
            if self.path != "/v1/health":
                return self._send_json(404, {"error": "not found"})
            kind = self._caller()
            if kind is None or (kind == "lan" and not self._signed("GET")):
                return
            self._send_json(200, {"ok": True, "version": __version__, "name": name, "remote": launcher.status()})

        def do_POST(self):
            # Read the body before any early reply: closing with unread data makes Windows reset the
            # connection, and the phone would see a network error instead of the real status.
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            if 0 < length <= MAX_BODY:
                self.rfile.read(length)  # nothing in it is used: what runs comes only from launcher.json
            if self.path == "/v1/remote/start":
                return self._remote_start()
            if self.path == "/v1/pair":
                return self._pair()
            self._send_json(404, {"error": "not found"})

        def _remote_start(self):
            kind = self._caller()
            if kind is None or (kind in ("loopback", "lan") and not self._signed("POST")):
                return
            started = time.monotonic()
            try:
                result = launcher.start()
            except LaunchError as exc:
                LOG.info("start refused status=%s seconds=%.1f", exc.status, time.monotonic() - started)
                return self._send_json(exc.status, {"error": str(exc)})
            LOG.info("start state=%s via=%s seconds=%.1f", result["state"], kind, time.monotonic() - started)
            self._send_json(200, {"ok": True, "name": name, **result})

        def _pair(self):
            kind = self._caller()
            if kind is None:
                return
            if kind != "tailnet":
                return self._send_json(403, {"error": "pairing only works over Tailscale, from one of your own devices"})
            if not limiter.allow():
                return self._send_json(429, {"error": "too many pairing attempts, wait a minute"})
            try:
                found = cards()
            except Exception as exc:  # the phone can still use the name and secret
                LOG.warning("could not list network cards: %s", exc)
                found = []
            LOG.info("paired with %s", peer_check(self.client_address[0])[1])
            self._send_json(200, {"ok": True, "name": name, "token": token, "port": self.server.server_address[1],
                                  "cards": found, "version": __version__})

    return Handler


def serve(address, port, peer_check, token, launcher, name=None, checker=None, cards=local_cards):
    server = ThreadingHTTPServer((address, port), make_handler(peer_check, token, launcher, name, checker, cards))
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    LOG.info("listening on %s:%s", address, port)
    return server


def setup_logging():
    """Log to a small rotating file (the program normally runs hidden, with no console)."""
    from logging.handlers import RotatingFileHandler
    handlers = [RotatingFileHandler(app_dir() / "pc.log", maxBytes=500_000, backupCount=2, encoding="utf-8")]
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", handlers=handlers)


def main():
    setup_logging()
    port = int(os.environ.get("START_CLAUDE_PORT", "8788"))
    allowed = os.environ.get("START_CLAUDE_ALLOWED_NODES")
    lan = os.environ.get("START_CLAUDE_NO_LAN") != "1"
    peer_check = PeerCheck(allowed_nodes=set(allowed.split(",")) if allowed else None, allow_lan=lan)
    token = load_or_create_token()
    launcher = RemoteLauncher()  # one shared copy: every listener below must not start its own Claude
    servers = {}
    while True:
        if lan:
            wanted = ["0.0.0.0"]  # every network card; callers are sorted by address, see auth.py
        else:  # START_CLAUDE_NO_LAN=1: Tailscale and this PC only
            wanted = ["127.0.0.1"]
            ts_ip = peer_check.tailscale_ipv4()  # Tailscale may come up after us, so keep trying
            if ts_ip:
                wanted.append(ts_ip)
        for address in wanted:
            if address not in servers:
                try:
                    servers[address] = serve(address, port, peer_check, token, launcher)
                except OSError as exc:
                    LOG.warning("cannot bind %s:%s (%s)", address, port, exc)
        time.sleep(30)


if __name__ == "__main__":
    main()
