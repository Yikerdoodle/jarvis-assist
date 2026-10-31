import json
import os
import pathlib
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from jarvis_bridge.engines import ClaudeCliEngine  # noqa: E402
from jarvis_bridge.launcher import (LaunchError, RemoteLauncher, build_argv, is_remote_control,  # noqa: E402
                                    load_config, parse_getmac)
from jarvis_bridge.server import serve  # noqa: E402

FAKE_RC = os.path.join(os.path.dirname(__file__), "fake_remote_control.py")
TOKEN = "test-token-123"


class FakeProc:
    """Looks enough like subprocess.Popen for the launcher: alive until told otherwise."""

    def __init__(self, alive=True):
        self.pid = 4242
        self.returncode = None if alive else 1

    def poll(self):
        return self.returncode


def config_for(folder, **extra):
    base = {"dir": pathlib.Path(folder), "name": None, "spawn": None, "capacity": None, "permission_mode": None}
    base.update(extra)
    return lambda: base


class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = pathlib.Path(self.tmp.name) / "launcher.json"

    def write(self, text, encoding="utf-8"):
        self.path.write_bytes(text.encode(encoding))

    def test_no_file_means_defaults_in_the_home_folder(self):
        cfg = load_config(self.path)
        self.assertEqual(cfg["dir"], pathlib.Path.home())
        self.assertIsNone(cfg["spawn"])

    def test_valid_file(self):
        self.write(json.dumps({"dir": self.tmp.name, "name": " My PC ", "spawn": "worktree", "capacity": 4,
                               "permission_mode": "plan"}))
        cfg = load_config(self.path)
        self.assertEqual((cfg["dir"], cfg["name"], cfg["spawn"], cfg["capacity"], cfg["permission_mode"]),
                         (pathlib.Path(self.tmp.name), "My PC", "worktree", 4, "plan"))

    def test_notepad_byte_order_mark_is_fine(self):
        self.write(json.dumps({"dir": self.tmp.name}), encoding="utf-8-sig")
        self.assertEqual(load_config(self.path)["dir"], pathlib.Path(self.tmp.name))

    def test_mistakes_are_explained(self):
        bad = [
            ("{not json", "cannot be read"),
            ("[1, 2]", "must look like"),
            (json.dumps({"dir": self.tmp.name, "spwan": "x"}), "spwan"),
            (json.dumps({"dir": self.tmp.name + "/missing"}), "does not exist"),
            (json.dumps({"dir": self.tmp.name, "spawn": "session"}), "spawn"),
            (json.dumps({"dir": self.tmp.name, "capacity": 0}), "capacity"),
            (json.dumps({"dir": self.tmp.name, "capacity": True}), "capacity"),
            (json.dumps({"dir": self.tmp.name, "permission_mode": "bypassPermissions"}), "permission_mode"),
            (json.dumps({"dir": self.tmp.name, "name": "x" * 61}), "name"),
        ]
        for text, expected in bad:
            self.write(text)
            with self.assertRaises(LaunchError, msg=text) as caught:
                load_config(self.path)
            self.assertIn(expected, str(caught.exception))

    def test_argv_only_carries_what_was_configured(self):
        plain = {"name": None, "spawn": None, "capacity": None, "permission_mode": None}
        self.assertEqual(build_argv(["claude"], plain), ["claude", "remote-control"])
        full = {"name": "Desk", "spawn": "worktree", "capacity": 3, "permission_mode": "plan"}
        self.assertEqual(build_argv(["claude"], full),
                         ["claude", "remote-control", "--name", "Desk", "--spawn", "worktree", "--capacity", "3",
                          "--permission-mode", "plan"])


