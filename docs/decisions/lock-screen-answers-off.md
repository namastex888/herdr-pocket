---
title: "Answering from the lock screen starts off"
type: decision
status: Implemented
created: 2026-11-01
updated: 2026-11-01
pr: "#19"
tags: [decisions, alerts, security, lock-screen]
---
# Answering from the lock screen starts off

**Settings → Alerts → Answer from the lock screen** is off on a new install. Turn it on to read and
answer agents without unlocking.

## Decision

- Owner, 2026-11-01: the setting defaults to off, and Settings explains why (PR #19). Reason in
  the PR and in the setting's text: anyone holding the locked phone could answer.
- **Supersedes** the default of on from PR #11 ("on by default, as you chose", 2026-11-01).
- Owner, 2026-11-01: add `SECURITY.md` with private vulnerability reporting, the release
  certificate fingerprint and what the app can reach (PR #19).

## Current state

- Default off: `android/app/src/main/java/ai/namastex/automagik/PocketViewModel.kt:53`, `:68`
  (pref `lock_answers`).
- Off means: notifications are private on the lock screen (`Notify.kt:83`), and Reply and the
  option buttons ask to unlock (`Notify.kt:67`, `:74`). The side-button panel asks to unlock first
  (`AssistActivity.kt:56`). Fallbacks when the app state is missing are off too (`Notify.kt:118`,
  `:140`).
- Setting text: `Screens.kt:175`.
- [SECURITY.md](../../SECURITY.md) "Lock screen".

## Open questions

None.

## Related

- automagik-dev/automagik-pocket #11, #19
- [Alerts are native notifications](native-notifications.md)
- [Privacy](../privacy.md)
