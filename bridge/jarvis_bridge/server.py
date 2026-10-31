"""HTTP bridge: GET /v1/health, POST /v1/ask (streams newline-delimited JSON), POST /v1/adb/pair,
POST /v1/remote/start (start Claude Remote Control on this PC), GET /v1/wake-info (this PC's network cards)."""

import json
import logging
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import __version__
from .adbpair import AdbPairer, validate_request
from .auth import TailnetPeerCheck, load_or_create_token, token_ok
from .engines import default_engines
from .launcher import LaunchError, RemoteLauncher, local_macs

LOG = logging.getLogger("jarvis_bridge")
MAX_BODY = 256 * 1024
MAX_TEXT = 8000


def make_handler(engines, peer_check, token, pairer=None, launcher=None):
    pairer = pairer or AdbPairer()
    launcher = launcher or RemoteLauncher()

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

        def _authorised(self):
            """Same checks as /v1/ask: a device of the owner's tailnet, and the app token."""
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
                return self._send_json(200, {
                    "ok": True, "version": __version__,
                    "engines": {name: engine.status() for name, engine in engines.items()},
                    "adb": pairer.status(),
                    "remote": launcher.status(),
                })
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
            raw = self.rfile.read(length) if 0 < length <= MAX_BODY else b""
            if self.path == "/v1/adb/pair":
                return self._adb_pair(raw)
            if self.path == "/v1/remote/start":
                return self._remote_start()
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

        def _remote_start(self):
            """Start `claude remote-control` on this PC. The request carries nothing: what runs, and in which
            folder, comes from launcher.json on the PC (see launcher.py)."""
            if not self._authorised():
                return
            started = time.monotonic()
            try:
                result = launcher.start()
            except LaunchError as exc:
                LOG.info("remote start refused status=%s seconds=%.1f", exc.status, time.monotonic() - started)
                return self._send_json(exc.status, {"error": str(exc)})
            LOG.info("remote start state=%s seconds=%.1f", result["state"], time.monotonic() - started)
            self._send_json(200, {"ok": True, **result})

        def _adb_pair(self, raw):
            """The phone hands over a Wireless-debugging pairing code; the PC runs `adb pair` itself.

            Only a device of the owner's own tailnet (checked with tailscale whois) may call this, and it can do
            nothing but try to pair this PC's adb with the caller's own pairing server using a code the phone
            is showing. No app token is needed, so the phone-side helper needs no secret. Calls are rate-limited.
            """
            if not self._peer_ok():
                return
            try:
                req = json.loads(raw.decode("utf-8"))
                code, port = req.get("code"), req.get("port")
            except (ValueError, UnicodeDecodeError, AttributeError):
                return self._send_json(400, {"error": "bad json"})
            try:
                validate_request(code, port)  # malformed requests are refused before they use up the rate limit
            except ValueError as exc:
                return self._send_json(400, {"error": str(exc)})
            if pairer.rate_limited():
                return self._send_json(429, {"error": "too many pairing attempts, wait a minute"})
            started = time.monotonic()
            result = pairer.pair(code, port, self.client_address[0])
            LOG.info("adb pair paired=%s connected=%s seconds=%.1f", result["paired"], result["connected"],
                     time.monotonic() - started)
            self._send_json(200, result)

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


def serve(address, port, engines, peer_check, token, pairer=None, launcher=None):
    server = ThreadingHTTPServer((address, port), make_handler(engines, peer_check, token, pairer, launcher))
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
    pairer = AdbPairer()  # one shared instance, so the rate limit covers every listening address
    launcher = RemoteLauncher()  # shared too: the loopback and Tailscale listeners must not each start a copy
    servers = {}
    while True:  # Tailscale may come up after us, so keep trying to bind its address
        wanted = ["127.0.0.1"]
        ts_ip = None if os.environ.get("JARVIS_BRIDGE_LOOPBACK_ONLY") == "1" else peer_check.tailscale_ipv4()
        if ts_ip:
            wanted.append(ts_ip)
        for address in wanted:
            if address not in servers:
                try:
                    servers[address] = serve(address, port, engines, peer_check, token, pairer, launcher)
                except OSError as exc:
                    LOG.warning("cannot bind %s:%s (%s)", address, port, exc)
        time.sleep(30)


if __name__ == "__main__":
    main()
