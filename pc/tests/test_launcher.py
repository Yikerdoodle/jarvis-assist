import json
import os
import pathlib
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from start_claude_pc.launcher import (LaunchError, RemoteLauncher, build_argv, is_remote_control,  # noqa: E402
                                    load_config, local_cards, parse_cards_json, parse_getmac)

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
            "python -m start_claude_pc remote-control",
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
            raise LaunchError("launcher.json has settings this program does not know: spwan")
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


class CardsTests(unittest.TestCase):
    ONE = '{"Name":"Ethernet","Adapter":"Realtek PCIe GbE","Mac":"A8-A1-59-12-34-56","Ip":"192.168.1.20","Prefix":24}'
    MANY = ('[{"Name":"Wi-Fi","Adapter":"Intel(R) Wi-Fi 6 AX200","Mac":"00-11-22-33-44-55","Ip":"192.168.1.31",'
            '"Prefix":24},' + ONE + ',{"Name":"Tailscale","Adapter":"Tailscale Tunnel","Mac":"00-FF-AA-BB-CC-DD",'
            '"Ip":"100.64.0.5","Prefix":32},{"Name":"Ethernet 2","Adapter":"Intel Ethernet","Mac":null,"Ip":null,'
            '"Prefix":null}]')

    def test_one_card_comes_as_an_object(self):
        self.assertEqual(parse_cards_json(self.ONE),
                         [{"name": "Ethernet", "adapter": "Realtek PCIe GbE", "mac": "A8:A1:59:12:34:56",
                           "ip": "192.168.1.20", "prefix": 24}])

    def test_several_cards_wired_first_virtual_and_broken_ones_dropped(self):
        cards = parse_cards_json(self.MANY)
        self.assertEqual([c["name"] for c in cards], ["Ethernet", "Wi-Fi"])

    def test_a_card_without_an_address_is_kept_without_one(self):
        cards = parse_cards_json('{"Name":"Ethernet","Adapter":"Realtek","Mac":"A8-A1-59-12-34-56","Ip":null,'
                                 '"Prefix":null}')
        self.assertEqual((cards[0]["mac"], cards[0]["ip"], cards[0]["prefix"]), ("A8:A1:59:12:34:56", None, None))

    def test_nothing_and_nonsense(self):
        self.assertEqual(parse_cards_json(""), [])
        self.assertEqual(parse_cards_json("   \n"), [])
        bad = '{"Name":"E","Adapter":"R","Mac":"A8-A1-59-12-34-56","Ip":"999 not an ip","Prefix":99}'
        card = parse_cards_json(bad)[0]
        self.assertEqual((card["ip"], card["prefix"]), (None, None))

    def test_local_cards_uses_powershell_first_and_getmac_as_the_fallback(self):
        class Done:
            def __init__(self, out):
                self.stdout = out

        calls = []

        def run(argv, **kwargs):
            calls.append(argv[0])
            if argv[0] == "powershell.exe":
                raise OSError("no powershell")
            return Done('"Ethernet","Realtek","A8-A1-59-12-34-56","\\Device\\Tcpip_{1}"\n')

        real = os.name
        os.name = "nt"  # pretend: local_cards only runs its commands on Windows
        try:
            cards = local_cards(run=run)
        finally:
            os.name = real
        self.assertEqual(calls, ["powershell.exe", "getmac"])
        self.assertEqual(cards[0]["mac"], "A8:A1:59:12:34:56")
        self.assertIsNone(cards[0]["ip"])

    def test_not_windows_means_no_cards(self):
        if os.name != "nt":
            self.assertEqual(local_cards(run=lambda *a, **k: 1 / 0), [])



if __name__ == "__main__":
    unittest.main()
