# Jarvis Assist

A personal assistant for an unrooted Android phone: ask by voice or text from anywhere, and it answers
with Claude (through a bridge on your PC), and later with free and on-device models too.
See [docs/DESIGN.md](docs/DESIGN.md).

Status: early. The app is built by GitHub Actions on every push; see the Actions tab for the APK.

## Pieces

* `app/`: the Android app (Java, no libraries, no analytics).
* `bridge/`: a small Python program for the PC. It only listens on your Tailscale address and loopback.

## Running the bridge (Windows)

```
cd bridge
python -m jarvis_bridge
```

The first start creates an app token in `%LOCALAPPDATA%\jarvis-assist\bridge-token`. Type that into the
app's Settings together with the PC's Tailscale address, for example `http://100.x.y.z:8787`.

Claude needs the official `claude` command to be logged in once on the PC (`claude`, then `/login`).

## Start Claude on your PC from your phone's home screen

One tap on a home-screen button: the phone finds your PC (waking it if it is asleep), the PC starts Claude's
Remote Control, and the Claude app opens. From there you start as many sessions as you like; they all run on
the PC. You never walk over to it.

**Set up the PC (once, Windows):**

1. Update the bridge code, then run `bridge\windows\install-bridge.ps1` again as Administrator. It is safe to
   repeat, and it restarts the bridge with the new code.
2. Check that `claude` works there: open a terminal in the folder you want sessions to run in, type `claude`,
   log in if asked, and answer **yes** to "trust this folder". This must be done once by hand. The button cannot
   answer that question for you.
3. Optional: choose the folder and options by creating `bridge\data\launcher.json`. Every line is optional.
   Without the file, sessions run in your home folder.
   ```json
   {
     "dir": "C:\\Users\\you\\code",
     "name": "My PC",
     "spawn": "worktree",
     "capacity": 8,
     "permission_mode": "default"
   }
   ```
   `spawn` is `same-dir` (all sessions share the folder) or `worktree` (each gets its own copy, needs a git
   folder). `permission_mode` is one of `default`, `acceptEdits`, `auto`, `dontAsk`, `plan`.
   `bypassPermissions` is deliberately not allowed here.
4. To wake the PC while it is asleep: in Device Manager, open the network card, then Power Management, and tick
   "Allow this device to wake the computer" and "Only allow a magic packet to wake the computer". Also switch on
   Wake on LAN in the BIOS/UEFI (it may be called "Power on by PCI-E"). Test from **sleep** first. If waking
   from a full shutdown fails, turn off Windows "Fast startup".

**Set up the phone (once):**

1. Install the newest build from the Actions tab.
2. Open Jarvis settings. The PC address and token must already be filled in and tested.
3. Tap **Get it from the PC**: it fills in the PC's network address (MAC). Then tap **Save and test**.
4. Tap **Add a button to the home screen** and confirm. (If your home screen cannot do that, touch and hold the
   Jarvis icon: "Start Claude on my PC" is in the menu.)

**Good to know:**

* A sleeping PC can only be woken from your **home Wi-Fi**. Wake-on-LAN is a shout to the local network and
  cannot travel through Tailscale. Away from home the PC must already be awake. Everything else works from
  anywhere over Tailscale.
* The PC should be on a **cable**. Wi-Fi cards rarely wake a PC.
* The bridge starts when you sign in to Windows. A PC that is fully shut down will wake up, but sits at the
  sign-in screen until it is signed in (turn on automatic sign-in if you want that). Sleep and hibernate keep
  you signed in, so they work.
* Remote Control only works while the PC is awake. To stop it falling asleep, set Windows to never sleep while
  plugged in.
* Do not also start `claude remote-control` by hand: the button sees it is running and does nothing.
* If the button says Claude stopped right after starting, the message includes what Claude said. The PC keeps
  the full text in `bridge\data\remote-control.log`.
* Android 17 asks apps that target Android 17 for permission before they talk to devices on the home network.
  This app targets Android 15, so it keeps working as is. If it is ever moved to Android 17, add the
  `ACCESS_LOCAL_NETWORK` permission and ask for it first.

## Tests

```
cd bridge
python -m unittest discover -s tests -v
```

The phone's logic (wake-up packet, wait-and-start steps) has plain unit tests in `app/src/test`; the build
robot runs them on every push.
