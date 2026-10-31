"""Finding the `claude` command, and a clean environment to run it in."""

import glob
import os
import re
import shutil

# Never let a stray key make the command bill an API instead of using the subscription login.
STRIPPED_ENV = ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_USE_BEDROCK",
                "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")
# A listener started from inside a Claude session would inherit that session's settings (its host login, proxy
# address and so on). Remote Control must use only the owner's own subscription login.
STRIPPED_PREFIXES = ("ANTHROPIC_", "CLAUDE_CODE_", "CLAUDE_AGENT_", "CLAUDECODE")


def clean_env(environ):
    return {k: v for k, v in environ.items()
            if k not in STRIPPED_ENV and not k.upper().startswith(STRIPPED_PREFIXES)}


def find_claude():
    """The path of the claude command, or None. START_CLAUDE_BIN overrides the search."""
    override = os.environ.get("START_CLAUDE_BIN")
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
    # program running outside the app (like this one) only sees by this real path.
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidates += glob.glob(os.path.join(local, "Packages", "Claude_*", "LocalCache", "Roaming", "Claude",
                                             "claude-code", "*", "*", "claude.exe"))
    if candidates:
        # newest version directory, e.g. ...\claude-code\2.1.309\<hash>\claude.exe
        return max(candidates, key=_version_key_dir)
    return None


def _version_key_dir(path):
    version_dir = os.path.basename(os.path.dirname(os.path.dirname(path)))
    return tuple(int(n) for n in re.findall(r"\d+", version_dir)[:4])
