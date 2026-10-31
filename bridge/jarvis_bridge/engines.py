"""Engines the bridge can answer with. Each one streams events: meta, delta, done, error."""

import glob
import json
import os
import re
import shutil
import subprocess
import threading
import time

from .auth import app_dir
from .prompting import SYSTEM_PROMPT, build_prompt

ALLOWED_CLAUDE_MODELS = {"haiku", "sonnet", "opus", "fable"}
# Never let a stray key make the CLI bill the API instead of using the subscription login.
STRIPPED_ENV = ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_USE_BEDROCK",
                "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")
TIMEOUT_SECONDS = 180


def find_claude():
    override = os.environ.get("JARVIS_CLAUDE_BIN")
    if override and os.path.exists(override):
        return override
    found = shutil.which("claude")
    if found:
        return found
    candidates = []
    roaming = os.environ.get("APPDATA")
    if roaming:
        candidates += glob.glob(os.path.join(roaming, "Claude", "claude-code", "*", "*", "claude.exe"))
    # The Claude desktop app is a packaged app: Windows keeps its "Roaming" files in a private folder that a
    # program running outside the app (like this bridge) only sees by this real path.
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidates += glob.glob(os.path.join(local, "Packages", "Claude_*", "LocalCache", "Roaming", "Claude",
                                             "claude-code", "*", "*", "claude.exe"))
    if candidates:
        # newest version directory, e.g. ...\claude-code\2.1.309\<hash>\claude.exe
        return max(candidates, key=lambda p: _version_key_dir(p))
    return None


def _version_key_dir(path):
    version_dir = os.path.basename(os.path.dirname(os.path.dirname(path)))
    return tuple(int(n) for n in re.findall(r"\d+", version_dir)[:4])


def parse_claude_line(line):
    """Turn one stream-json line from `claude -p` into zero or more bridge events."""
    try:
        obj = json.loads(line)
    except ValueError:
        return []
    kind = obj.get("type")
    if kind == "stream_event":
        event = obj.get("event", {})
        if event.get("type") == "content_block_delta":
            delta = event.get("delta", {})
            if delta.get("type") == "text_delta" and delta.get("text"):
                return [{"type": "delta", "text": delta["text"]}]
        return []
    if kind == "result":
        if obj.get("is_error"):
            return [{"type": "error", "message": str(obj.get("result") or "Claude returned an error")}]
        return [{"type": "result", "text": obj.get("result") or "", "duration_ms": obj.get("duration_ms")}]
    return []


class EchoEngine:
    """Test engine: repeats the question in two chunks. Only enabled for tests."""
    name = "echo"

    def status(self):
        return {"available": True, "detail": "test engine"}

    def stream(self, req, cancel):
        yield {"type": "meta", "engine": self.name}
        yield {"type": "delta", "text": "You said: "}
        yield {"type": "delta", "text": req["text"]}
        yield {"type": "done", "text": "You said: " + req["text"]}


class ClaudeCliEngine:
    """Claude through the official `claude` command, using your normal subscription login."""
    name = "claude"

    def __init__(self, command=None, default_model=None):
        self.command = command  # list of leading argv items; tests can substitute a fake
        self.default_model = default_model or os.environ.get("JARVIS_CLAUDE_MODEL", "sonnet")

    def _argv(self, model):
        if self.command:
            return list(self.command)
        binary = find_claude()
        if not binary:
            return None
        return [binary, "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--tools", "", "--no-session-persistence", "--disable-slash-commands",
                "--strict-mcp-config", "--setting-sources", "",
                "--model", model, "--system-prompt", SYSTEM_PROMPT]

    def status(self):
        if self.command:
            return {"available": True, "detail": "custom command"}
        binary = find_claude()
        return {"available": bool(binary), "detail": "claude CLI found" if binary else "claude CLI not found"}

    def stream(self, req, cancel):
        model = req.get("model") if req.get("model") in ALLOWED_CLAUDE_MODELS else self.default_model
        argv = self._argv(model)
        if not argv:
            yield {"type": "error", "message": "The claude command was not found on the PC."}
            return
        env = {k: v for k, v in os.environ.items() if k not in STRIPPED_ENV}
        cwd = app_dir() / "claude-cwd"
        cwd.mkdir(parents=True, exist_ok=True)
        prompt = build_prompt(req["text"], req.get("history"), req.get("context"))
        started = time.monotonic()
        try:
            proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    cwd=str(cwd), env=env, text=True, encoding="utf-8", errors="replace",
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except OSError as exc:
            yield {"type": "error", "message": f"Could not start Claude: {exc}"}
            return

        stderr_tail = []

        def drain_stderr():
            for chunk in proc.stderr:
                stderr_tail.append(chunk)
                del stderr_tail[:-20]
            proc.stderr.close()

        def watchdog():
            while proc.poll() is None:
                if cancel.is_set() or time.monotonic() - started > TIMEOUT_SECONDS:
                    proc.kill()
                    return
                time.sleep(0.2)

        threading.Thread(target=drain_stderr, daemon=True).start()
        threading.Thread(target=watchdog, daemon=True).start()
        try:
            proc.stdin.write(prompt)
            proc.stdin.close()
        except OSError:
            pass

        yield {"type": "meta", "engine": self.name, "model": model}
        streamed = []
        final = None
        failed = None
        for line in proc.stdout:
            for event in parse_claude_line(line):
                if event["type"] == "delta":
                    streamed.append(event["text"])
                    yield event
                elif event["type"] == "result":
                    final = event["text"]
                elif event["type"] == "error":
                    failed = event["message"]
        proc.stdout.close()
        proc.wait()
        if cancel.is_set():
            return
        if failed:
            yield {"type": "error", "message": failed}
        elif proc.returncode not in (0, None) and not streamed and final is None:
            detail = "".join(stderr_tail).strip()[-300:] or f"exit code {proc.returncode}"
            yield {"type": "error", "message": f"Claude failed: {detail}"}
        else:
            text = "".join(streamed) or final or ""
            if not streamed and final:
                yield {"type": "delta", "text": final}
            yield {"type": "done", "text": text}


def default_engines():
    engines = {"claude": ClaudeCliEngine()}
    if os.environ.get("JARVIS_BRIDGE_ENABLE_ECHO") == "1":
        engines["echo"] = EchoEngine()
    return engines
