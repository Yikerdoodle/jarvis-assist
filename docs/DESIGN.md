# Jarvis Assist: design

One entry point on an unrooted Android phone that answers by voice or text and picks the best engine
itself. Engines are interchangeable: Claude (through a PC bridge), a free cloud model, and an on-device
model that works offline. Zero extra cost: no paid API keys anywhere.

## How it was checked

An earlier plan for this was reviewed against current sources before building. What changed:

| Earlier idea | What the checks found | What this project does instead |
|---|---|---|
| Tasker overlays and a hardware-key remap | Tasker is paid. Volume-key combos cannot be remapped on stock Android without an accessibility service. | A real Android app. Entry points: Quick Settings tile, Quick Tap, launcher icon, and (optional) the default-assistant gesture. |
| Run a 7B model offline | About 4.5 GB RAM, slow, hot, and killed in the background. | Gemma 4 E2B/E4B (LiteRT-LM) and Gemini Nano (AICore) are the candidates. Picked by measurement on the phone, not by guess. |
| Stream raw audio to the server | Sends private audio off the phone for no benefit. | Transcribe on the phone, send text only, speak with Android text-to-speech. |
| Google AI Studio free tier for heavy jobs | Free-tier prompts may be used by Google to improve products. | Only non-personal questions may go there. |
| "Flat-rate" Claude workspace | `claude -p` draws from the same plan limits as interactive use. A phone app must never hold the Claude login. | The bridge calls the official `claude` command on the PC. Claude is used on demand. |
| Localhost ADB-over-Wi-Fi as the base | Wireless debugging turns off on reboot or Wi-Fi change. | One-time `adb shell pm grant` from a PC where needed; loopback ADB is an optional extra. |

## Pieces

```
 phone app (Java, no libraries)            PC bridge (Python, standard library only)
 ┌──────────────────────────────┐          ┌───────────────────────────────────┐
 │ AskActivity: type or speak   │  tailnet │ GET  /v1/health                   │
 │ Router: choose an engine     │ ───────► │ POST /v1/ask  → streamed NDJSON   │
 │ Tile / assistant session     │          │ checks: same-tailnet-user device  │
 │ on-device engine (later)     │          │         + app token               │
 └──────────────────────────────┘          │ engine: `claude -p`, tools off    │
                                           └───────────────────────────────────┘
```

* Bridge security: only loopback or a device owned by the same tailnet user may connect (checked with
  `tailscale whois`), and every request needs the app token. It binds to the Tailscale address and
  loopback only, never to the LAN.
* The bridge never logs what you ask; only the engine, outcome and duration.
* The bridge removes API-key environment variables before starting `claude`, so it cannot bill an API.
* Claude runs with all tools disabled in an empty working folder. Anything that changes the phone or
  files will be a separate, confirmed step.

## Start Claude on the PC (home-screen button)

```
 phone: WakeActivity (pinned shortcut)           PC bridge
 ┌──────────────────────────────────┐            ┌────────────────────────────────────────┐
 │ 1. GET /v1/health  ── answers? ──┼── tailnet ►│                                        │
 │    no: Wake-on-LAN broadcast on  │            │ POST /v1/remote/start                  │
 │    the home Wi-Fi, poll health   │            │   same checks as /v1/ask               │
 │ 2. POST /v1/remote/start ────────┼───────────►│   already running? → say so            │
 │ 3. open the Claude app           │            │   else start `claude remote-control`   │
 └──────────────────────────────────┘            │   hidden, in the folder from           │
                                                 │   launcher.json, no input, log to file │
                                                 └────────────────────────────────────────┘
```

* The request carries nothing. The command, folder and options come only from `launcher.json` on the PC, so a
  request can never choose what runs or where. `bypassPermissions` cannot be configured.
* "Already running" is decided by looking at the PC's real processes (a whole-argument match on
  `claude remote-control`), so it also covers one started by hand or by an earlier run of the bridge. A lock
  makes two quick taps start one copy.
* The child gets the same cleaned environment as the ask engine: no API keys, no inherited Claude session.
* The wake-up packet is a broadcast on the home Wi-Fi, bound to that network so it cannot be swallowed by the
  VPN. It never goes through Tailscale, so waking only works at home. The packet is not secret and is not
  authenticated; it can only wake a PC that was set up to listen.
* The phone learns the PC's MAC from `GET /v1/wake-info` (token required), which lists connected, physical
  network cards only.

## Roadmap

1. **Shell** (this): tile, ask screen, voice in, spoken out, bridge to Claude, engine switch.
2. On-device engine: benchmark Gemma 4 E2B/E4B and Gemini Nano on the phone, then wire the winner in so
   "Auto" tries it first and offline still works.
3. Free cloud engine for non-personal questions.
4. Device actions through a fixed, confirmed tool list.
5. Personal context from permissions the owner grants (calendar, notifications, health, current screen).
6. Code and file track through Claude on the PC.
