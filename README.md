# Automagik Pocket

Your coding agents on your Android phone: a companion app for herdr-core and Herdr desktop.
Android 8.0 or newer. Not an official Herdr release.

## Install

1. On your phone, open the [latest release](https://github.com/automagik-dev/automagik-pocket/releases/latest)
   and download `automagik.apk`.
2. Have the old **Herdr Pocket** (`dev.herdr.pocket`) installed? Uninstall it first. It can't
   update to this app, and you'll pair again.
3. Open the APK. Allow your browser to install apps when Android asks.
4. Open **Automagik**.

Want to check the download first? See [SECURITY.md](SECURITY.md).

## Connect

### A. Pocket Link (no SSH)

1. On the computer, add the Pocket Link mod to Herdr desktop: follow
   [desktop/pocket-link](desktop/pocket-link/README.md). It needs Node.js 20.3+ and herdr-core,
   on macOS or Linux.
2. In Herdr desktop, press ⌘⌥P (Ctrl+Alt+P on Linux), then **Turn on**, then **Pair a phone**.
   A QR code appears.
3. In the app, tap **Pair with Herdr desktop**, then **Scan the QR code**. You can also paste the
   link.
4. Done. The code works once, for 10 minutes.

### B. SSH

1. On the computer, run herdr-core or open Herdr desktop. `hc server status` should work.
2. Make SSH reachable from the phone: same network, or Tailscale.
3. In the app, open **Settings**, tap **Copy public key**, and add it as a line to
   `~/.ssh/authorized_keys` on the computer. (Or skip this: the app asks for the SSH password and
   doesn't save it.)
4. Tap **Add an SSH machine** (or **Machines → Add machine**). Enter the host, user, port and
   session (`default`). Tap **Save and connect**.
5. Compare the fingerprint with the computer's host key, then tap **Trust and connect**. For an
   Ed25519 key: `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`.

More detail: [docs/guides/using.md](docs/guides/using.md).

## What it does

- **Cockpit:** agents that need you first, then the ones that finished, across your machines.
- **Terminals:** any agent full screen, with its tabs, a key row and a message composer.
- **Alerts:** Android notifications for Needs you and Done, with Reply, Yes/No and answer buttons,
  also while the app is open.
- **Watch in background:** stays connected while you're away and reconnects on its own.
- **Side-button assistant:** hold the side button, even on the lock screen, to see what needs you
  and answer by typing or speaking.
- **Updates itself** from GitHub Releases.
- **Automagik Stage theme,** light and dark, following the phone.

## Privacy and safety

- The app talks only to your own machines, plus GitHub to check for updates. Pocket Link traffic
  is end-to-end encrypted, even through n0's relays.
- No analytics, no ads, no account.
- Answering from the lock screen is off until you turn it on.
- Full policy: [docs/privacy.md](docs/privacy.md). Report a vulnerability: [SECURITY.md](SECURITY.md).

## Limits

- Pocket Link needs the desktop mod and Node.js 20.3+ on macOS or Linux.
- SSH works with Linux and macOS hosts. No jump hosts, no imported SSH keys.
- If Android stops the background service or the phone restarts, open the app and connect again.
- Spaces and tabs come from the desktop; the app doesn't edit them.
- The phone's keys can't be backed up. Uninstalling loses them; pair again.
- Opening a terminal on the phone can resize it on the desktop too.
- Needs herdr-core protocol generation 1. The older `herdr` TUI isn't supported.

## Updates

1. The app checks when it opens. Or tap **Settings → About → Check for updates**.
2. The first time, allow Automagik to install apps. On Android 12 and newer, later updates install
   without asking.
3. The app closes while it updates. Open it again.

A copy signed with a different key (a debug build, a pull request artifact) can't update. Uninstall
it once and install the release.

## Build from source

```sh
cd android && ./gradlew :app:assembleDebug
```

Requirements, tests and the emulator: [docs/guides/development.md](docs/guides/development.md).
How releases are cut: [docs/guides/releasing.md](docs/guides/releasing.md).

## Docs

Start at [docs/_index.md](docs/_index.md): guides, privacy, RFCs and decisions.

## License and attribution

Apache-2.0, see [LICENSE](LICENSE). The bundled xterm.js terminal is MIT.

Herdr desktop's program table and sidebar rules come from
[herdrdev/herdr-app](https://github.com/herdrdev/herdr-app) (Apache-2.0). The wire format follows
[herdr-core's API](https://github.com/herdrdev/herdr-core/tree/master/schema).
[juanibiapina/herdr-mobile](https://github.com/juanibiapina/herdr-mobile) was a protocol and SSH
reference; this app's UI and Kotlin protocol code were written for this project. All bundled
components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
