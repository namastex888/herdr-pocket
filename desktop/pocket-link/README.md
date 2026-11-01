# Pocket Link for Herdr

Pair your phone with Herdr desktop like a TV pairs with a streaming account:
press **Pair a phone**, scan the code with [Automagik Pocket](https://github.com/automagik-dev/automagik-pocket),
done. Your agents on your phone from anywhere, with no SSH, no password and no port to open.

![Pocket Link](preview.png)

## Install

Ask your Herdr agent to add this mod, or do it yourself:

1. Copy `mods/pocket-link/` into `~/.config/herdr-app/mods/`.
2. Merge `keys.json` into `~/.config/herdr-app/keys.json` (⌘⌥P on macOS, Ctrl+Alt+P on Linux).
3. Open it with ⌘⌥P. The first time, press **Install the bridge** (npm, about 25 MB), then **Turn on**.

It needs Node.js 20.3+ and herdr-core (`hc`), on macOS or Linux (systemd).

## What it does on your computer

- Runs a small bridge (`mods/pocket-link/bridge/bridge.mjs`) as a login service:
  a LaunchAgent on macOS, or a systemd user unit on Linux.
- **Network:** [iroh](https://iroh.computer). Phones dial this computer by its public key.
  Connections are direct when they can be, otherwise through n0's public relays,
  and always end to end encrypted. The bridge also publishes its address to n0's lookup service.
- **Programs it runs:** `node`, `npm` (setup), `hc connect`, `launchctl` / `systemctl --user`, `sh`;
  for the usage meter, `hc machine ls` and `hc exec` (`uname`, `sh`, `curl`, `nice`, the meter).
- **Files it writes:** its own `.state/` folder, and the one login service file; on each machine,
  `~/.local/bin/automagik-meter` (and the meter's own store, see its README).

- **Usage meter:** while the bridge runs, it keeps the
  [Automagik usage meter](../../meter/README.md) on this computer and on every connected herdr-core
  machine, so the phone can show token use and spend:
  - Every 10 minutes after the last pass, one pass at a time (a pass still running is never
    queued behind).
  - On each machine, through `hc exec`, it runs `uname -sm`. If
    `~/.local/bin/automagik-meter` is missing or isn't the latest release's build, it downloads
    that build from this repository's GitHub releases with `curl` and installs it only once its
    SHA-256 matches the release's `SHA256SUMS`.
  - Then it runs `nice -n 19 ~/.local/bin/automagik-meter sync`, unless the machine is on battery.
  - The commands are fixed; nothing a phone sends reaches them. The only network use is the
    release download.
  - The dialog shows a line only for a machine where this keeps failing.
  - Run one pass by hand with `node bridge/bridge.mjs meter [machine]`.

A paired phone can use every herdr-core session on this computer, just like a terminal running `hc`.
Pair only your own phones. Remove one in the dialog and it's disconnected at once.

See [RECIPE.md](RECIPE.md) for how it's built.

## License

Apache-2.0
