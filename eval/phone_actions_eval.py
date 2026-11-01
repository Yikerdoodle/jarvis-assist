"""A small, fair test of how reliably a model drives phone actions through tool calls.

It sends 28 everyday requests with a fixed tool list to an Ollama server and checks the tool calls it makes.
Some cases are traps on purpose: questions that need no tool, requests no tool can do, and requests with missing
details. In those the right answer is to NOT call a tool, because a wrong action on a phone is worse than a question.

Run (standard library only):
    python phone_actions_eval.py --model qwen3.5:4b --host localhost:11434
Writes a JSON summary to stdout (and details to --out if given).
"""
import argparse
import json
import sys
import time
import urllib.request


def fn(name, description, props, required):
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": {"type": "object", "properties": props, "required": required}}}


TOOLS = [
    fn("set_timer", "Start a countdown timer.",
       {"minutes": {"type": "integer", "description": "Length in minutes."}, "label": {"type": "string"}}, ["minutes"]),
    fn("set_alarm", "Set an alarm for a time of day.",
       {"time": {"type": "string", "description": "24-hour time, HH:MM."}, "label": {"type": "string"}}, ["time"]),
    fn("call_contact", "Call a contact by name.", {"name": {"type": "string"}}, ["name"]),
    fn("send_message", "Send a text message to a contact.",
       {"contact": {"type": "string"}, "text": {"type": "string"}}, ["contact", "text"]),
    fn("open_app", "Open an app by name.", {"app": {"type": "string"}}, ["app"]),
    fn("set_setting", "Turn a phone setting on or off.",
       {"setting": {"type": "string", "enum": ["wifi", "bluetooth", "flashlight", "do_not_disturb", "airplane_mode"]},
        "on": {"type": "boolean"}}, ["setting", "on"]),
    fn("set_volume", "Set a volume level.",
       {"stream": {"type": "string", "enum": ["media", "ring", "alarm"]},
        "percent": {"type": "integer", "description": "0 to 100."}}, ["stream", "percent"]),
    fn("create_event", "Add a calendar event.",
       {"title": {"type": "string"}, "start": {"type": "string", "description": "Local time, YYYY-MM-DDTHH:MM."},
        "duration_minutes": {"type": "integer"}}, ["title", "start"]),
    fn("navigate_to", "Start navigation to a place.", {"place": {"type": "string"}}, ["place"]),
    fn("play_music", "Play music.", {"query": {"type": "string"}}, ["query"]),
]

SYSTEM = ("You are a phone assistant. Use the provided tools to carry out the owner's requests. Call a tool only when "
          "the request clearly asks for that action and you have every detail it needs. If a detail is missing, or no "
          "tool fits the request, reply in text instead: ask one short question, or say plainly that you cannot do it. "
          "Never claim to have done something unless you called the tool. "
          "Today is Saturday 2026-10-31 and the time is 09:30.")

