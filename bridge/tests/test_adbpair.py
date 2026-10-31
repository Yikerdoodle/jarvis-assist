import json
import os
import sys
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from jarvis_bridge.adbpair import AdbPairer, acceptable_host, parse_mdns  # noqa: E402
from jarvis_bridge.engines import ClaudeCliEngine  # noqa: E402
from jarvis_bridge.server import serve  # noqa: E402

FAKE_ADB = [sys.executable, os.path.join(os.path.dirname(__file__), "fake_adb.py")]
FAKE_CLAUDE = [sys.executable, os.path.join(os.path.dirname(__file__), "fake_claude.py")]


def pairer():
    return AdbPairer(adb_command=FAKE_ADB, connect_wait=0)


class MdnsTests(unittest.TestCase):
    def test_parses_pairing_and_connect_services(self):
        out = ("List of discovered mdns services\n"
               "adb-X-abc\t_adb-tls-pairing._tcp\t192.168.1.101:46081\n"
               "adb-X-abc\t_adb-tls-connect._tcp\t192.168.1.101:44867\n"
               "garbage line\n")
        pairing, connect = parse_mdns(out)
        self.assertEqual(pairing, [("192.168.1.101", 46081)])
        self.assertEqual(connect, [("192.168.1.101", 44867)])

    def test_acceptable_hosts(self):
        for good in ("192.168.1.5", "10.0.0.2", "100.100.100.100", "172.16.4.4"):
            self.assertTrue(acceptable_host(good), good)
        for bad in ("127.0.0.1", "8.8.8.8", "100.128.0.1", "example.com", "::1", "192.168.1.5; rm -rf", ""):
            self.assertFalse(acceptable_host(bad), bad)


class PairTests(unittest.TestCase):
    def test_pairs_using_the_matching_lan_service_first(self):
        result = pairer().pair("123456", 33139, "100.64.0.9")
        self.assertTrue(result["paired"])
        self.assertTrue(result["connected"])
        self.assertEqual(result["message"], "paired and connected")

    def test_falls_back_to_the_peer_address_when_the_lan_does_not_match(self):
        result = pairer().pair("123456", 40000, "100.64.0.9")  # fake mDNS only advertises port 33139
        self.assertTrue(result["paired"])

    def test_wrong_code_is_explained_without_echoing_it(self):
        result = pairer().pair("654321", 33139, "100.64.0.9")
        self.assertFalse(result["paired"])
        self.assertIn("not accepted", result["message"])
        self.assertNotIn("654321", result["message"])

    def test_protocol_fault_reads_as_a_closed_dialog(self):
        result = pairer().pair("000111", 33139, "100.64.0.9")
        self.assertFalse(result["paired"])
        self.assertIn("pairing dialog may have closed", result["message"])
        self.assertNotIn("000111", result["message"])

    def test_no_address_to_try(self):
        result = pairer().pair("123456", 40000, "127.0.0.1")
        self.assertFalse(result["paired"])
        self.assertIn("no address", result["message"])

    def test_rejects_bad_input(self):
        for code, port in (("12345", 33139), ("abcdef", 33139), (123456, 33139), ("123456", 80),
                           ("123456", "33139"), ("123456", True), ("123456", 70000)):
            with self.assertRaises(ValueError, msg=repr((code, port))):
                pairer().pair(code, port, "100.64.0.9")

    def test_adb_missing(self):
        missing = AdbPairer(adb_command=None)
        missing._adb = lambda: None  # no adb anywhere
        self.assertIn("adb was not found", missing.pair("123456", 33139, "100.64.0.9")["message"])

    def test_rate_limit(self):
        p = pairer()
        self.assertFalse(any(p.rate_limited() for _ in range(6)))
        self.assertTrue(p.rate_limited())


class EndpointTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        engines = {"claude": ClaudeCliEngine(command=FAKE_CLAUDE)}
        cls.server = serve("127.0.0.1", 0, engines, lambda ip: (True, "test"), "tok", pairer())
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def post(self, body):
        request = urllib.request.Request(self.base + "/v1/adb/pair", json.dumps(body).encode(),
                                         {"Content-Type": "application/json"})  # note: no token needed
        return urllib.request.urlopen(request, timeout=30)

    def test_health_reports_adb(self):
        with urllib.request.urlopen(self.base + "/v1/health", timeout=5) as response:
            self.assertTrue(json.load(response)["adb"]["available"])

    def test_pair_ok(self):
        with self.post({"code": "123456", "port": 33139}) as response:
            data = json.load(response)
        self.assertEqual(response.status, 200)
        self.assertTrue(data["paired"])

    def test_wrong_code_is_a_clean_200_with_paired_false(self):
        with self.post({"code": "654321", "port": 33139}) as response:
            data = json.load(response)
        self.assertFalse(data["paired"])
        self.assertNotIn("654321", json.dumps(data))

    def test_bad_input_is_400(self):
        for body in ({"code": "12", "port": 33139}, {"port": 33139}, {"code": "123456"}, ["not", "a", "dict"]):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                self.post(body)
            self.assertEqual(caught.exception.code, 400, body)

    def test_ask_still_needs_its_token(self):
        request = urllib.request.Request(self.base + "/v1/ask", json.dumps({"text": "hi"}).encode(),
                                         {"Content-Type": "application/json"})
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=5)
        self.assertEqual(caught.exception.code, 401)


if __name__ == "__main__":
    unittest.main()
