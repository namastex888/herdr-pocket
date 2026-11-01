---
title: "Google Play listing and review notes"
type: reference
created: 2026-11-01
updated: 2026-11-01
tags: [play, listing, review]
---
# Google Play listing and review notes

Drafts for the Play Console, for the **play** flavor (`bundlePlayRelease`). The owner reviews and
enters them; nothing here is submitted. Code paths are under
`android/app/src/main/` unless they say otherwise.

## Store listing

**App name:** Automagik Pocket (launcher label "Automagik", `AndroidManifest.xml`).

**Short description** (80 characters at most):

> Watch and answer your coding agents from your phone, on your own machines.

**Full description:**

> Automagik Pocket is the phone companion for herdr-core, the terminal workspace that runs your
> coding agents (Claude Code, Codex and others) on your own computers.
>
> Pair it with your desktop by scanning a QR code, or connect over SSH. Then, from your phone:
>
> • See every agent at a glance: which ones need you, which are working, which are done.
> • Open any terminal in full color, type, or answer an agent's menu with one tap.
> • Get a notification when an agent asks for approval or finishes, and reply from it.
> • Browse your spaces and tabs, projects and branches, the same way the desktop shows them.
> • Hold the side button to open a quick panel over any screen, and type or speak a reply.
> • See the tokens and estimated spend per project, if you run the usage meter.
>
> Your terminals never pass through our servers: we run none. The app talks to your own
> machines over SSH, or over Pocket Link. Pocket Link is end-to-end encrypted and uses iroh's
> public relays and discovery server, run by n0. They can't read your terminals, but they see
> your phone's Pocket Link ID, IP address and connection times. No account, no ads, no
> analytics.
>
> You need a computer running herdr-core. To look around first, open the app and choose
> "Explore the interface" to see it with sample data.

**Category suggestion:** Tools (alternative: Productivity).

**Contact and privacy policy:** issues on the GitHub repo;
`https://github.com/automagik-dev/automagik-pocket/blob/dev/docs/privacy.md`.

## Features (for screenshots and the listing)

| Feature | Where in the app | Source |
|---|---|---|
| Today: agents that need you, working, done | Today tab | `Cockpit.kt`, `MainActivity.kt` |
| Spaces and tabs like the desktop | Today → a space | `Sidebar.kt`, `SidebarList.kt` |
| Projects and tasks (repos and branches) | Projects, Tasks tabs | `Projects.kt`, `ProjectScreens.kt` |
| Full-color terminal, keys, composer, menu buttons | Open a terminal | `TerminalScreen.kt`, `Prompt.kt` |
| Notifications with Reply and option buttons | Android notifications | `Notify.kt` |
| Watch in background | Settings → Alerts | `WatchService.kt` |
| Side-button assistant panel with voice input | Settings → Side button assistant | `AssistActivity.kt` |
| Usage and spend | Today, Projects, Tasks | `Spend.kt`, `SpendScreens.kt` |
| Pairing by QR (Pocket Link) or SSH | Welcome screen, Machines tab | `PairScreens.kt`, `IrohLink.kt`, `Ssh.kt` |

## App access (for reviewers)

**The app needs the user's own machine.** It connects only to a computer running herdr-core that
the user controls, paired by Pocket Link QR (Herdr desktop mod) or reached over SSH. Reviewers
won't have one, and there is no developer server, account or test login to give them. The app
has a demo mode with sample data instead; it needs no account and no network.

**Steps to give reviewers** (both paths checked in code):

1. Open the app. On the first screen, tap **Explore the interface**
   (`MainActivity.kt`, `WelcomeScreen`, `onPreview = vm::enterDemo`).
2. Or, from anywhere: tap the **gear** icon in the top bar → **Settings** → scroll to **About** →
   **Explore the interface with sample data** (`Screens.kt`, Settings screen, `vm::enterDemo`).
3. A banner "Interface preview · sample data" shows; **Exit** leaves the demo
   (`MainActivity.kt`, `state.demo`).
4. On Android 13 and newer, Android may ask once whether Automagik may send notifications: the
   sample machine counts as connected, which is when the app asks (`MainActivity.kt`,
   `POST_NOTIFICATIONS`). Either answer works; the demo posts none.

**What demo mode covers** (`PocketViewModel.kt`, `enterDemo`):

- The tabs Today, Projects, Tasks and Machines, filled with one sample machine, three spaces,
  six agent terminals and five repositories.
- The cockpit: agents that need you, are working, or are done.
- The terminal view with sample output, its keys and composer (sending is disabled).
- An agent's option buttons: the sample "Authentication flow" terminal waits for approval, and its
  menu buttons answer with sample results.
- Sample spend numbers (`SpendDemo.kt`).

**What demo mode does not cover:**

- Real remote connections: pairing by Pocket Link or connecting over SSH.
- The background `WatchService` foreground service (demo mode never starts it,
  `PocketViewModel.kt`, the `watch`/`demo` checks).
- Live notifications from real agents.
- The side-button assistant panel against real machines.

Suggested App access text:

> Automagik Pocket connects only to the user's own computer running herdr-core (paired by QR code
> or reached over SSH). There is no account, login or developer server. To review the interface
> without one, open the app and tap "Explore the interface" on the first screen, or tap the gear
> icon → Settings → About → "Explore the interface with sample data". The demo shows sample agents,
> terminals, projects and option buttons. It does not open real connections, run the background
> service, or post notifications; those need a paired computer.

