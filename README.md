# Herdr Pocket

A native Android companion for **herdr-core**, styled after the Herdr desktop app. Kotlin and Jetpack Compose, with the desktop's Graphite palette, status colors, spaces and tabs. The sidebar becomes a hamburger drawer. The selected terminal gets the phone's width; desktop splits become a pane picker.

This is an independent prototype, not an official Herdr release.

## What works

- Save several Linux/macOS SSH hosts and connect with this phone's Ed25519 key or a password.
- Verify a host's SHA-256 fingerprint before authenticating. Changed keys require a new decision.
- See live agents and terminals, grouped by the status the program reports.
- Read the desktop's `herdr-app` document to preserve space names, tab order and split membership, including machines forwarded by a core.
- Open an agent directly into an immersive, full-screen terminal. Tap **…** to reveal navigation, tabs, the keyboard and message composer; Android Back returns to the agent list.
- Swipe through terminal history, keep your place as new output arrives, and tap **↓ Latest** to follow live output again. Swipes in full-screen programs use their mouse/alternate-screen scrolling.
- Type with the Android keyboard and use Esc, Tab, Ctrl+C, arrows, Page Up/Down and Enter keys. The key row appears with the keyboard or terminal controls.
- Send a composed message through `terminal.send`, retaining the draft when a send is not confirmed.
- Start a shell or command. From an existing space it creates a new tab visible in the desktop app through `herdr.space` / `herdr.tab` labels.
- Switch terminals or close the app without stopping the remote program.
- Explore a clearly labeled sample-data preview before connecting.

## Connect your machine

1. Install and run herdr-core on the host, or open Herdr desktop. `hc server status` should work. This client speaks protocol generation 1.
2. Make SSH reachable from the phone, using your local network or Tailscale.
3. Open **Herdr Pocket → Settings** and copy the public key. Add it as a line to the host user's `~/.ssh/authorized_keys`. Alternatively use SSH password authentication for a connection; the app doesn't save passwords.
4. Open **Machines → Add machine**, enter the hostname, SSH user and port, and the core session name (`default` unless you chose another). Tap Connect.
5. Compare the fingerprint shown with your host's SSH public-key fingerprint before trusting it. For an Ed25519 SSH server key, run `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the host.
6. Open the hamburger drawer to choose a space, or choose an agent from All agents.

The app executes `hc connect` through an SSH exec channel. It also finds `herdr-core` and `~/.local/bin/hc` if the login PATH does not contain `hc`. No relay, web server or plugin is installed on the host. It does not connect to the older `herdr` TUI runtime.

Tap **…** in the upper-right of a terminal to reveal its controls. The keyboard button focuses raw terminal input. The chat button opens the native message composer. A normal composed send respects the core's blocked-state protection; answer interactive prompts with the terminal keyboard/key bar.

## Build

Requirements: JDK 17, Android SDK platform 35 and build-tools 35.0.0.

```sh
printf 'sdk.dir=/absolute/path/to/Android/Sdk\n' > local.properties
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk`. It is a **debug-signed test build**, with app ID `dev.herdr.pocket`, and requires Android 8.0/API 26 or newer. Source includes a Gradle wrapper pinned to its distribution checksum. For production distribution, configure your own release signing key.

Protocol integration tests can use a real core without touching your installed session:

```sh
HERDR_TEST_HC=/absolute/path/to/hc ./gradlew :app:testDebugUnitTest --rerun
```

Tests select unique core sessions and temporary XDG state/config/runtime directories, and stop only those scratch cores. `scripts/ssh_test_host.py` is an optional loopback-only fixture for SSH verification (requires Python Paramiko). It is not needed by the app.

## Current limits

- Connections run in the app process. Background watching, push notifications, automatic reconnect, SSH jump hosts and imported SSH identities are not implemented.
- Linux/macOS SSH hosts are supported. The connection command assumes a Unix shell; Windows hosts need a different launcher.
- Space/tab layout is read from the desktop. This version does not edit, rename or delete that document or run desktop mods.
- **Full color** uses a bundled, offline xterm.js WebView. **Native text**, selectable in Settings, renders the core’s screen snapshot directly in Compose, keeps the message and key controls, and activates automatically if the color renderer fails. Native text has no terminal colors; it keeps up to 500 lines of local shell history and sends scroll events to alternate-screen programs. Navigation, lists, settings, SSH and the core client are native Kotlin. Kitty images and Windows win32-input-mode are not supported.
- A phone attach can resize the same terminal shown on the desktop; herdr-core gives the last active viewer control of its size.
- The phone's private key is stored in Android app-private, no-backup storage. Uninstalling loses it. This prototype does not offer key backup or hardware-backed SSH signing. Backups and device transfers of app data are disabled.

Optional terminal regression checks: install Python Playwright and Chromium, then run `python scripts/test_terminal.py`. These cover zero-height parents, keyboard resizing/input, touch scrolling in history and alternate-screen programs, preserving scroll position on output, and returning to the latest output.

The terminal assets are already bundled; Node is not needed for an Android build. To refresh those pinned assets, run `scripts/vendor-terminal.sh` with npm available. The script transpiles xterm for older Android WebViews.

## Source map

- `Model.kt`: profiles, terminal records, desktop layout reader, bounded frame codec.
- `CoreClient.kt`: framed JSON-RPC, event subscription and terminal channels with byte-credit flow control.
- `Ssh.kt`: Ed25519 identity, host-key pinning, SSH transport.
- `PocketViewModel.kt`: connections, inventory, selection, terminal lifecycle and ordered input.
- `MainActivity.kt`, `Screens.kt`: native Compose UI and Graphite theme.
- `TerminalScreen.kt`, `assets/terminal/`: offline terminal surface and native key/message controls.

## Attribution

Graphite color values and the Herdr mark come from [herdrdev/herdr-app](https://github.com/herdrdev/herdr-app), Apache-2.0. The wire format follows [herdr-core's API](https://github.com/herdrdev/herdr-core/tree/master/schema). [juanibiapina/herdr-mobile](https://github.com/juanibiapina/herdr-mobile) was consulted as a protocol/SSH reference; this application's native UI and Kotlin protocol implementation were written for this project.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Project code is Apache-2.0; the bundled terminal has its own MIT license.
