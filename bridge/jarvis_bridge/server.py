"""HTTP bridge: GET /v1/health and POST /v1/ask (streams newline-delimited JSON)."""

import json
import logging
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import __version__
from .auth import TailnetPeerCheck, load_or_create_token, token_ok
from .engines import default_engines

LOG = logging.getLogger("jarvis_bridge")
MAX_BODY = 256 * 1024
MAX_TEXT = 8000


def make_handler(engines, peer_check, token):
    class Handler(BaseHTTPRequestHandler):
        server_version = f"JarvisBridge/{__version__}"
        protocol_version = "HTTP/1.0"  # body streams until the connection closes

        def log_message(self, fmt, *args):  # metadata only; never log what was asked
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

        def do_GET(self):
            if self.path != "/v1/health":
                return self._send_json(404, {"error": "not found"})
            if not self._peer_ok():
                return
            self._send_json(200, {
                "ok": True, "version": __version__,
                "engines": {name: engine.status() for name, engine in engines.items()},
            })

        def do_POST(self):
            # Read the body before any early reply: closing with unread data makes Windows reset the
            # connection, and the phone would see a network error instead of the real status.
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            raw = self.rfile.read(length) if 0 < length <= MAX_BODY else b""
            if self.path != "/v1/ask":
                return self._send_json(404, {"error": "not found"})
            if not self._peer_ok():
                return
            if not token_ok(self.headers.get("Authorization"), token):
                return self._send_json(401, {"error": "bad token"})
            if not raw:
                return self._send_json(413, {"error": "bad body size"})
            try:
                req = json.loads(raw.decode("utf-8"))
                text = str(req.get("text", "")).strip()
            except (ValueError, UnicodeDecodeError, AttributeError):
                return self._send_json(400, {"error": "bad json"})
            if not text or len(text) > MAX_TEXT:
                return self._send_json(400, {"error": "text missing or too long"})
            req["text"] = text
            name = req.get("engine", "claude")
            engine = engines.get(name)
            if engine is None:
                return self._send_json(400, {"error": f"unknown engine {name}"})
            self._stream(engine, req)

        def _stream(self, engine, req):
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            cancel = threading.Event()
            started = time.monotonic()
            outcome = "ok"
            try:
                for event in engine.stream(req, cancel):
                    self.wfile.write((json.dumps(event) + "\n").encode("utf-8"))
                    self.wfile.flush()
                    if event["type"] == "error":
                        outcome = "error"
            except (BrokenPipeError, ConnectionResetError, OSError):
                cancel.set()
                outcome = "client-left"
            except Exception as exc:  # engine bug: tell the client rather than hang
                outcome = "crash"
                LOG.exception("engine crashed")
                try:
                    self.wfile.write((json.dumps({"type": "error", "message": f"bridge error: {exc}"}) + "\n").encode())
                except OSError:
                    pass
            LOG.info("ask engine=%s outcome=%s seconds=%.1f", engine.name, outcome, time.monotonic() - started)

    return Handler


def serve(address, port, engines, peer_check, token):
    server = ThreadingHTTPServer((address, port), make_handler(engines, peer_check, token))
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    LOG.info("listening on %s:%s", address, port)
    return server


def setup_logging():
    """Log to a small rotating file (the bridge normally runs hidden, with no console)."""
    from logging.handlers import RotatingFileHandler
    from .auth import app_dir
    handlers = [RotatingFileHandler(app_dir() / "bridge.log", maxBytes=500_000, backupCount=2, encoding="utf-8")]
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", handlers=handlers)


def main():
    setup_logging()
    port = int(os.environ.get("JARVIS_BRIDGE_PORT", "8787"))
    allowed = os.environ.get("JARVIS_BRIDGE_ALLOWED_NODES")
    peer_check = TailnetPeerCheck(allowed_nodes=set(allowed.split(",")) if allowed else None)
    token = load_or_create_token()
    engines = default_engines()
    servers = {}
    while True:  # Tailscale may come up after us, so keep trying to bind its address
        wanted = ["127.0.0.1"]
        ts_ip = None if os.environ.get("JARVIS_BRIDGE_LOOPBACK_ONLY") == "1" else peer_check.tailscale_ipv4()
        if ts_ip:
            wanted.append(ts_ip)
        for address in wanted:
            if address not in servers:
                try:
                    servers[address] = serve(address, port, engines, peer_check, token)
                except OSError as exc:
                    LOG.warning("cannot bind %s:%s (%s)", address, port, exc)
        time.sleep(30)


if __name__ == "__main__":
    main()
