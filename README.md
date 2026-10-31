# Start Claude

A button on your Android home screen. One tap goes down your list of computers, first choice first, and for the
first one that works:

1. **wakes it** if it is asleep (when you are on your home Wi-Fi),
2. **starts Claude's Remote Control** on it, unless Claude is already running there, and
3. **opens the Claude app**, so you can start as many sessions as you like. They all run on that computer.

You never have to walk over to the PC. Reach is by **Tailscale** first, and by your **home network** as a
fallback when Tailscale is not available.

This is a small stand-alone project. It has nothing to do with the rest of the repository it lives in.

```
 phone (Android app, Java)                           each computer (Python, standard library only)
 ┌────────────────────────────────┐                  ┌──────────────────────────────────────┐
 │ your list:                     │   Tailscale      │ GET  /v1/health                      │
 │   1. njk-laptop   ◄── first    │  (or the home    │ POST /v1/remote/start                │
 │   2. another computer          │   network)       │   starts `claude remote-control`     │
 │                                │ ───────────────► │   hidden, once                       │
 │ for each, in order:            │                  │ POST /v1/pair                        │
 │   awake? ─ yes ─► start Claude │                  │   hands the phone what it needs      │
 │   asleep? wake it, wait, start │                  └──────────────────────────────────────┘
 │   cannot reach or wake? next   │
 └────────────────────────────────┘
```

* `pc/`: the program for each computer (Windows is what the installer and the wake-up lookup are written for).
* `android/`: the phone app. The build robot makes the APK on every push (Actions tab, then Artifacts).

## Set up each computer (once, Windows)

You need Python 3.10 or newer, Tailscale (signed in), and the `claude` command logged in.

1. **Trust a folder once, by hand.** Open a terminal in the folder you want your sessions to work in, type
   `claude`, log in if asked, and answer **yes** to "trust this folder". The button cannot answer that question
   for you.
