"""Lets the phone hand its Wireless-debugging pairing code to this PC, which then runs `adb pair` itself.

The phone sends only the six digits and the pairing port. The PC works out the address on its own: the
phone's own tailnet address (the request came from it) and any matching pairing service found on the
local network. The code is never logged and never echoed back.
"""

import ipaddress
import os
import re
import shutil
import subprocess
import threading
import time
from collections import deque

CODE_RE = re.compile(r"^\d{6}$")
PAIR_TIMEOUT = 15
MAX_REQUESTS = 6
WINDOW_SECONDS = 60
TAILNET = ipaddress.ip_network("100.64.0.0/10")


def find_adb():
    override = os.environ.get("JARVIS_ADB")
    if override and os.path.exists(override):
        return override
    found = shutil.which("adb")
    if found:
        return found
    default = r"C:\platform-tools\adb.exe"
    return default if os.path.exists(default) else None


def parse_mdns(output):
    """`adb mdns services` lines look like: <name> <_adb-tls-pairing._tcp|_adb-tls-connect._tcp> <ip>:<port>."""
    pairing, connect = [], []
    for line in output.splitlines():
        parts = line.split()
        if len(parts) < 3 or ":" not in parts[-1]:
            continue
        host, _, port = parts[-1].rpartition(":")
        if not port.isdigit():
            continue
        if parts[-2] == "_adb-tls-pairing._tcp":
            pairing.append((host, int(port)))
        elif parts[-2] == "_adb-tls-connect._tcp":
            connect.append((host, int(port)))
    return pairing, connect


def acceptable_host(host):
    """Only home-network or tailnet IPv4 addresses; never loopback, public or hostile-looking input."""
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    return ip.version == 4 and not ip.is_loopback and (ip.is_private or ip in TAILNET)


def validate_request(code, port):
    if not isinstance(code, str) or not CODE_RE.match(code):
        raise ValueError("the pairing code must be six digits")
    if not isinstance(port, int) or isinstance(port, bool) or not 1024 <= port <= 65535:
        raise ValueError("the port must be a number between 1024 and 65535")


def _result(paired, connected, message):
    return {"ok": paired, "paired": paired, "connected": connected, "message": message}


class AdbPairer:
    def __init__(self, adb_command=None, connect_wait=8.0):
        self.adb_command = adb_command  # tests substitute a fake command here
        self.connect_wait = connect_wait
        self._lock = threading.Lock()
        self._recent = deque()

    def _adb(self):
        if self.adb_command:
            return list(self.adb_command)
        found = find_adb()
        return [found] if found else None

    def status(self):
        return {"available": bool(self._adb())}

    def _run(self, args, timeout):
        done = subprocess.run(self._adb() + args, capture_output=True, text=True, timeout=timeout,
                              creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return (done.stdout or "") + (done.stderr or "")

    def rate_limited(self):
        now = time.monotonic()
        while self._recent and now - self._recent[0] > WINDOW_SECONDS:
            self._recent.popleft()
        if len(self._recent) >= MAX_REQUESTS:
            return True
        self._recent.append(now)
        return False

    def _mdns(self):
        try:
            return parse_mdns(self._run(["mdns", "services"], 8))
        except (subprocess.SubprocessError, OSError):
            return [], []

    def _connected(self):
        try:
            out = self._run(["devices"], 8)
        except (subprocess.SubprocessError, OSError):
            return False
        return any(line.rstrip().endswith("\tdevice") for line in out.splitlines())

    def pair(self, code, port, peer_ip):
        validate_request(code, port)
        if not self._adb():
            return _result(False, False, "adb was not found on the PC")

        with self._lock:
            pairing, _ = self._mdns()
            candidates = [h for h, p in pairing if p == port and acceptable_host(h)]  # same network: fastest
            if peer_ip and acceptable_host(peer_ip) and peer_ip not in candidates:
                candidates.append(peer_ip)  # the phone's own tailnet address
            if not candidates:
                return _result(False, False, "the PC found no address to pair with")

            paired_host, last = None, "pairing failed"
            for host in candidates:
                try:
                    out = self._run(["pair", f"{host}:{port}", code], PAIR_TIMEOUT)
                except subprocess.TimeoutExpired:
                    last = f"timed out pairing with {host}"
                    continue
                except OSError as exc:
                    return _result(False, False, f"could not run adb: {exc}")
                if "Successfully paired" in out:
                    paired_host = host
                    break
                last = _explain(out, code)
            if paired_host is None:
                return _result(False, False, last)

            connected = self._connect_after_pairing(paired_host)
        return _result(True, connected, "paired and connected" if connected else "paired, not connected yet")

    def _connect_after_pairing(self, paired_host):
        deadline = time.monotonic() + self.connect_wait
        tried = False
        while True:
            if self._connected():
                return True
            if not tried:
                tried = True
                _, connect = self._mdns()
                for host, port in connect:
                    if host == paired_host:
                        try:
                            self._run(["connect", f"{host}:{port}"], 10)
                        except (subprocess.SubprocessError, OSError):
                            pass
            if time.monotonic() >= deadline:
                return False
            time.sleep(1)


def _explain(output, code):
    text = output.replace(code, "******").strip()
    lowered = text.lower()
    if "wrong password" in lowered or "protocol fault" in lowered or "connection was dropped" in lowered:
        return "the code was not accepted (the pairing dialog may have closed, or the code was already used)"
    first = text.splitlines()[0] if text else "no output"
    return "adb said: " + first[:160]
