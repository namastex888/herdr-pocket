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
- **Programs it runs:** `node`, `npm` (setup), `hc connect`, `launchctl` / `systemctl --user`, `sh`.
- **Files it writes:** its own `.state/` folder, and the one login service file.

A paired phone can use every herdr-core session on this computer, just like a terminal running `hc`.
Pair only your own phones. Remove one in the dialog and it's disconnected at once.

See [RECIPE.md](RECIPE.md) for how it's built.

## License

Apache-2.0
