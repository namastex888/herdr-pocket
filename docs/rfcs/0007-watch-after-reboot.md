---
rfc: "0007"
title: "Watch in background comes back after a phone restart (RECEIVE_BOOT_COMPLETED)"
status: Accepted
created: 2026-11-01
pr: ""
---
# RFC 0007 — Watch in background comes back after a phone restart

## Summary

After the phone restarts and is unlocked once, the app restarts **Watch in background** and
reconnects the machines it was watching, the same way it already does after an update. This adds
one permission, `RECEIVE_BOOT_COMPLETED`, a normal permission granted at install. Nothing new is
collected or sent.

## Motivation

Today a restart ends the watch, and agent alerts stop until the person opens the app again
([using](../guides/using.md), "Limits"). People restart phones for system updates and overnight
battery tools, so alerts stop without warning. The update case was fixed in #32; the restart case
was left as an open question in
[updates install silently](../decisions/updates-install-silently.md) because it needs a new
permission, and [AGENTS.md](../../AGENTS.md) requires an RFC first.

## Current state

Before this RFC, on `dev`:

- While watching, the app keeps the ids of connected, connecting and retrying machines in the
  preferences as `watched_hosts`, leaving out typed-password machines and the demo
  (`android/app/src/main/java/ai/namastex/automagik/PocketViewModel.kt:367`,
  `WatchResume.kt:18`).
- `WatchResumeReceiver` wakes on `MY_PACKAGE_REPLACED` only (`WatchResume.kt:37-43`, manifest
  `AndroidManifest.xml:38-41`). If watching was on and something was watched, it calls
  `resumeWatching()` (`PocketViewModel.kt:380`), which starts `WatchService` and reconnects those
  machines with the usual backoff, up to a minute between tries (`PocketViewModel.kt:345`).
- `WatchService` is a `specialUse` foreground service (`AndroidManifest.xml:35`).
- No receiver listens for `BOOT_COMPLETED`, and the app doesn't hold `RECEIVE_BOOT_COMPLETED`.

## Design

| Piece | Change |
|---|---|
| Permission | `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />` in `src/main/AndroidManifest.xml` |
| Receiver | `WatchResumeReceiver`'s intent filter gets a second action, `android.intent.action.BOOT_COMPLETED`; still `exported="false"` |
| Decision | `WatchResume.ACTIONS` = `MY_PACKAGE_REPLACED`, `BOOT_COMPLETED`; `WatchResume.resumes(action, watch, watched)` is true only for those actions, with watching on and at least one machine watched |
| Work | Unchanged: `resumeWatching()` starts the service and reconnects. The receiver does nothing slow itself, so it needs no `goAsync()` |

Rules, the same as after an update:

