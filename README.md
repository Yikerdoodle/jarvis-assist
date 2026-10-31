# Start Claude

A button on your Android home screen. One tap:

1. finds your PC over Tailscale (and **wakes it** if it is asleep, when you are on your home Wi-Fi),
2. starts Claude's **Remote Control** on the PC, and
3. opens the Claude app, so you can start as many sessions as you like. They all run on the PC.

You never have to walk over to the PC.

This is a small stand-alone project. It has nothing to do with the rest of the repository it lives in.

```
 phone (Android app, Java)                          PC (Python, standard library only)
 ┌──────────────────────────────┐                  ┌───────────────────────────────────────┐
 │ home-screen button           │   Tailscale      │ GET  /v1/health                       │
 │  1. is the PC up?        ────┼─────────────────►│ POST /v1/remote/start                 │
 │  2. no? shout "wake up!"     │                  │       starts `claude remote-control`  │
 │     on the home Wi-Fi        │                  │       hidden, once                    │
 │  3. start Claude         ────┼─────────────────►│ GET  /v1/wake-info (network address)  │
 │  4. open the Claude app      │                  │                                       │
 └──────────────────────────────┘                  └───────────────────────────────────────┘
```

* `pc/`: the program for the PC (Windows is the target; the tests run anywhere).
* `android/`: the phone app. The build robot makes the APK on every push (Actions tab, then Artifacts).

## Set up the PC (once, Windows)

You need Python 3.10 or newer, Tailscale (signed in), and the `claude` command logged in.

1. **Trust a folder once, by hand.** Open a terminal in the folder you want your sessions to work in, type
   `claude`, log in if asked, and answer **yes** to "trust this folder". The button cannot answer that question
   for you.
2. **Install the program.** Open PowerShell **as Administrator** in this folder and run:
   ```
   pc\windows\install.ps1
   ```
   It adds a firewall rule that lets only your Tailscale devices in (TCP 8788), and a scheduled task that starts
   the program hidden whenever you sign in to Windows. It is safe to run again. `pc\windows\uninstall.ps1`
   undoes it.
3. **Find the token.** Open `pc\data\token` in Notepad. You will type it into the phone. Do not share it.
4. **Optional: choose the folder and options.** Without a file, sessions run in your home folder. To change
   that, create `pc\data\launcher.json`. Every line is optional:
   ```json
   {
     "dir": "C:\\Users\\you\\code",
     "name": "My PC",
     "spawn": "worktree",
     "capacity": 8,
     "permission_mode": "default"
   }
   ```
   * `spawn`: `same-dir` (sessions share the folder) or `worktree` (each gets its own copy; needs a git folder).
   * `permission_mode`: `default`, `acceptEdits`, `auto`, `dontAsk` or `plan`. `bypassPermissions` is
     deliberately not allowed here: a tap on a phone should never start sessions that run unchecked.
5. **Optional: let the PC be woken.** In Device Manager, open the network card, then Power Management, and tick
   "Allow this device to wake the computer" and "Only allow a magic packet to wake the computer". Also switch
   on Wake on LAN in the BIOS/UEFI (it may be called "Power on by PCI-E"). Test from **sleep** first. If waking
   from a full shutdown fails, turn off Windows "Fast startup".

## Set up the phone (once)

1. Download the APK from the newest run in the Actions tab (Artifacts) and install it. If you are installing a
   newer build over an older one, uninstall the older one first (see "Updates" below).
2. Open **Start Claude**. Type the PC's Tailscale address with the port, for example `http://100.x.y.z:8788`,
   and the token.
3. Tap **Get it from the PC**. It fills in the PC's network address (MAC). Then tap **Save and test**. You
   should see "Connected".
4. Tap **Add a button to the home screen** and confirm. (If your home screen cannot do that, touch and hold the
   Start Claude icon: "Start Claude on my PC" is in the menu, and you can drag it out.)

Now tap the new button. A screen shows what is happening ("Looking for your PC...", "Waking your PC...",
"Starting Claude...") and then opens the Claude app. Look for your PC under **Code**.

## Good to know

* A sleeping PC can only be woken from your **home Wi-Fi**. Wake-on-LAN is a shout to the local network and
  cannot travel through Tailscale. Away from home the PC must already be awake. Everything else works from
  anywhere over Tailscale, including at home.
* The PC should be on a **cable**. Wi-Fi cards rarely wake a PC.
* The program starts when you sign in to Windows. A PC that is fully shut down will wake up, but sits at the
  sign-in screen until it is signed in (turn on automatic sign-in if you want that). Sleep and hibernate keep
  you signed in, so they work.
* Remote Control only works while the PC is awake. To stop it falling asleep, set Windows to never sleep while
  plugged in.
* Do not also start `claude remote-control` by hand: the button sees it is running and does nothing.
* If the button says Claude stopped right after starting, the message includes what Claude said. The PC keeps
  the full text in `pc\data\remote-control.log`. That file is whatever the `claude remote-control` command
  printed, so treat it as private. The program's own log is `pc\data\pc.log`; it records only what happened
  (which device asked, and the outcome), never your sessions.
* **Updates:** the APK is signed with a throwaway key made by the build robot, so each build has a different
  signature and Android will not install one over another. Uninstall first (your settings are lost, but they are
  three fields). If you want updates to install over each other, sign the build with a key you keep.
* Android 17 asks apps that target Android 17 for permission before they talk to devices on the home network.
  This app targets Android 15, so it keeps working as is. If it is ever moved to Android 17, add the
  `ACCESS_LOCAL_NETWORK` permission and ask for it before the first wake-up (see `WakeSender.java`).

## How it stays safe

* The program listens on loopback and on the PC's Tailscale address only, never on your home network, and the
  firewall rule only admits Tailscale addresses.
* Every request must come from a device on **your own** tailnet (checked with `tailscale whois`) **and** carry
  the token. Anything else gets nothing.
* A request carries nothing. The command, folder and options come only from `launcher.json` on the PC, so a
  request can never choose what runs or where.
* "Already running" is decided by looking at the PC's real processes, so a copy you started yourself is not
  started twice, and two quick taps start only one.
* The program removes API-key and Claude-session variables from the environment before it starts Claude, so it
  uses only your own subscription login.
* The wake-up packet is a broadcast on the home Wi-Fi. It cannot be aimed at the internet and carries no secret.

## Tests

```
cd pc
python -m unittest discover -s tests -v
```

The phone's logic (the wake-up packet, and the wake-wait-start steps) has plain unit tests in
`android/app/src/test`. The build robot runs both sets on every push.
