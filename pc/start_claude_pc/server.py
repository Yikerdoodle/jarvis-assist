"""The listener the phone talks to.

GET  /v1/health        is this program up, and can `claude` be found?      (tailnet device)
POST /v1/remote/start  start Claude Remote Control on this PC              (tailnet device + token)
GET  /v1/wake-info     this PC's network cards, to fill in the MAC address (tailnet device + token)

It listens on loopback and on this PC's Tailscale address only, never on the home network.
"""

import json
import logging
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import __version__
from .auth import TailnetPeerCheck, app_dir, load_or_create_token, token_ok
from .launcher import LaunchError, RemoteLauncher, local_macs

LOG = logging.getLogger("start_claude_pc")
MAX_BODY = 64 * 1024


def make_handler(peer_check, token, launcher):

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

        def _peer_ok(self):
            allowed, who = peer_check(self.client_address[0])
            if not allowed:
                LOG.warning("refused %s: %s", self.client_address[0], who)
                self._send_json(403, {"error": "device not allowed"})
            return allowed

        def _authorised(self):
            """A device of the owner's tailnet, and the token."""
            if not self._peer_ok():
                return False
            if not token_ok(self.headers.get("Authorization"), token):
                self._send_json(401, {"error": "bad token"})
                return False
            return True

        def do_GET(self):
            if self.path == "/v1/health":
                if not self._peer_ok():
                    return
                return self._send_json(200, {"ok": True, "version": __version__, "remote": launcher.status()})
            if self.path == "/v1/wake-info":
                if not self._authorised():
                    return
                try:
                    return self._send_json(200, {"macs": local_macs()})
                except Exception as exc:  # getmac missing or slow: the phone can still be filled in by hand
                    LOG.warning("wake-info failed: %s", exc)
                    return self._send_json(200, {"macs": []})
            return self._send_json(404, {"error": "not found"})

        def do_POST(self):
            # Read the body before any early reply: closing with unread data makes Windows reset the
            # connection, and the phone would see a network error instead of the real status.
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            if 0 < length <= MAX_BODY:
                self.rfile.read(length)  # nothing in it is used: what runs comes only from launcher.json
            if self.path != "/v1/remote/start":
                return self._send_json(404, {"error": "not found"})
            if not self._authorised():
                return
            started = time.monotonic()
            try:
                result = launcher.start()
            except LaunchError as exc:
                LOG.info("start refused status=%s seconds=%.1f", exc.status, time.monotonic() - started)
                return self._send_json(exc.status, {"error": str(exc)})
            LOG.info("start state=%s seconds=%.1f", result["state"], time.monotonic() - started)
            self._send_json(200, {"ok": True, **result})

    return Handler


def serve(address, port, peer_check, token, launcher):
    server = ThreadingHTTPServer((address, port), make_handler(peer_check, token, launcher))
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
    peer_check = TailnetPeerCheck(allowed_nodes=set(allowed.split(",")) if allowed else None)
    token = load_or_create_token()
    launcher = RemoteLauncher()  # one shared copy: the loopback and Tailscale listeners must not each start Claude
    servers = {}
    while True:  # Tailscale may come up after us, so keep trying to bind its address
        wanted = ["127.0.0.1"]
        ts_ip = None if os.environ.get("START_CLAUDE_LOOPBACK_ONLY") == "1" else peer_check.tailscale_ipv4()
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
