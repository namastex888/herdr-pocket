---
title: "Updates install silently, and notifications say so"
type: decision
status: Implemented
created: 2026-11-01
updated: 2026-11-01
pr: ""
tags: [decisions, updates, notifications]
---
# Updates install silently, and notifications say so

New versions install on their own in the background, and the person is told afterwards. Native
Android notifications replace the in-app "update available" bar.

## Decision

- Owner, 2026-11-01: "silent": new versions install on their own in the background, and the person
  is told afterwards. The owner dislikes in-app bottom bars and wants native Android notifications.
- This supersedes the update snackbar ("Automagik X is available · Update") from #6.

## Current state

- Checks: on open (at most every 15 minutes) and every 6 hours while the process runs
  (`android/app/src/main/java/ai/namastex/automagik/Updater.kt:121-150`,
  `PocketViewModel.kt:408`).
- Silent or notify, and when installing would interrupt: `UpdatePolicy.kt` (unit tested in
  `UpdatePolicyTest.kt`). Silent needs Android 12+, the switch on, install-apps allowed and an
  unmetered network. It waits while a terminal is open on screen, and on other screens until the
  app has been untouched for 10 minutes. Away from the app, it installs at once.
- The fallback notification "Automagik X is ready · Install", once per version (`Updater.kt:361`),
  and "Updated to X" after the restart (`Updater.kt:376`, woken by `MY_PACKAGE_REPLACED`,
  `Updater.kt:412`). Both are in the low-importance **Updates** channel (`Updater.kt:356`).
- Settings → About → **Install updates automatically**, on by default (`Screens.kt:292`).
- Metered networks are read with `ACCESS_NETWORK_STATE`, a normal permission
  ([privacy](../privacy.md)).

## Reconnecting after an update

- 2026-11-01, closing the open question from #28: after an update the new build restarts Watch
  in background and reconnects the machines it was watching, with no action from the person. If
  watching was off, nothing happens.
- While watching, the app keeps the ids of connected, connecting and retrying machines in the
  preferences (`watched_hosts`,
  `android/app/src/main/java/ai/namastex/automagik/PocketViewModel.kt:367`).
  A machine connected with a typed password isn't kept, because the password isn't.
- `WatchResumeReceiver` (`WatchResume.kt:37`, manifest `AndroidManifest.xml:31`) wakes on
  `MY_PACKAGE_REPLACED`, so it runs for any update source, GitHub or Play. It lives in `src/main`,
  apart from the updater. It starts the watch service and reconnects those machines
  (`PocketViewModel.kt:380`). The decision is unit tested in `WatchResumeTest.kt`.
- Android allows this: `ACTION_MY_PACKAGE_REPLACED` is an exemption from the background
  foreground-service start limit
  ([restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#background-start-restriction-exemptions)).
  Android 15's limit on starting services from `BOOT_COMPLETED` covers other types, not
  `specialUse`, and doesn't name `MY_PACKAGE_REPLACED`
  ([Android 15 changes](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed)).
- No new permission. "Updated to X" no longer says "Open Automagik to reconnect" when the watch
  comes back on its own (`Updater.kt:392`).
- Not covered: a reboot. Restarting after `BOOT_COMPLETED` would reuse the same path, but needs
  the `RECEIVE_BOOT_COMPLETED` permission, which needs an RFC first.

## Open questions

1. Should the switch also allow downloads on a metered network? — the owner.
2. Should Watch in background also come back after the phone restarts (`BOOT_COMPLETED`, a new
   permission)? — the owner.

## Related

- [RFC 0002: release and update channel](../rfcs/0002-release-and-update-channel.md)
- [Alerts are native Android notifications](native-notifications.md)