- Only if **Watch in background** was on, and only the machines in `watched_hosts`.
- Never a machine connected with a typed password (the password isn't stored) and never the demo.
- Watching off, or nothing watched: the receiver returns, and no service starts.

Everything is in `src/main`, so both distribution flavors from
[RFC 0006](https://github.com/automagik-dev/automagik-pocket/pull/31) (`github`, `play`) get it.

If the network isn't up yet when the broadcast arrives, the first connection fails and the existing
backoff retries it; the service stays on "Reconnecting…" meanwhile, as after an update.

### What Android allows

- **The permission is normal.** "Allows an application to receive the `Intent.ACTION_BOOT_COMPLETED`
  that is broadcast after the system finishes booting … Protection level: normal"
  ([Manifest.permission](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_BOOT_COMPLETED)).
  Granted at install, no prompt. The same page warns it "can have a negative impact on the user
  experience by increasing the amount of time it takes the system to start and allowing
  applications to have themselves running without the user being aware of them". Here the work
  is one service start, only when the person turned watching on, and the service shows its
  ongoing "Watching" notification with **Stop**.
- **Starting the service from the background is allowed.** The background start limit has an
  exemption for "After the device reboots and receives the `ACTION_BOOT_COMPLETED`,
  `ACTION_LOCKED_BOOT_COMPLETED`, or `ACTION_MY_PACKAGE_REPLACED` intent action in a broadcast
  receiver"
  ([restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#background-start-restriction-exemptions)).
- **`specialUse` isn't one of the types blocked from boot.** "`BOOT_COMPLETED` receivers are not
  allowed to launch the following types of foreground services": `dataSync`, `camera`,
  `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone`; trying throws
  `ForegroundServiceStartNotAllowedException`
  ([Android 15 changes](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed)).
  The restrictions page says the limit applies to apps that target Android 14 or higher; this app
  targets 35, and `specialUse` is not listed either way.
- **Wait for the unlock: `BOOT_COMPLETED`, not `LOCKED_BOOT_COMPLETED`.** "Upon receipt of this
  broadcast, the user is unlocked and both device-protected and credential-protected storage can
  [be] accessed safely"
  ([Intent](https://developer.android.com/reference/android/content/Intent#ACTION_BOOT_COMPLETED)).
  The preferences and SSH keys are in credential-encrypted storage, which "is available after the
  user has successfully unlocked the device"
  ([Direct Boot](https://developer.android.com/privacy-and-security/direct-boot)). The receiver is
  not `directBootAware`, so the watch starts after the first unlock, not at the lock screen.
- **A force-stopped app stays stopped.** The stopped state is meant to last "until the user
  explicitly removes the app from this state by directly launching the app or indirectly
  interacting with the app", and on Android 15 "when the user's actions remove the app from the
  stopped state, the `ACTION_BOOT_COMPLETED` broadcast is delivered to the app"
  ([Android 15, all apps](https://developer.android.com/about/versions/15/behavior-changes-all#enhanced-stop-states);
  the `Intent` reference says the same). So after **Force stop**, a restart does not bring the
  watch back; the next time the person opens the app, `BOOT_COMPLETED` arrives while the app is
  starting. `resumeWatching()` skips machines whose connection is already running
  (`PocketViewModel.kt:384`), so that second path is harmless.
- **Only the system sends it.** "This is a protected intent that can only be sent by the system"
  ([Intent](https://developer.android.com/reference/android/content/Intent#ACTION_BOOT_COMPLETED)).
  Another app can't wake the receiver with it, and the receiver stays unexported.

### Google Play

- `RECEIVE_BOOT_COMPLETED` is a normal permission. The Play foreground-service declaration asks,
  per type, for the functionality, the user impact if deferred or interrupted, a video and a use
  case; it has no field for what starts the service
  ([Play help](https://support.google.com/googleplay/android-developer/answer/13392821)). No new
  declaration.
- The existing `specialUse` declaration gains a start trigger. #31's `docs/play/listing.md` says
  the service is "only started from the foreground"; that is already untrue since #32 (after an
  update) and, with this RFC, after a restart. Follow-up for #31: say it starts from the
  foreground, after an app update, and after a phone restart, each only while watching is on.

### Privacy

Nothing new is collected, stored or sent. The same `watched_hosts` ids, already kept for updates,
are read once at boot. [privacy](../privacy.md) lists the new permission.

## Decisions

- Owner, 2026-11-01: approved adding `RECEIVE_BOOT_COMPLETED` so Watch in background comes back
  after a phone restart (in the task for this pull request).

## Alternatives considered

- **WorkManager.** It can't start on its own after a reboot without the same permission:
  WorkManager itself declares `RECEIVE_BOOT_COMPLETED` to reschedule work. A worker would also have
  to start the foreground service from a background job, which the boot exemption doesn't cover.
  More moving parts for the same result.
- **Do nothing.** Alerts stay off after every restart until the person opens the app, with no sign
  that they stopped. The "Limits" note in the guide is the only warning.
- **`LOCKED_BOOT_COMPLETED`.** Earlier, but the preferences and keys can't be read before the first
  unlock.

## Open questions

1. Samsung and other OEM battery managers can keep boot receivers from running ("Sleeping apps",
   "Deep sleeping apps"). Should the app or the guide point the person to allow it? — the owner.

## Related

- #32: after an update, the same resume path.
- [Updates install silently](../decisions/updates-install-silently.md): the decision note whose
  open question this answers.
- [RFC 0006](https://github.com/automagik-dev/automagik-pocket/pull/31): distribution flavors
  (open pull request).
