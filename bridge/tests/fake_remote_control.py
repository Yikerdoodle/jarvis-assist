"""Stands in for `claude remote-control` so tests need no login and no network.

FAKE_RC_MODE=crash makes it fail at once, the way the real command does in a folder it has not been
allowed to trust yet. Otherwise it just stays alive, like the real server.
"""
import os
import sys
import time

mode = os.environ.get("FAKE_RC_MODE", "ok")
print("\x1b[1mRemote Control\x1b[0m starting; args:", " ".join(sys.argv[1:]), flush=True)
if mode == "crash":
    print("\x1b[31mError:\x1b[0m this folder is not trusted yet.", file=sys.stderr, flush=True)
    sys.exit(1)
time.sleep(60)
