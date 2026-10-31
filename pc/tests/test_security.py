import json
import os
import sys
import tempfile
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from start_claude_pc.auth import (PeerCheck, SignatureChecker, auth_header, load_or_create_token,  # noqa: E402
                                  sign)
from start_claude_pc.claude import clean_env  # noqa: E402
from start_claude_pc.launcher import RemoteLauncher  # noqa: E402
from start_claude_pc.server import PairLimiter, serve  # noqa: E402

# The phone (Signer.java) must produce exactly these values: the same constants are in SignerTest.java.
VECTOR = dict(token="test-token", method="POST", path="/v1/remote/start", t=1730000000, n="00112233aabbccdd")
VECTOR_SIGNATURE = "23a2eea30d3f3030c4c972cba3728aa7d3323894b353f0eedbd2de68db9993bc"
VECTOR_HEADER = ("StartClaude t=1730000000,n=0011223344556677,"
                 "s=ba80930b0d2e919a3772651b719638757b6d61df1a17ad10afe568973c41e9fa")


class EnvTests(unittest.TestCase):
    def test_claude_and_api_settings_are_removed_but_normal_variables_stay(self):
        env = {"ANTHROPIC_API_KEY": "x", "ANTHROPIC_BASE_URL": "u", "CLAUDE_CODE_ENTRYPOINT": "e",
               "CLAUDE_AGENT_SDK_VERSION": "v", "CLAUDECODE": "1", "claude_code_session_id": "s",
               "PATH": "p", "USERPROFILE": "h", "CLAUDE_CONFIG_DIR": "keep-me"}
        self.assertEqual(clean_env(env), {"PATH": "p", "USERPROFILE": "h", "CLAUDE_CONFIG_DIR": "keep-me"})


class TokenTests(unittest.TestCase):
    def test_token_is_created_once_and_kept(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "token")
            first = load_or_create_token(path)
            self.assertGreaterEqual(len(first), 32)
            self.assertEqual(load_or_create_token(path), first)


class SignatureTests(unittest.TestCase):
    def test_matches_the_phone(self):
        self.assertEqual(sign(VECTOR["token"], VECTOR["method"], VECTOR["path"], VECTOR["t"], VECTOR["n"]),
                         VECTOR_SIGNATURE)
        self.assertEqual(auth_header("test-token", "GET", "/v1/health", 1730000000, "0011223344556677"),
                         VECTOR_HEADER)

    def checker(self, now=1730000000):
        return SignatureChecker(clock=lambda: now)

    def header(self, **overrides):
        args = dict(token="tok", method="POST", path="/v1/remote/start", timestamp=1730000000)
        args.update(overrides)
        return auth_header(**args)

    def test_a_good_request_is_accepted_once(self):
        checker = self.checker()
        header = self.header()
        self.assertTrue(checker.check(header, "tok", "POST", "/v1/remote/start"))
        self.assertFalse(checker.check(header, "tok", "POST", "/v1/remote/start"), "replay must be refused")

    def test_the_secret_itself_is_never_in_the_header(self):
        self.assertNotIn("tok", self.header().replace("StartClaude", ""))

    def test_wrong_token_method_or_path_is_refused(self):
        header = self.header()
        self.assertFalse(self.checker().check(header, "other", "POST", "/v1/remote/start"))
        self.assertFalse(self.checker().check(header, "tok", "GET", "/v1/remote/start"))
        self.assertFalse(self.checker().check(header, "tok", "POST", "/v1/health"))

    def test_old_and_future_requests_are_refused(self):
        header = self.header()
        self.assertTrue(self.checker(now=1730000059).check(header, "tok", "POST", "/v1/remote/start"))
        self.assertFalse(self.checker(now=1730000061).check(header, "tok", "POST", "/v1/remote/start"))
        self.assertFalse(self.checker(now=1729999939).check(header, "tok", "POST", "/v1/remote/start"))

    def test_garbage_is_refused(self):
        for value in (None, "", "Bearer tok", "StartClaude", "StartClaude t=1,n=2,s=3",
                      VECTOR_HEADER.replace("StartClaude", "Other")):
            self.assertFalse(self.checker().check(value, "tok", "POST", "/x"), value)

    def test_two_requests_in_the_same_second_both_work(self):
        checker = self.checker()
        self.assertTrue(checker.check(self.header(), "tok", "POST", "/v1/remote/start"))
        self.assertTrue(checker.check(self.header(), "tok", "POST", "/v1/remote/start"))


