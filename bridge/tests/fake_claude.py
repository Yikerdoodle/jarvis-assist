"""Stands in for `claude -p ... --output-format stream-json` so tests need no login and no network."""
import json
import sys

prompt = sys.stdin.read()
mode = "ok"
if "FAIL" in prompt:
    mode = "fail"
elif "SLOW" in prompt:
    mode = "slow"


def emit(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


emit({"type": "system", "subtype": "init"})
if mode == "fail":
    emit({"type": "result", "subtype": "error", "is_error": True, "result": "model unavailable"})
    sys.exit(1)
if mode == "slow":
    import time
    time.sleep(30)
for piece in ("Hello ", "from ", "fake Claude."):
    emit({"type": "stream_event", "event": {"type": "content_block_delta", "index": 0,
                                            "delta": {"type": "text_delta", "text": piece}}})
emit({"type": "assistant", "message": {"content": [{"type": "text", "text": "Hello from fake Claude."}]}})
emit({"type": "result", "subtype": "success", "is_error": False, "result": "Hello from fake Claude.", "duration_ms": 5})