2. **Install the program.** Open PowerShell **as Administrator** in this folder and run:
   ```
   pc\windows\install.ps1
   ```
   It adds two firewall rules and a scheduled task that starts the program hidden whenever you sign in to
   Windows. It is safe to run again. `pc\windows\uninstall.ps1` undoes it. The rules:
   * **Tailscale:** devices on your Tailscale network may connect (TCP 8788).
   * **Home network:** devices on your home network may connect, but **only while Windows calls that network
     "Private"**. Set your home Wi-Fi or Ethernet to Private in Windows settings (Network & internet, then the
     connection's properties). On a "Public" network, such as a cafe or hotel, this stays closed. Delete this
     second rule, or set `START_CLAUDE_NO_LAN=1`, if you only want Tailscale.
3. **Check it.** On that computer, open `http://127.0.0.1:8788/v1/health` in a browser. You should see
   `"ok": true`, the computer's name, and `"available": true` (it found `claude`).
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
5. **Optional: let the computer be woken.** In Device Manager, open the network card, then Power Management,
   and tick "Allow this device to wake the computer" and "Only allow a magic packet to wake the computer". Also
   switch on Wake on LAN in the BIOS/UEFI (it may be called "Power on by PCI-E"). Test from **sleep** first. If
   waking from a full shutdown fails, turn off Windows "Fast startup".

The name the phone shows is the Windows computer name (for example `NJK-LAPTOP`). Set `START_CLAUDE_NAME` to
change it.

## Set up the phone (once)

1. Download the APK from the newest run in the Actions tab (Artifacts) and install it. If you are installing a
   newer build over an older one, uninstall the older one first (see "Updates" below).
2. Open **Start Claude**. Under "Add a computer", type its Tailscale name, for example `njk-laptop`, and tap
   **Add and pair**. That is all: the computer hands over its name, its wake-up address and its home-network
   address by itself. There is no token to copy. Tailscale must be on in the phone for this step.
3. Add your other computers the same way. The list is the **priority order**: first is tried first. Use Up and
   Down to change it.
4. Tap **Check all computers** to see which would answer right now.
5. Tap **Add a button to the home screen** and confirm. (If your home screen cannot do that, touch and hold the
   Start Claude icon: "Start Claude on my PC" is in the menu, and you can drag it out.)

Now tap the new button. A screen shows what is happening ("njk-laptop: Waking your PC... 12 s", "Starting
Claude...") and then opens the Claude app. Look for the computer under **Code**.

**How the order works.** The button takes the list top to bottom. A computer that is awake is used at once. One
that is asleep is woken and waited for (up to 2.5 minutes). One that cannot be reached or woken, for example
because you are away from home, is skipped straight away. If a computer answers but cannot start Claude, the
button moves on to the next. If none works, it tells you why for each.

If a short name like `njk-laptop` does not work on your phone, use the full name
(`njk-laptop.<your-tailnet>.ts.net`) or the `100.x.y.z` address instead.

## Good to know

* A sleeping computer can only be woken from your **home Wi-Fi**. Wake-on-LAN is a shout to the local network
  and cannot travel through Tailscale. Away from home the computer must already be awake, otherwise the button
  moves on to the next one.
* The computer should be on a **cable**. Wi-Fi cards rarely wake a PC.
* The program starts when you sign in to Windows. A computer that is fully shut down will wake up, but sits at
  the sign-in screen until it is signed in (turn on automatic sign-in if you want that). Sleep and hibernate
  keep you signed in, so they work.
* Remote Control only works while the computer is awake. To stop it falling asleep, set Windows to never sleep
  while plugged in.
* Do not also start `claude remote-control` by hand: the button sees it is running and does nothing.
* **The home-network fallback** needs: the phone on that Wi-Fi, Windows calling that network Private, and the
  home-network address the phone noted when it paired. If the computer's address changes (your router handed
  out a new one), tap **Add and pair** again over Tailscale, or give the computer a fixed address in your
  router. The phone's and the computer's clocks must agree to within a minute.
* If the button says Claude stopped right after starting, the message includes what Claude said. The computer
  keeps the full text in `pc\data\remote-control.log`. That file is whatever the `claude remote-control`
  command printed, so treat it as private. The program's own log is `pc\data\pc.log`; it records only what
  happened (which device asked, and the outcome), never your sessions.
* **Updates:** the APK is signed with a throwaway key made by the build robot, so each build has a different
  signature and Android will not install one over another. Uninstall first (you will add your computers
  again, which takes a few seconds). If you want updates to install over each other, sign the build with a key
  you keep.
* Android 17 asks apps that target Android 17 for permission before they talk to devices on the home network.
  This app targets Android 15, so it keeps working as is. If it is ever moved to Android 17, add the
  `ACCESS_LOCAL_NETWORK` permission and ask for it before the first wake-up or home-network request (see
  `WakeSender.java`).

## How it stays safe

Every caller is sorted by where it connects from:

| Caller | Health | Start Claude | Pair |
|---|---|---|---|
| a program on the computer itself | yes | signed request | no |
| **your own device on Tailscale** (checked with `tailscale whois`) | yes | **yes, no secret needed** | yes |
| a device on your home network | signed request | signed request | no |
| anyone else, including the internet and other people's Tailscale devices | no | no | no |

* **Over Tailscale no secret is needed.** Tailscale has already proved the caller is one of your own devices.
* **On the home network nothing proves who is calling**, so each request is signed with a secret. The phone
  gets the secret when it pairs (over Tailscale, where it is safe to hand over) and never types it. A signed
  request does not contain the secret, only a proof that expires after a minute and works once, so someone
  listening on your Wi-Fi learns nothing they can reuse.
* Pairing is only offered over Tailscale, to your own devices, at most ten times a minute. Any of your own
  Tailscale devices can pair, and that gives it nothing it did not already have: it could already start Claude.
* A request carries nothing. The command, folder and options come only from `launcher.json` on the computer,
  so a request can never choose what runs or where. Starting Claude's Remote Control does not let a stranger
  into your sessions: they belong to your Claude account.
* The home-network door is open only on networks Windows calls Private, and only to home-network addresses
  (10.x, 172.16 to 31.x, 192.168.x). Public internet addresses are refused outright.
* "Already running" is decided by looking at the computer's real processes, so a copy you started yourself is
  not started twice, and two quick taps start only one.
* The program removes API-key and Claude-session variables from the environment before it starts Claude, so it
  uses only your own subscription login.
* The wake-up packet is a broadcast on the home Wi-Fi. It cannot be aimed at the internet and carries no secret.

## Tests

```
cd pc
python -m unittest discover -s tests -v
```

The phone's logic (signing, addresses, the order of play, the wake-up packet, the wake-wait-start steps) has
plain unit tests in `android/app/src/test`. The phone signs requests in `Signer.java` and the computer checks
them in `auth.py`; both tests use the same fixed example, so the two cannot drift apart unnoticed. The build
robot runs both sets on every push.