class RecogniserTests(unittest.TestCase):
    def test_real_remote_control_commands(self):
        for command in (
            r"C:\Users\me\AppData\Roaming\Claude\claude-code\2.1.309\abc\claude.exe remote-control",
            r'"C:\Program Files\Claude\claude.exe" remote-control --spawn worktree --name "My PC"',
            "/usr/local/bin/claude remote-control --capacity 4",
            r"node C:\Users\me\AppData\Roaming\npm\node_modules\@anthropic-ai\claude-code\cli.js remote-control",
        ):
            self.assertTrue(is_remote_control(command), command)

    def test_lookalikes_do_not_count(self):
        for command in (
            "",
            "claude",
            '"claude" -p "what is remote-control"',               # the word is inside one quoted argument
            "claude --resume",
            "powershell.exe -Command Get-CimInstance Win32_Process -Filter \"CommandLine LIKE '%remote-control%'\"",
            "python -m jarvis_bridge remote-control",
            "node server.js remote-control",                       # node, but not Claude's script
            "vim remote-control.txt",
        ):
            self.assertFalse(is_remote_control(command), command)


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.calls = []

    def popen(self, alive=True):
        def fake(argv, **kwargs):
            self.calls.append((argv, kwargs))
            return FakeProc(alive=alive)
        return fake

    def launcher(self, **overrides):
        options = dict(config=config_for(self.tmp.name), find=lambda: "claude", processes=lambda: [],
                       popen=self.popen(), startup_check=0.2, log_path=os.path.join(self.tmp.name, "rc.log"))
        options.update(overrides)
        return RemoteLauncher(**options)

    def test_starts_once_then_reports_already_running(self):
        launcher = self.launcher()
        first = launcher.start()
        self.assertEqual(first["state"], "started")
        self.assertEqual(first["folder"], pathlib.Path(self.tmp.name).name)
        self.assertEqual(launcher.start()["state"], "already-running")
        self.assertEqual(len(self.calls), 1)

    def test_start_is_hidden_runs_in_the_chosen_folder_and_has_no_stdin(self):
        self.launcher(config=config_for(self.tmp.name, spawn="worktree")).start()
        argv, kwargs = self.calls[0]
        self.assertEqual(argv, ["claude", "remote-control", "--spawn", "worktree"])
        self.assertEqual(kwargs["cwd"], self.tmp.name)
        self.assertEqual(kwargs["stdin"], __import__("subprocess").DEVNULL)

    def test_claude_api_and_session_variables_do_not_reach_the_child(self):
        os.environ["ANTHROPIC_API_KEY"] = "secret"
        os.environ["CLAUDE_CODE_SESSION_X"] = "other"
        self.addCleanup(os.environ.pop, "ANTHROPIC_API_KEY", None)
        self.addCleanup(os.environ.pop, "CLAUDE_CODE_SESSION_X", None)
        self.launcher().start()
        env = self.calls[0][1]["env"]
        self.assertNotIn("ANTHROPIC_API_KEY", env)
        self.assertNotIn("CLAUDE_CODE_SESSION_X", env)

    def test_one_started_by_hand_is_not_started_again(self):
        launcher = self.launcher(processes=lambda: [(77, "/usr/bin/claude remote-control")])
        self.assertEqual(launcher.start()["state"], "already-running")
        self.assertEqual(self.calls, [])

    def test_a_broken_process_lookup_still_starts_it(self):
        def broken():
            raise RuntimeError("powershell failed")
        self.assertEqual(self.launcher(processes=broken).start()["state"], "started")

    def test_two_quick_taps_start_only_one(self):
        launcher = self.launcher()
        results = []
        threads = [threading.Thread(target=lambda: results.append(launcher.start()["state"])) for _ in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(sorted(results), ["already-running"] * 4 + ["started"])
        self.assertEqual(len(self.calls), 1)

    def test_missing_claude_command(self):
        with self.assertRaises(LaunchError) as caught:
            self.launcher(find=lambda: None).start()
        self.assertEqual(caught.exception.status, 503)
        self.assertEqual(self.calls, [])

    def test_bad_settings_stop_the_start(self):
        def broken():
            raise LaunchError("launcher.json has settings the bridge does not know: spwan")
        with self.assertRaises(LaunchError):
            self.launcher(config=broken).start()
        self.assertEqual(self.calls, [])

    def test_cannot_spawn(self):
        def fail(argv, **kwargs):
            raise FileNotFoundError("no such file")
        with self.assertRaises(LaunchError) as caught:
            self.launcher(popen=fail).start()
        self.assertEqual(caught.exception.status, 502)

    def test_a_real_process_that_crashes_reports_what_it_said(self):
        os.environ["FAKE_RC_MODE"] = "crash"
        self.addCleanup(os.environ.pop, "FAKE_RC_MODE", None)
        launcher = RemoteLauncher(config=config_for(self.tmp.name), prefix=[sys.executable, FAKE_RC],
                                  processes=lambda: [], startup_check=5.0,
                                  log_path=os.path.join(self.tmp.name, "rc.log"))
        with self.assertRaises(LaunchError) as caught:
            launcher.start()
        message = str(caught.exception)
        self.assertEqual(caught.exception.status, 502)
        self.assertIn("this folder is not trusted yet", message)
        self.assertNotIn("\x1b", message)  # colour codes are stripped
        self.assertIn("run `claude` once", message)

    def test_a_crash_report_does_not_include_older_runs(self):
        log = pathlib.Path(self.tmp.name) / "rc.log"
        log.write_bytes(b"OLD RUN SAID SOMETHING ELSE\n")
        os.environ["FAKE_RC_MODE"] = "crash"
        self.addCleanup(os.environ.pop, "FAKE_RC_MODE", None)
        launcher = RemoteLauncher(config=config_for(self.tmp.name), prefix=[sys.executable, FAKE_RC],
                                  processes=lambda: [], startup_check=5.0, log_path=log)
        with self.assertRaises(LaunchError) as caught:
            launcher.start()
        self.assertNotIn("OLD RUN", str(caught.exception))

    def test_a_real_process_that_stays_up_counts_as_started(self):
        launcher = RemoteLauncher(config=config_for(self.tmp.name), prefix=[sys.executable, FAKE_RC],
                                  processes=lambda: [], startup_check=0.5,
                                  log_path=os.path.join(self.tmp.name, "rc.log"))
        try:
            self.assertEqual(launcher.start()["state"], "started")
            self.assertEqual(launcher.start()["state"], "already-running")
        finally:
            launcher._proc.kill()
            launcher._proc.wait()

    def test_the_log_is_started_afresh_when_it_gets_big(self):
        log = pathlib.Path(self.tmp.name) / "rc.log"
        log.write_bytes(b"x" * 1_100_000)
        self.launcher(log_path=log).start()
        self.assertLess(log.stat().st_size, 1000)


class GetmacTests(unittest.TestCase):
    SAMPLE = (
        '"Ethernet","Realtek PCIe GbE Family Controller","A8-A1-59-12-34-56","\\Device\\Tcpip_{1111}"\n'
        '"Wi-Fi","Intel(R) Wi-Fi 6 AX200 160MHz","00-11-22-33-44-55","\\Device\\Tcpip_{2222}"\n'
        '"Tailscale","Tailscale Tunnel","00-FF-AA-BB-CC-DD","\\Device\\Tcpip_{3333}"\n'
        '"vEthernet (WSL)","Hyper-V Virtual Ethernet Adapter","00-15-5D-01-02-03","\\Device\\Tcpip_{4444}"\n'
        '"Ethernet 2","Intel(R) Ethernet Connection","B8-AA-CC-DD-EE-FF","Media disconnected"\n'
        '"Bluetooth Network Connection","Bluetooth Device (PAN)","AA-BB-CC-DD-EE-01","N/A"\n'
        'garbage line\n'
    )

    def test_connected_physical_cards_only_wired_first(self):
        cards = parse_getmac(self.SAMPLE)
        self.assertEqual([c["mac"] for c in cards], ["A8:A1:59:12:34:56", "00:11:22:33:44:55"])
        self.assertEqual(cards[0]["name"], "Ethernet")

    def test_empty_output(self):
        self.assertEqual(parse_getmac(""), [])


class EndpointTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.calls = []

        def popen(argv, **kwargs):
            cls.calls.append(argv)
            return FakeProc()

        cls.launcher = RemoteLauncher(config=config_for(cls.tmp.name), find=lambda: "claude",
                                      processes=lambda: [], popen=popen, startup_check=0.1,
                                      log_path=os.path.join(cls.tmp.name, "rc.log"))
        engines = {"claude": ClaudeCliEngine(command=[sys.executable, "-c", "pass"])}
        cls.server = serve("127.0.0.1", 0, engines, lambda ip: (True, "test"), TOKEN, launcher=cls.launcher)
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp.cleanup()

    def call(self, path, method="POST", token=TOKEN):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        request = urllib.request.Request(self.base + path, data=b"" if method == "POST" else None,
                                         headers=headers, method=method)
        return urllib.request.urlopen(request, timeout=10)

    def test_start_needs_the_token(self):
        for token in (None, "wrong"):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                self.call("/v1/remote/start", token=token)
            self.assertEqual(caught.exception.code, 401)
        self.assertEqual(self.calls, [])

    def test_start_then_start_again(self):
        with self.call("/v1/remote/start") as response:
            first = json.load(response)
        with self.call("/v1/remote/start") as response:
            second = json.load(response)
        self.assertEqual((first["ok"], first["state"]), (True, "started"))
        self.assertEqual(second["state"], "already-running")
        self.assertEqual(len(self.calls), 1)

    def test_the_request_cannot_choose_what_runs(self):
        # A hostile body is ignored: the command line comes only from launcher.json on the PC.
        request = urllib.request.Request(
            self.base + "/v1/remote/start", headers={"Authorization": f"Bearer {TOKEN}"}, method="POST",
            data=json.dumps({"dir": "C:\\Windows", "argv": ["calc.exe"], "spawn": "worktree"}).encode())
        urllib.request.urlopen(request, timeout=10).close()
        self.assertTrue(all(argv == ["claude", "remote-control"] for argv in self.calls))

    def test_health_says_whether_claude_can_be_started(self):
        with self.call("/v1/health", method="GET", token=None) as response:
            self.assertEqual(json.load(response)["remote"], {"available": True})

    def test_wake_info_needs_the_token_and_returns_a_list(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.call("/v1/wake-info", method="GET", token=None)
        self.assertEqual(caught.exception.code, 401)
        with self.call("/v1/wake-info", method="GET") as response:
            self.assertIsInstance(json.load(response)["macs"], list)

    def test_a_failed_start_is_reported_with_its_reason(self):
        failing = RemoteLauncher(config=config_for(self.tmp.name), find=lambda: None)
        server = serve("127.0.0.1", 0, {}, lambda ip: (True, "test"), TOKEN, launcher=failing)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        request = urllib.request.Request(f"http://127.0.0.1:{server.server_address[1]}/v1/remote/start",
                                         data=b"", headers={"Authorization": f"Bearer {TOKEN}"}, method="POST")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=10)
        self.assertEqual(caught.exception.code, 503)
        self.assertIn("not found", json.load(caught.exception)["error"])


if __name__ == "__main__":
    unittest.main()