class PeerTests(unittest.TestCase):
    OWN = ["100.100.9.9"]

    def check(self, allowed_nodes=None, allow_lan=True, fail=False):
        def run(args):
            if fail:
                raise RuntimeError("down")
            if "status" in args:
                return {"Self": {"UserID": 1, "TailscaleIPs": self.OWN}}
            return {"Node": {"Name": "phone.tail1.ts.net.", "User": 1 if args[-1] == "100.100.2.2" else 2}}
        return PeerCheck(tailscale="ts", run_json=run, allowed_nodes=allowed_nodes, allow_lan=allow_lan)

    def test_each_kind_of_caller(self):
        check = self.check()
        self.assertEqual(check("127.0.0.1"), ("loopback", "loopback"))
        self.assertEqual(check("::1")[0], "loopback")
        self.assertEqual(check("100.100.2.2"), ("tailnet", "phone"))
        self.assertEqual(check("100.100.9.9"), ("loopback", "this computer"))
        for ip in ("192.168.1.20", "10.0.4.5", "172.16.0.9", "172.31.255.1"):
            self.assertEqual(check(ip)[0], "lan", ip)

    def test_other_users_and_the_internet_are_refused(self):
        check = self.check()
        self.assertIsNone(check("100.100.3.3")[0])          # a Tailscale device of someone else
        for ip in ("8.8.8.8", "172.32.0.1", "169.254.1.1", "100.128.0.1", "not an ip", "1.2.3"):
            self.assertIsNone(check(ip)[0], ip)

    def test_lan_can_be_switched_off(self):
        self.assertIsNone(self.check(allow_lan=False)("192.168.1.20")[0])
        self.assertEqual(self.check(allow_lan=False)("100.100.2.2")[0], "tailnet")

    def test_allowlist(self):
        self.assertIsNone(self.check(allowed_nodes={"laptop"})("100.100.2.2")[0])
        self.assertEqual(self.check(allowed_nodes={"phone"})("100.100.2.2")[0], "tailnet")

    def test_tailscale_failing_refuses_tailnet_callers_but_not_loopback(self):
        check = self.check(fail=True)
        self.assertIsNone(check("100.100.2.2")[0])
        self.assertEqual(check("127.0.0.1")[0], "loopback")

    def test_no_tailscale_installed(self):
        check = PeerCheck(tailscale=None)
        check.tailscale = None
        self.assertIsNone(check("100.100.2.2")[0])
        self.assertEqual(check("::1")[0], "loopback")


class PairLimiterTests(unittest.TestCase):
    def test_ten_a_minute(self):
        now = [0.0]
        limiter = PairLimiter(clock=lambda: now[0])
        self.assertEqual([limiter.allow() for _ in range(11)], [True] * 10 + [False])
        now[0] = 61.0
        self.assertTrue(limiter.allow())


