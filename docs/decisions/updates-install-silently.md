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
  `PocketViewModel.kt:390`).
- Silent or notify, and when installing would interrupt: `UpdatePolicy.kt` (unit tested in
  `UpdatePolicyTest.kt`). Silent needs Android 12+, the switch on, install-apps allowed and an
  unmetered network. It waits while a terminal is open on screen, and on other screens until the
  app has been untouched for 10 minutes. Away from the app, it installs at once.
- The fallback notification "Automagik X is ready · Install", once per version (`Updater.kt:361`),
  and "Updated to X" after the restart (`Updater.kt:376`, woken by `MY_PACKAGE_REPLACED`,
  `Updater.kt:410`). Both are in the low-importance **Updates** channel (`Updater.kt:356`).
- Settings → About → **Install updates automatically**, on by default (`Screens.kt:292`).
- Metered networks are read with `ACCESS_NETWORK_STATE`, a normal permission
  ([privacy](../privacy.md)).

## Open questions

1. After a silent update, connections stay closed until the app is opened. Should the new build
   start the watch service and reconnect on its own (`MY_PACKAGE_REPLACED` allows starting it)? —
   the owner.
2. Should the switch also allow downloads on a metered network? — the owner.

## Related

- [RFC 0002: release and update channel](../rfcs/0002-release-and-update-channel.md)
- [Alerts are native Android notifications](native-notifications.md)
