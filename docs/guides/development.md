---
title: "Development"
type: guide
created: 2026-11-01
updated: 2026-11-01
tags: [guide, build, test, contributors]
---
# Development

Build, test and run Automagik Pocket from source. Releases are in [releasing.md](releasing.md).

## 1. Set up

1. Install JDK 17 (CI uses Temurin 17). Gradle 8.11.1 also runs on JDK 21; it does not run on
   very new JDKs, so point `JAVA_HOME` at 17 or 21 if Android Studio bundles a newer one.
2. Install the Android SDK: platform 35 and build-tools 35.0.0.
3. Tell Gradle where the SDK is:

   ```sh
   printf 'sdk.dir=/absolute/path/to/Android/Sdk\n' > android/local.properties
   ```

`local.properties` is ignored by git. The Gradle wrapper is pinned to its distribution checksum.

## 2. Build, test, lint

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

This is the same gate CI runs. The debug APK is
`android/app/build/outputs/apk/debug/app-debug.apk`.

## 3. Run it

**On a phone:** `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`. Android 8.0
(API 26) or newer.

**On an emulator:**

1. Create a device with an x86_64 or arm64 image, API 26 or newer. 32-bit x86 images won't run
   it: the APK ships only arm64-v8a, armeabi-v7a and x86_64 (`android/app/build.gradle.kts:27`).
2. Install the APK as above.
3. Without a machine, use **Settings → About → Explore the interface with sample data**.
4. To pair, paste the Pocket Link link instead of scanning: the emulator camera can't see a real
   QR code.

A debug build signed with a different key than the installed copy can't replace it. Uninstall
first.

## 4. More tests

**Against a real herdr-core.** Unit tests skip these unless you point them at `hc`:

```sh
cd android
HERDR_TEST_HC=/absolute/path/to/hc ./gradlew :app:testDebugUnitTest --rerun
```

They start their own core sessions with temporary XDG state, config and runtime folders, and stop
only those.

**SSH fixture (optional).** `android/scripts/ssh_test_host.py` runs a loopback-only SSH server
against an isolated core. It needs Python Paramiko and `HERDR_TEST_HC`. The app doesn't need it.

**Terminal page (Playwright).** CI runs it on every pull request:

```sh
python -m pip install playwright && python -m playwright install chromium
python android/scripts/test_terminal.py
```

It covers zero-height parents, keyboard resize and input, touch scrolling in history and in
full-screen programs, keeping the scroll position as output arrives, **↓ Latest**, and pinch to
resize text.

**Terminal assets.** xterm.js is bundled, so an Android build doesn't need Node. To refresh the
pinned assets, run `android/scripts/vendor-terminal.sh` with npm available. It transpiles xterm for
older Android WebViews.

## 5. The release key

Releases are signed with one key. Android only updates an installed copy with an APK signed by the
same key.

- **CI** reads two repository secrets: `POCKET_SIGNING_KEYSTORE` (the PKCS12 keystore, base64) and
  `POCKET_SIGNING_PASSWORD`.
- **A maintainer's machine** keeps the key in `~/.config/herdr-pocket/`: the keystore, a password
  file, and `signing.properties` with `storeFile`, `passwordFile` and `alias` (default `pocket`).
  The environment variables `POCKET_SIGNING_STORE` and `POCKET_SIGNING_PASSWORD` override it
  (`android/app/build.gradle.kts:5-12`).
- With the key present, debug builds are signed with it too, so a build from Android Studio and the
  releases update each other. Without it, builds are debug-signed.
- Never commit a key: `*.jks` and `*.keystore` are in `.gitignore`. Never regenerate the key;
  every installed copy would have to uninstall.
- A fork signs with its own key and sets `pocket.updateUrl` in `android/gradle.properties` to its
  own `latest.json`.

## 6. Where things are

App code, `android/app/src/main/java/ai/namastex/automagik/`:

| File | What |
|---|---|
| `Model.kt` | Machine profiles, terminal records, the desktop layout reader, frame codec |
| `CoreClient.kt` | Framed JSON-RPC to herdr-core, events, terminal channels with flow control |
| `Ssh.kt` | Ed25519 key, host key pinning, SSH transport |
| `IrohLink.kt`, `PairScreens.kt` | Pocket Link pairing and sessions |
| `PocketViewModel.kt` | Connections, inventory, selection, terminal lifecycle, input order |
| `MainActivity.kt`, `Screens.kt`, `ProjectScreens.kt`, `Cockpit.kt`, `Projects.kt`, `Nav.kt`, `Sidebar*.kt` | Compose UI |
| `TerminalScreen.kt`, `assets/terminal/` | Terminal view and its key and message controls |
| `KeyInput.kt`, `TerminalModes.kt` | Key row modifiers, cursor and paste encoding |
| `Notify.kt`, `Alerts.kt`, `Prompt.kt`, `WatchService.kt` | Notifications, buttons, background watch |
| `AssistActivity.kt` | Side-button assistant panel |
| `Updater.kt` | Self-update from GitHub releases |
| `Theme.kt` | Automagik Stage colours, light and dark |

Desktop mod: `desktop/pocket-link/` — see its [README](../../desktop/pocket-link/README.md) and
[RECIPE](../../desktop/pocket-link/RECIPE.md).

## 7. Commits and pull requests

- Work in a branch or worktree, open the pull request into `dev`.
- Every commit subject is conventional: `type(scope): description`. CI rejects others
  (`.github/workflows/commitlint.yml`). The type decides whether a merge cuts a release; see
  [releasing.md](releasing.md).
- A change to a contract, privacy or permissions needs an RFC first:
  [RFC 0000](../rfcs/0000-rfc-process.md).
