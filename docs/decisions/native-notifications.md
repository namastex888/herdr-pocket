---
title: "Alerts are native Android notifications, in the app too"
type: decision
status: Implemented
created: 2026-11-01
updated: 2026-11-01
pr: "#17"
tags: [decisions, alerts, notifications]
---
# Alerts are native Android notifications, in the app too

An agent that needs you or finishes alerts as an Android notification, even while the app is open.
There is no in-app toast.

## Decision

- Owner, 2026-11-01: no in-app toast at the bottom of the screen; native Android notifications
  instead (PR #17, which opens with "Owner asked").
- This supersedes the in-app sound-and-toast with **Open** from #10 for agent alerts. Update and
  crash notices still use the in-app snackbar.

## Current state

- `android/app/src/main/java/ai/namastex/automagik/PocketViewModel.kt:369-381`: an agent you are
  not looking at posts a notification, in the foreground too. The agent on screen only sounds when
  it needs you. With notifications off, the sound is the alert.
- Two channels: **Needs you** (high importance, pops up over the screen) and **Done**
  (`Notify.kt:33-37`).
- Buttons: Reply, plus Yes/No or up to three answers when the agent asks (`Notify.kt:55-78`).
  A button re-reads the agent and presses keys only if the same question is still on screen.

## Open questions

None.

## Related

- automagik-dev/automagik-pocket #10, #11, #13, #17
- [Lock-screen answering starts off](lock-screen-answers-off.md)