## Sensitive permissions and declarations

### Foreground service: `specialUse` (`WatchService`)

- Declared: `android:foregroundServiceType="specialUse"` and the permission
  `FOREGROUND_SERVICE_SPECIAL_USE` (`AndroidManifest.xml`).
- Subtype text, from the manifest property `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`:

  > Keeps the user's connections to their own computers open so a coding agent that needs them
  > or finishes reaches them as a notification they can reply to.

- When it runs: only while **Settings → Alerts → Watch in background** is on and at least one
  machine is connected. It starts from the foreground. It also restarts after an app update
  (from Play or GitHub), when the app was watching before (`WatchResumeReceiver`,
  `MY_PACKAGE_REPLACED`, `WatchResume.kt`). It shows an ongoing "Watching N machines"
  notification with a **Stop** action (`WatchService.kt`, `Screens.kt`).
- Why no other type fits: it holds open SSH/iroh sessions to the user's own machines to receive
  agent events; it isn't data sync, media, location or a phone call.
- **Demo video: PENDING (owner-recorded).** Play asks for a video link for each declared
  foreground-service feature
  ([Play help](https://support.google.com/googleplay/android-developer/answer/13392821)). Not
  recorded yet.

Shot list for that video (about 60 seconds, a test machine and a throwaway agent session only, no
personal sessions, real names or hostnames on screen):

1. The app connected to a test machine; open **Settings → Alerts**, turn on **Watch in
   background**.
2. Go to the home screen. Pull down the shade: the "Watching 1 machine" notification.
3. On the test machine, start an agent that asks for approval.
4. The phone shows "Needs you"; tap **Reply** or an option button from the notification.
5. Show the agent continuing on the test machine.
6. Tap **Stop** on the "Watching" notification; it disappears.

### `RECORD_AUDIO`

Used only when the user taps the microphone in the side-button assistant panel. Android's
`SpeechRecognizer` hands the audio to the phone's default speech service and returns text; the
app never records or stores audio (`AssistActivity.kt`, `Dictate`). The permission is asked when
the user sets up the assistant in Settings (`Screens.kt`, `AssistantRow`).

### `CAMERA` (from the ZXing scanner library)

Added by `com.journeyapps:zxing-android-embedded` (`android/app/build.gradle.kts`). Used only
while the user scans a Pocket Link QR code to pair; frames are decoded on the phone and nothing is
stored or sent (`MainActivity.kt`, `ScanContract`).

### `POST_NOTIFICATIONS`

Alerts when an agent needs the user or finishes, with Reply and option buttons, and the "Watching"
notification of the foreground service. Asked once, when a machine first connects
(`MainActivity.kt`, `Notify.kt`).

### Assistant role

`AssistActivity` handles `android.intent.action.ASSIST`, so the user can choose Automagik as the
digital assistant app (Android settings; the app opens them from **Settings → Side button
assistant**). Holding the side button then opens a panel with agents that need the user and a
message box. It shows over the lock screen; **Answer from the lock screen** is off by default, and
while off, the panel asks to unlock first (`AssistActivity.kt`,
[decision](../decisions/lock-screen-answers-off.md)).

### Not in the play build

`REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, and the self-updater with
its "Updates" notifications, are in the GitHub build only
([RFC 0006](../rfcs/0006-distribution-flavors.md)).

### `ACCESS_NETWORK_STATE` (normal permission, both builds)

Pocket Link's iroh library reads the phone's networks and DNS servers through Android's
`ConnectivityManager` to find a path to the user's machine (`IrohLink.kt` installs the Android
context). No runtime prompt; the permission itself sends nothing. What Pocket Link sends to n0's
relays and discovery server is in the [Data safety draft](data-safety.md).

## Native code and 16 KB pages

Play requires 16 KB page support for apps targeting Android 15 and higher; from 2027-02-01,
updates without it can't be released
([page sizes](https://developer.android.com/guide/practices/page-sizes)). The play bundle stores
native libraries uncompressed and 16 KB-aligned in the APKs Play generates. Each ABI
(arm64-v8a, armeabi-v7a, x86_64) has three libraries:

| Library | From | 16 KB ELF alignment |
|---|---|---|
| `libiroh_ffi.so` | `computer.iroh:iroh-android` 1.2.0 | passes |
| `libandroidx.graphics.path.so` | `androidx.graphics:graphics-path` 1.0.1 (Compose) | passes |
| `libjnidispatch.so` | JNA 5.17.0, pinned over the 5.15.0 that iroh-android 1.2.0 asks for (#36) | passes |

JNA 5.15.0's library was not 16 KB-ready (x86_64 4 KB-aligned; arm64-v8a's RELRO shared a 16 KB
page with writable data). #36 pins JNA 5.17.0, whose changelog fixes it ("Fix calls to jnidispatch
on Android with 16KB page size", JNA #1618 and #1647). The per-library check results are in the
pull request that added this file.

## Related

- [Data safety draft](data-safety.md)
- [RFC 0006 — Distribution flavors](../rfcs/0006-distribution-flavors.md)
- [Privacy policy](../privacy.md)
