import json
import os
import sys
import tempfile
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from start_claude_pc.auth import TailnetPeerCheck, load_or_create_token, token_ok  # noqa: E402
from start_claude_pc.claude import clean_env  # noqa: E402
from start_claude_pc.launcher import RemoteLauncher  # noqa: E402
from start_claude_pc.server import serve  # noqa: E402


class EnvTests(unittest.TestCase):
    def test_claude_and_api_settings_are_removed_but_normal_variables_stay(self):
        env = {"ANTHROPIC_API_KEY": "x", "ANTHROPIC_BASE_URL": "u", "CLAUDE_CODE_ENTRYPOINT": "e",
               "CLAUDE_AGENT_SDK_VERSION": "v", "CLAUDECODE": "1", "claude_code_session_id": "s",
               "PATH": "p", "USERPROFILE": "h", "CLAUDE_CONFIG_DIR": "keep-me"}
        self.assertEqual(clean_env(env), {"PATH": "p", "USERPROFILE": "h", "CLAUDE_CONFIG_DIR": "keep-me"})


class TokenTests(unittest.TestCase):
    def test_header_check(self):
        self.assertTrue(token_ok("Bearer abc", "abc"))
        self.assertFalse(token_ok("Bearer abd", "abc"))
        self.assertFalse(token_ok("abc", "abc"))
        self.assertFalse(token_ok(None, "abc"))

    def test_token_is_created_once_and_kept(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "token")
            first = load_or_create_token(path)
            self.assertGreaterEqual(len(first), 32)
            self.assertEqual(load_or_create_token(path), first)


class PeerTests(unittest.TestCase):
    def test_same_user_allowed_other_user_refused(self):
        def run(args):
            if "status" in args:
                return {"Self": {"UserID": 1, "TailscaleIPs": ["100.1.1.1"]}}
            return {"Node": {"Name": "phone.tail1.ts.net.", "User": 1 if args[-1] == "100.2.2.2" else 2}}
        check = TailnetPeerCheck(tailscale="ts", run_json=run)
        self.assertEqual(check("100.2.2.2"), (True, "phone"))
        self.assertFalse(check("100.3.3.3")[0])
        self.assertTrue(check("127.0.0.1")[0])

    def test_allowlist(self):
        def run(args):
            if "status" in args:
                return {"Self": {"UserID": 1, "TailscaleIPs": []}}
            return {"Node": {"Name": "laptop.tail1.ts.net.", "User": 1}}
        self.assertFalse(TailnetPeerCheck(tailscale="ts", run_json=run, allowed_nodes={"phone"})("100.2.2.2")[0])

    def test_fails_closed(self):
        def run(args):
            raise RuntimeError("down")
        self.assertFalse(TailnetPeerCheck(tailscale="ts", run_json=run)("100.2.2.2")[0])

    def test_no_tailscale_means_only_loopback(self):
        check = TailnetPeerCheck(tailscale=None)
        check.tailscale = None
        self.assertFalse(check("100.2.2.2")[0])
        self.assertTrue(check("::1")[0])


class RefusedDeviceTests(unittest.TestCase):
    """A device that is not on the owner's tailnet gets nothing, even with the right token."""

    @classmethod
    def setUpClass(cls):
        cls.calls = []

        def popen(argv, **kwargs):
            cls.calls.append(argv)
            raise AssertionError("must not start anything")

        launcher = RemoteLauncher(find=lambda: "claude", processes=lambda: [], popen=popen)
        cls.server = serve("127.0.0.1", 0, lambda ip: (False, "stranger"), "tok", launcher)
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def test_every_endpoint_says_403(self):
        for method, path in (("GET", "/v1/health"), ("GET", "/v1/wake-info"), ("POST", "/v1/remote/start")):
            request = urllib.request.Request(self.base + path, data=b"" if method == "POST" else None,
                                             headers={"Authorization": "Bearer tok"}, method=method)
            with self.assertRaises(urllib.error.HTTPError, msg=path) as caught:
                urllib.request.urlopen(request, timeout=5)
            self.assertEqual(caught.exception.code, 403, path)
            self.assertEqual(json.load(caught.exception), {"error": "device not allowed"})
        self.assertEqual(self.calls, [])

    def test_unknown_paths_are_404(self):
        for method in ("GET", "POST"):
            request = urllib.request.Request(self.base + "/nope", data=b"" if method == "POST" else None,
                                             method=method)
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(request, timeout=5)
            self.assertEqual(caught.exception.code, 404)


if __name__ == "__main__":
    unittest.main()