# Each case: (category, request, expected). expected is a list of (tool, {arg: (check, value)}); [] means no tool call.
EQ, HAS = "eq", "has"
CASES = [
    ("single", "Set a timer for ten minutes", [("set_timer", {"minutes": (EQ, 10)})]),
    ("single", "Timer for an hour and a half", [("set_timer", {"minutes": (EQ, 90)})]),
    ("single", "Wake me at 6:45 tomorrow morning", [("set_alarm", {"time": (EQ, "06:45")})]),
    ("single", "Alarm for half past seven in the evening", [("set_alarm", {"time": (EQ, "19:30")})]),
    ("single", "Call Sam", [("call_contact", {"name": (HAS, "sam")})]),
    ("single", "Text Dana that I will be ten minutes late",
     [("send_message", {"contact": (HAS, "dana"), "text": (HAS, "late")})]),
    ("single", "Open the camera", [("open_app", {"app": (HAS, "camera")})]),
    ("single", "Turn on the flashlight", [("set_setting", {"setting": (EQ, "flashlight"), "on": (EQ, True)})]),
    ("single", "Switch off bluetooth", [("set_setting", {"setting": (EQ, "bluetooth"), "on": (EQ, False)})]),
    ("single", "Put airplane mode on", [("set_setting", {"setting": (EQ, "airplane_mode"), "on": (EQ, True)})]),
    ("single", "Set the media volume to 30 percent", [("set_volume", {"stream": (EQ, "media"), "percent": (EQ, 30)})]),
    ("single", "Ring volume to the maximum", [("set_volume", {"stream": (EQ, "ring"), "percent": (EQ, 100)})]),
    ("single", "Add a dentist appointment tomorrow at 3 pm for 45 minutes",
     [("create_event", {"title": (HAS, "dentist"), "start": (EQ, "2026-11-01T15:00"), "duration_minutes": (EQ, 45)})]),
    ("single", "Navigate to the nearest pharmacy", [("navigate_to", {"place": (HAS, "pharmacy")})]),
    ("single", "Play some Miles Davis", [("play_music", {"query": (HAS, "miles davis")})]),
    ("single", "Do not disturb on", [("set_setting", {"setting": (EQ, "do_not_disturb"), "on": (EQ, True)})]),
    ("multi", "Text Mum that I am on my way and start a 20 minute timer",
     [("send_message", {"contact": (HAS, "mum"), "text": (HAS, "way")}), ("set_timer", {"minutes": (EQ, 20)})]),
    ("multi", "Turn off wifi and turn on bluetooth",
     [("set_setting", {"setting": (EQ, "wifi"), "on": (EQ, False)}),
      ("set_setting", {"setting": (EQ, "bluetooth"), "on": (EQ, True)})]),
    ("multi", "Navigate to the airport and play some driving music",
     [("navigate_to", {"place": (HAS, "airport")}), ("play_music", {"query": (HAS, "driving")})]),
    ("no_tool", "What is the capital of France?", []),
    ("no_tool", "Tell me a short joke", []),
    ("no_tool", "Thanks, that is all", []),
    ("unsupported", "Book me a flight to Toronto", []),
    ("unsupported", "Order me a pizza", []),
    ("unsupported", "Turn on the oven at home", []),
    ("missing_detail", "Call him", []),
    ("missing_detail", "Set a timer", []),
    ("missing_detail", "Send a message", []),
]


def check_arg(actual, rule):
    kind, want = rule
    if kind == EQ:
        if isinstance(want, bool):
            return actual is want or str(actual).lower() == str(want).lower()
        if isinstance(want, int):
            try:
                return int(float(actual)) == want
            except (TypeError, ValueError):
                return False
        return str(actual).strip().lower() == str(want).lower()
    return want in str(actual).lower()


def call_matches(actual, expected):
    name, rules = expected
    if actual["name"] != name:
        return False
    args = actual["arguments"] if isinstance(actual["arguments"], dict) else {}
    return all(key in args and check_arg(args[key], rule) for key, rule in rules.items())


def judge(actual_calls, expected):
    """Pass only if the calls are exactly the expected ones (any order). No extra calls, none missing."""
    if len(actual_calls) != len(expected):
        return False
    remaining = list(actual_calls)
    for exp in expected:
        hit = next((a for a in remaining if call_matches(a, exp)), None)
        if hit is None:
            return False
        remaining.remove(hit)
    return True


