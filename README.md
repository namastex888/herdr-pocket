# Herdr Pocket

A native Android companion for **herdr-core**, styled after the Herdr desktop app. Kotlin and Jetpack Compose, with the desktop's Graphite palette, status colors, spaces and tabs. The sidebar becomes a hamburger drawer. The selected terminal gets the phone's width; desktop splits become a pane picker.

This is an independent prototype, not an official Herdr release.

## What works

- Save several Linux/macOS SSH hosts, edit them later, and connect with one tap from the start screen using this phone's Ed25519 key. If a host rejects the key, the app asks for its SSH password.
- Verify a host's SHA-256 fingerprint before authenticating. Changed keys require a new decision.
- See live agents and terminals, grouped by the status the program reports.
- Read the desktop's `herdr-app` document to preserve space names, tab order and split membership, including machines forwarded by a core.
- Open an agent directly into an immersive, full-screen terminal. Tap **…** to reveal navigation, tabs, the keyboard and message composer; Android Back returns to the agent list.
- Swipe through terminal history, keep your place as new output arrives, and tap **↓ Latest** to follow live output again. Swipes in full-screen programs use their mouse/alternate-screen scrolling.
- Type with the Android keyboard and use the key row: Esc, sticky **Ctrl** and **Alt** (they apply to the next key or typed letter), Tab, Shift+Tab, arrows, Enter, Ctrl+C, Home/End, Page Up/Down, common symbols and Paste. Arrows and paging repeat while held. Cursor keys and paste follow the program's application-cursor and bracketed-paste modes. The key row appears with the keyboard or terminal controls.
- Pinch a terminal with two fingers to change its text size, or set it in Settings. The size is remembered.
- Alerts, like the desktop's Settings > Alerts: while the app is connected, it plays a sound when an agent needs you, or finishes while you aren't looking at it, and shows a toast with **Open**. Turn sounds, Needs you and Done on or off in **Settings → Alerts**.
- Android notifications when you aren't in the app: **Needs you** and **Done**, one per agent, with **Reply** (typed into the agent, and the outcome shows in the notification) and a tap that opens the agent. A notification goes away once the agent is answered elsewhere, starts working again, or you open it. **Answer from the lock screen** (on by default) shows what the agent asks on the lock screen and lets Reply work there without unlocking; turn it off to require unlocking.
- **Watch in background** (on by default): while a machine is connected, a foreground service keeps the connections open with a "Watching N machines" notification; **Stop** there disconnects. A dropped connection comes back on its own (Pocket Link and key-based SSH; a password connection waits for you). In the background the app reads changes less often and skips git checks.
- If a connection drops, the terminal and agent list show a **Reconnect** action; pull down on the agent list to refresh or retry.
- Send a composed message through `terminal.send`, retaining the draft when a send is not confirmed.
- Start a shell or command. From an existing space it creates a new tab visible in the desktop app through `herdr.space` / `herdr.tab` labels.
- Switch terminals or close the app without stopping the remote program.
- Explore a clearly labeled sample-data preview before connecting.

## Connect your machine

### Pair with Herdr desktop (no SSH)

