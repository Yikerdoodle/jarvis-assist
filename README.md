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

## Tests

```
cd bridge
python -m unittest discover -s tests -v
```