def ask(host, model, request, think, api="ollama"):
    """Returns (tool calls, reply text, seconds, speed numbers). api is "ollama" or "openai" (llama.cpp's llama-server)."""
    messages = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": request}]
    if api == "openai":
        body = {"model": model, "stream": False, "tools": TOOLS, "messages": messages, "temperature": 0,
                "max_tokens": 512}
        if think is False:
            body["chat_template_kwargs"] = {"enable_thinking": False}  # skip the long "thinking" for tool calls
        url = f"http://{host}/v1/chat/completions"
    else:
        body = {"model": model, "stream": False, "tools": TOOLS, "messages": messages,
                "options": {"temperature": 0, "num_ctx": 4096}}
        if think is not None:
            body["think"] = think
        url = f"http://{host}/api/chat"
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    started = time.monotonic()
    with urllib.request.urlopen(req, timeout=900) as resp:
        data = json.load(resp)
    seconds = time.monotonic() - started
    if api == "openai":
        message = (data.get("choices") or [{}])[0].get("message", {})
        calls = []
        for c in message.get("tool_calls") or []:
            arguments = c["function"].get("arguments", {})
            if isinstance(arguments, str):  # the OpenAI format sends the arguments as a JSON string
                try:
                    arguments = json.loads(arguments)
                except ValueError:
                    arguments = {}
            calls.append({"name": c["function"]["name"], "arguments": arguments})
        t = data.get("timings", {})
        speed = {"prompt_tokens": t.get("prompt_n"), "output_tokens": t.get("predicted_n"),
                 "prompt_tps": t.get("prompt_per_second"), "decode_tps": t.get("predicted_per_second")}
    else:
        message = data.get("message", {})
        calls = [{"name": c["function"]["name"], "arguments": c["function"].get("arguments", {})}
                 for c in message.get("tool_calls") or []]
        eval_ns = data.get("eval_duration") or 0
        speed = {"prompt_tokens": data.get("prompt_eval_count"), "output_tokens": data.get("eval_count"),
                 "prompt_tps": None,
                 "decode_tps": (data.get("eval_count", 0) / (eval_ns / 1e9)) if eval_ns else None}
    return calls, (message.get("content") or "").strip(), seconds, speed


def _mean(values):
    values = [v for v in values if v]
    return round(sum(values) / len(values), 1) if values else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--host", default="localhost:11434")
    ap.add_argument("--think", choices=["on", "off", "default"], default="off")
    ap.add_argument("--limit", type=int, default=0, help="run only the first N cases (0 = all)")
    ap.add_argument("--skip", type=int, default=0, help="skip the first N cases (to resume or to run only the rest)")
    ap.add_argument("--api", choices=["ollama", "openai"], default="ollama",
                    help='"openai" is the format llama.cpp\'s llama-server speaks')
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    think = {"on": True, "off": False, "default": None}[args.think]
    cases = CASES[args.skip:]
    if args.limit:
        cases = cases[:args.limit]

    rows, by_cat = [], {}
    for category, request, expected in cases:
        try:
            calls, text, seconds, speed = ask(args.host, args.model, request, think, args.api)
            ok = judge(calls, expected)
            error = None
        except Exception as exc:  # a transport or server problem counts as a failure, and is reported
            calls, text, seconds, speed, ok, error = [], "", 0.0, {}, False, str(exc)
        rows.append({"category": category, "request": request, "pass": ok, "seconds": round(seconds, 1),
                     "speed": speed, "calls": calls, "text": text[:160], "error": error})
        by_cat.setdefault(category, []).append(ok)
        print(f"{'PASS' if ok else 'FAIL'} {seconds:5.1f}s [{category}] {request}", file=sys.stderr, flush=True)

    summary = {
        "model": args.model, "think": args.think, "cases": len(rows),
        "passed": sum(r["pass"] for r in rows),
        "accuracy_percent": round(100.0 * sum(r["pass"] for r in rows) / len(rows), 1),
        "by_category": {c: f"{sum(v)}/{len(v)}" for c, v in by_cat.items()},
        "mean_seconds": round(sum(r["seconds"] for r in rows) / len(rows), 1),
        "mean_decode_tokens_per_second": _mean([r["speed"].get("decode_tps") for r in rows]),
        "mean_prompt_tokens_per_second": _mean([r["speed"].get("prompt_tps") for r in rows]),
        "mean_prompt_tokens": _mean([r["speed"].get("prompt_tokens") for r in rows]),
        "failures": [{k: r[k] for k in ("category", "request", "calls", "text", "error")} for r in rows if not r["pass"]],
    }
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump({"summary": summary, "rows": rows}, handle, indent=2)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