1. On the computer, add the [Pocket Link](https://github.com/namastex888/herdr-pocket-link) mod to Herdr desktop. It needs Node.js 20.3+.
2. Open it with ⌘⌥P (Ctrl+Alt+P on Linux), then press **Turn on** and **Pair a phone**. A QR code appears.
3. In the app, tap **Pair with Herdr desktop**, then **Scan the QR code**. You can also paste the link, or open it on the phone.

The phone and the desktop dial each other by public key over [iroh](https://iroh.computer). The connection is direct on the same network or through hole punching, and otherwise goes through n0's public relays. It is end to end encrypted either way. The code works once, for 10 minutes. Removing the phone in Pocket Link disconnects it immediately. No port is opened on the computer, and no SSH or password is needed. Scanning uses a bundled scanner (ZXing), so it works without Google Play services; the app asks for the camera the first time.

### Over SSH

1. Install and run herdr-core on the host, or open Herdr desktop. `hc server status` should work. This client speaks protocol generation 1.
2. Make SSH reachable from the phone, using your local network or Tailscale.
3. Open **Herdr Pocket → Settings** and copy the public key. Add it as a line to the host user's `~/.ssh/authorized_keys`. Alternatively use SSH password authentication: the app asks for the password when the host rejects the key, and doesn't save it.
4. Tap **Connect a machine** (or **Machines → Add machine**), enter the hostname, SSH user and port, and the core session name (`default` unless you chose another). Tap **Save and connect**.
5. Compare the fingerprint shown with your host's SSH public-key fingerprint before trusting it. For an Ed25519 SSH server key, run `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the host.
6. Open the hamburger drawer to choose a space, or open an agent from the Cockpit: what needs you first, then what finished.

The app executes `hc connect` through an SSH exec channel. It also finds `herdr-core` and `~/.local/bin/hc` if the login PATH does not contain `hc`. No relay, web server or plugin is installed on the host. It does not connect to the older `herdr` TUI runtime.

When you add a machine, the app saves it and connects straight away. Later, tap a saved machine on the start screen to connect.

Tap **…** in the upper-right of a terminal to reveal its controls. The keyboard button focuses raw terminal input. The chat button opens the native message composer. A normal composed send respects the core's blocked-state protection; answer interactive prompts with the terminal keyboard/key bar.

## Build

Requirements: JDK 17, Android SDK platform 35 and build-tools 35.0.0.

```sh
printf 'sdk.dir=/absolute/path/to/Android/Sdk\n' > local.properties
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions (`.github/workflows/android.yml`) runs the same build, tests and lint, plus the terminal page checks, on every pull request and push to `main`. It requires Android 8.0/API 26 or newer, with app ID `dev.herdr.pocket`. Source includes a Gradle wrapper pinned to its distribution checksum.

### Install and update

Every push to `main` publishes a release-signed build on the [Releases](https://github.com/namastex888/herdr-pocket/releases/latest) page. Install `herdr-pocket.apk` from there once. After that, the app checks for a newer build when it opens and offers it; **Settings → About → Check for updates** checks on demand. Android asks once to allow Herdr Pocket to install apps. On Android 12 and newer, updates then install without another prompt; older versions show Android's "Update this app?" confirmation. The app closes while it updates; open it again to continue.

Each release carries `latest.json` (version code, APK URL and SHA-256). The app installs only an APK whose hash matches, and Android installs it only if it's signed with the same key as the installed copy. A copy built with a different key (a pull request artifact, or a debug build from another machine) can't update in place: uninstall it once, then install the release.

The version code is the commit's time in minutes since 2025, so each build on `main` is newer than the one before it.

**Release key.** CI signs with the `POCKET_SIGNING_KEYSTORE` (base64 PKCS12) and `POCKET_SIGNING_PASSWORD` repository secrets. A maintainer's machine keeps the same key in `~/.config/herdr-pocket/` (`release.jks`, `signing.password`, and `signing.properties` with `storeFile`, `passwordFile` and `alias`), and local builds use it too, so they update and are updated by the releases. Without that key, local builds are debug-signed. A fork publishes from its own key and sets `pocket.updateUrl` in `gradle.properties` to its own `latest.json`.

Protocol integration tests can use a real core without touching your installed session:

```sh
HERDR_TEST_HC=/absolute/path/to/hc ./gradlew :app:testDebugUnitTest --rerun
```

Tests select unique core sessions and temporary XDG state/config/runtime directories, and stop only those scratch cores. `scripts/ssh_test_host.py` is an optional loopback-only fixture for SSH verification (requires Python Paramiko). It is not needed by the app.

## Current limits

- Pocket Link pairing needs the desktop mod and Node.js 20.3+ on the computer; the bridge runs on macOS (LaunchAgent) and Linux (systemd user service).
- Connections run in the app process; with Watch in background they stay open in a foreground service. If Android stops the service or the phone restarts, open the app and connect again. SSH jump hosts and imported SSH identities are not implemented.
- Linux/macOS SSH hosts are supported. The connection command assumes a Unix shell; Windows hosts need a different launcher.
- Space/tab layout is read from the desktop. This version does not edit, rename or delete that document or run desktop mods.
- **Full color** uses a bundled, offline xterm.js WebView. **Native text**, selectable in Settings, renders the core’s screen snapshot directly in Compose, keeps the message and key controls, and activates automatically if the color renderer fails. Native text has no terminal colors; it keeps up to 500 lines of local shell history and sends scroll events to alternate-screen programs. Navigation, lists, settings, SSH and the core client are native Kotlin. Kitty images and Windows win32-input-mode are not supported.
- A phone attach can resize the same terminal shown on the desktop; herdr-core gives the last active viewer control of its size.
- The phone's private key is stored in Android app-private, no-backup storage. Uninstalling loses it. This prototype does not offer key backup or hardware-backed SSH signing. Backups and device transfers of app data are disabled.

Optional terminal regression checks: install Python Playwright and Chromium, then run `python scripts/test_terminal.py`. These cover zero-height parents, keyboard resizing/input, touch scrolling in history and alternate-screen programs, preserving scroll position on output, returning to the latest output, and pinch-to-resize text.

The terminal assets are already bundled; Node is not needed for an Android build. To refresh those pinned assets, run `scripts/vendor-terminal.sh` with npm available. The script transpiles xterm for older Android WebViews.

## Source map

- `Model.kt`: profiles, terminal records, desktop layout reader, bounded frame codec.
- `CoreClient.kt`: framed JSON-RPC, event subscription and terminal channels with byte-credit flow control.
- `Ssh.kt`: Ed25519 identity, host-key pinning, SSH transport.
- `PocketViewModel.kt`: connections, inventory, selection, terminal lifecycle and ordered input.
- `MainActivity.kt`, `Screens.kt`: native Compose UI and Graphite theme.
- `TerminalScreen.kt`, `assets/terminal/`: offline terminal surface and native key/message controls.
- `KeyInput.kt`, `TerminalModes.kt`: key-row modifiers, cursor-key and paste encoding, and the terminal modes they depend on.

## Attribution

Graphite color values and the Herdr mark come from [herdrdev/herdr-app](https://github.com/herdrdev/herdr-app), Apache-2.0. The wire format follows [herdr-core's API](https://github.com/herdrdev/herdr-core/tree/master/schema). [juanibiapina/herdr-mobile](https://github.com/juanibiapina/herdr-mobile) was consulted as a protocol/SSH reference; this application's native UI and Kotlin protocol implementation were written for this project.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Project code is Apache-2.0; the bundled terminal has its own MIT license.
