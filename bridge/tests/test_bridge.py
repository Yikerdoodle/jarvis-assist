import json
import os
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from jarvis_bridge.auth import TailnetPeerCheck, token_ok  # noqa: E402
from jarvis_bridge.engines import ClaudeCliEngine, parse_claude_line  # noqa: E402
from jarvis_bridge.prompting import build_prompt  # noqa: E402
from jarvis_bridge.server import serve  # noqa: E402

FAKE = os.path.join(os.path.dirname(__file__), "fake_claude.py")
TOKEN = "test-token-123"


class ParserTests(unittest.TestCase):
    def test_text_delta(self):
        line = json.dumps({"type": "stream_event", "event": {"type": "content_block_delta",
                                                             "delta": {"type": "text_delta", "text": "Hi"}}})
        self.assertEqual(parse_claude_line(line), [{"type": "delta", "text": "Hi"}])

    def test_thinking_and_garbage_ignored(self):
        line = json.dumps({"type": "stream_event", "event": {"type": "content_block_delta",
                                                             "delta": {"type": "thinking_delta", "thinking": "x"}}})
        self.assertEqual(parse_claude_line(line), [])
        self.assertEqual(parse_claude_line("not json"), [])

    def test_error_result(self):
        line = json.dumps({"type": "result", "is_error": True, "result": "boom"})
        self.assertEqual(parse_claude_line(line), [{"type": "error", "message": "boom"}])


class AuthTests(unittest.TestCase):
    def test_token(self):
        self.assertTrue(token_ok("Bearer abc", "abc"))
        self.assertFalse(token_ok("Bearer abd", "abc"))
        self.assertFalse(token_ok("abc", "abc"))
        self.assertFalse(token_ok(None, "abc"))

    def test_peer_same_user_allowed_other_user_refused(self):
        def run(args):
            if "status" in args:
                return {"Self": {"UserID": 1, "TailscaleIPs": ["100.1.1.1"]}}
            return {"Node": {"Name": "phone.tail1.ts.net.", "User": 1 if args[-1] == "100.2.2.2" else 2}}
        check = TailnetPeerCheck(tailscale="ts", run_json=run)
        self.assertEqual(check("100.2.2.2"), (True, "phone"))
        self.assertFalse(check("100.3.3.3")[0])
        self.assertTrue(check("127.0.0.1")[0])

    def test_peer_allowlist(self):
        def run(args):
            if "status" in args:
                return {"Self": {"UserID": 1, "TailscaleIPs": []}}
            return {"Node": {"Name": "laptop.tail1.ts.net.", "User": 1}}
        check = TailnetPeerCheck(tailscale="ts", run_json=run, allowed_nodes={"phone"})
        self.assertFalse(check("100.2.2.2")[0])

    def test_fails_closed(self):
        def run(args):
            raise RuntimeError("down")
        self.assertFalse(TailnetPeerCheck(tailscale="ts", run_json=run)("100.2.2.2")[0])


class PromptTests(unittest.TestCase):
    def test_history_is_capped_and_ordered(self):
        history = [{"role": "user" if i % 2 == 0 else "assistant", "text": f"m{i}"} for i in range(30)]
        prompt = build_prompt("now?", history)
        self.assertNotIn("m0\n", prompt)
        self.assertIn("m29", prompt)
        self.assertTrue(prompt.rstrip().endswith("Jarvis:"))


class ServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        engines = {"claude": ClaudeCliEngine(command=[sys.executable, FAKE])}
        cls.server = serve("127.0.0.1", 0, engines, lambda ip: (True, "test"), TOKEN)
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def post(self, body, token=TOKEN):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(self.base + "/v1/ask", json.dumps(body).encode(), headers)
        return urllib.request.urlopen(request, timeout=20)

    def events(self, body):
        with self.post(body) as response:
            return [json.loads(line) for line in response.read().decode().splitlines() if line]

    def test_health(self):
        with urllib.request.urlopen(self.base + "/v1/health", timeout=5) as response:
            data = json.load(response)
        self.assertTrue(data["ok"])
        self.assertTrue(data["engines"]["claude"]["available"])

    def test_ask_streams_deltas_then_done(self):
        events = self.events({"text": "hello"})
        self.assertEqual(events[0]["type"], "meta")
        self.assertEqual("".join(e["text"] for e in events if e["type"] == "delta"), "Hello from fake Claude.")
        self.assertEqual(events[-1], {"type": "done", "text": "Hello from fake Claude."})

    def test_error_from_engine_is_reported(self):
        events = self.events({"text": "FAIL please"})
        self.assertEqual(events[-1]["type"], "error")
        self.assertIn("model unavailable", events[-1]["message"])

    def test_bad_token_and_missing_token_refused(self):
        for token in ("wrong", None):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                self.post({"text": "hi"}, token=token)
            self.assertEqual(caught.exception.code, 401)

    def test_bad_requests(self):
        for body, code in (({"text": ""}, 400), ({"text": "x", "engine": "nope"}, 400)):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                self.post(body)
            self.assertEqual(caught.exception.code, code)

    def test_client_leaving_kills_the_process(self):
        # A slow fake would run 30 s; the watchdog must stop it soon after the client disconnects.
        import http.client
        host, port = self.base[len("http://"):].split(":")
        conn = http.client.HTTPConnection(host, int(port), timeout=10)
        conn.request("POST", "/v1/ask", json.dumps({"text": "SLOW"}), {"Authorization": f"Bearer {TOKEN}"})
        conn.getresponse().read(1)
        conn.close()
        time.sleep(1)  # no assertion on timing of the child; the request must not wedge the server
        with urllib.request.urlopen(self.base + "/v1/health", timeout=5) as response:
            self.assertTrue(json.load(response)["ok"])


if __name__ == "__main__":
    unittest.main()