class WhoMayDoWhatTests(unittest.TestCase):
    """The table at the top of server.py, checked against a real server for every kind of caller."""
    TOKEN = "tok-1234"
    CARDS = [{"name": "Ethernet", "adapter": "Realtek", "mac": "A8:A1:59:12:34:56", "ip": "192.168.1.20",
              "prefix": 24}]

    @classmethod
    def setUpClass(cls):
        cls.kind = ["tailnet"]
        cls.starts = []

        def popen(argv, **kwargs):
            cls.starts.append(argv)

            class Alive:
                pid = 1
                returncode = None

                def poll(self):
                    return None
            return Alive()

        cls.tmp = tempfile.TemporaryDirectory()
        launcher = RemoteLauncher(config=lambda: {"dir": __import__("pathlib").Path(cls.tmp.name), "name": None,
                                                   "spawn": None, "capacity": None, "permission_mode": None},
                                  find=lambda: "claude", processes=lambda: [], popen=popen, startup_check=0.05,
                                  log_path=os.path.join(cls.tmp.name, "rc.log"))

        def peers(ip):
            kind = cls.kind[0]
            return (kind, "test") if kind else (None, "stranger")
        cls.launcher = launcher
        cls.server = serve("127.0.0.1", 0, peers, cls.TOKEN, launcher, name="NK-PC-ASUS", cards=lambda: cls.CARDS)
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp.cleanup()

    def setUp(self):
        self.starts.clear()
        self.launcher._proc = None  # every case starts from "not running"

    def call(self, kind, method, path, signed=False, token=None):
        self.kind[0] = kind
        headers = {}
        if signed:
            headers["Authorization"] = auth_header(token or self.TOKEN, method, path)
        request = urllib.request.Request(self.base + path, data=b"" if method == "POST" else None,
                                         headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as err:
            return err.code, json.load(err)

    def test_health(self):
        status, body = self.call("tailnet", "GET", "/v1/health")
        self.assertEqual((status, body["name"], body["remote"]), (200, "NK-PC-ASUS", {"available": True}))
        self.assertEqual(self.call("loopback", "GET", "/v1/health")[0], 200)  # so a browser on the PC can check
        self.assertEqual(self.call("lan", "GET", "/v1/health")[0], 401)
        self.assertEqual(self.call("lan", "GET", "/v1/health", signed=True)[0], 200)
        self.assertEqual(self.call("lan", "GET", "/v1/health", signed=True, token="wrong")[0], 401)
        self.assertEqual(self.call(None, "GET", "/v1/health", signed=True)[0], 403)

    def test_start_needs_no_secret_from_your_own_tailscale_device(self):
        status, body = self.call("tailnet", "POST", "/v1/remote/start")
        self.assertEqual((status, body["state"], body["name"]), (200, "started", "NK-PC-ASUS"))
        self.assertEqual(len(self.starts), 1)

    def test_start_needs_a_signature_from_the_pc_itself_and_from_the_home_network(self):
        for kind in ("loopback", "lan"):
            self.assertEqual(self.call(kind, "POST", "/v1/remote/start")[0], 401, kind)
            self.assertEqual(self.call(kind, "POST", "/v1/remote/start", signed=True, token="wrong")[0], 401, kind)
            self.assertEqual(self.starts, [], kind)
        self.assertEqual(self.call("lan", "POST", "/v1/remote/start", signed=True)[0], 200)
        self.assertEqual(len(self.starts), 1)

    def test_start_is_refused_for_everyone_else(self):
        self.assertEqual(self.call(None, "POST", "/v1/remote/start", signed=True)[0], 403)
        self.assertEqual(self.starts, [])

    def test_a_signed_request_cannot_be_replayed_on_the_wire(self):
        self.kind[0] = "lan"
        header = auth_header(self.TOKEN, "POST", "/v1/remote/start")
        results = []
        for _ in range(2):
            request = urllib.request.Request(self.base + "/v1/remote/start", data=b"", method="POST",
                                             headers={"Authorization": header})
            try:
                with urllib.request.urlopen(request, timeout=10) as response:
                    results.append(response.status)
            except urllib.error.HTTPError as err:
                results.append(err.code)
        self.assertEqual(results, [200, 401])

    def test_pairing_only_over_tailscale_and_it_hands_over_everything(self):
        status, body = self.call("tailnet", "POST", "/v1/pair")
        self.assertEqual(status, 200)
        self.assertEqual((body["name"], body["token"], body["cards"]), ("NK-PC-ASUS", self.TOKEN, self.CARDS))
        self.assertEqual(body["port"], self.server.server_address[1])
        for kind in ("loopback", "lan"):
            status, body = self.call(kind, "POST", "/v1/pair", signed=True)
            self.assertEqual(status, 403, kind)
            self.assertNotIn("token", body)
        self.assertEqual(self.call(None, "POST", "/v1/pair")[0], 403)

    def test_the_request_cannot_choose_what_runs(self):
        self.kind[0] = "tailnet"
        request = urllib.request.Request(
            self.base + "/v1/remote/start", method="POST",
            data=json.dumps({"dir": "C:\\Windows", "argv": ["calc.exe"], "spawn": "worktree"}).encode())
        urllib.request.urlopen(request, timeout=10).close()
        self.assertEqual(self.starts, [["claude", "remote-control"]])

    def test_unknown_paths_are_404(self):
        self.assertEqual(self.call("tailnet", "GET", "/nope")[0], 404)
        self.assertEqual(self.call("tailnet", "POST", "/nope")[0], 404)


if __name__ == "__main__":
    unittest.main()
