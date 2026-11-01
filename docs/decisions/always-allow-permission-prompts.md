---
title: "Offer Always allow for agent permission prompts"
type: decision
status: Accepted
created: 2026-11-01
updated: 2026-11-01
pr: "#41"
tags: [decisions, alerts, notifications, terminal]
---
# Offer Always allow for agent permission prompts

When a coding agent's permission prompt offers "don't ask again", the phone offers it as one tap:
**Always allow** in the answer row above the terminal, **Always** in the notification, between
Yes and No.

## Decision

- Owner, 2026-11-01: permission buttons offer Yes and No only, never "always allow" or "switch to
  auto mode", which "change settings beyond this one prompt" (PR #13, kept in PR #24).
  **Superseded** for "don't ask again" options by the next line. Mode changes stay excluded.
- Owner, 2026-11-01: when a permission prompt offers a "don't ask again" or always option, the
  person can pick it in one tap, in the terminal's answer row and in the notification, ordered
  Yes / Always / No. Notification actions keep the existing lock-screen rules. Requested by Felipe
  for PR #41, answering PR #24's "Say if you want them in-app".

## Current state

What counts as "always": `android/app/src/main/java/ai/namastex/automagik/Prompt.kt:67`
(`isAlways`), and the buttons come from `:85` (`choices`).

- An option counts when it starts with "Yes" and says "don't ask again" (straight or curly
  apostrophe), "always allow" or "in the future". Examples:
  - Claude Code: "Yes, and don't ask again for … commands in …", "Yes, and always allow access to …".
  - Codex: "Yes, and don't ask again for commands that start with …", "… for this command in this
    session", "Yes, and allow this host in the future".
- **Mode changes never count.** Anything that mentions a mode is out, so "Yes, and switch to auto
  mode" stays off. Claude Code's "Yes, allow all edits during this session (shift+tab)" is out as
  well: it switches the session's permission mode rather than allowing one command again.
- Without both a Yes and a No, there are still no buttons. With two always options, the first is
  offered.
- **One option, exactly.** A press sends the arrow keys from the cursor to that option's index,
  then Enter, and only after re-reading the screen and finding the same menu
  (`PocketViewModel.kt:438`, `choose`, unchanged).
- **Terminal row:** `TerminalScreen.kt:198` draws it as "N. Always allow" (`Prompt.kt:16`).
- **Notification:**
  - Actions Yes / Always / No (`Notify.kt:45`, `titles`).
  - **The lock-screen rule is shared with Yes and No.** Always is built in the same loop, with the
    same `setAuthenticationRequired(!lockScreen)` (`Notify.kt:67`). With **Answer from the lock
    screen** off, the default, it asks to unlock first, and the text stays private on a secure lock
    screen. See [Answering from the lock screen starts off](lock-screen-answers-off.md).
  - **The scope is shown.** "Always" alone doesn't say what it allows, so the text adds a line such
    as "Always: don't ask again for npm test commands in this project" (`Notify.kt:77`,
    `Prompt.scope` at `Prompt.kt:70`).
  - **Reply is dropped then.** Android shows at most three actions, so when Always is there, Reply
    goes and tapping the notification opens the agent to type (`Notify.kt:86`). No is kept over
    Reply because it is the safe answer. Permissions without an always option still show
    Yes / No / Reply.
- Tests: `PromptTest`, on synthetic screens shaped like Claude Code's and Codex's prompts.
- User guide: [Using Automagik Pocket](../guides/using.md), Alerts.

Excluding mode changes, showing the scope, and keeping No over Reply were proposed in PR #41 to
meet the request's "decide the best 3 and explain". They are not separate owner decisions.

## Open questions

1. Should Always ask to unlock even with **Answer from the lock screen** on, since it allows more
   than one prompt? Today it follows the same rule as Yes and No. — the owner.
2. Does herdr-core report Codex's approvals as `kind: "permission"`? If not, Codex shows no
   buttons. Not checked on a live Codex session. — whoever next runs Codex with approvals on.

## Related

- automagik-dev/automagik-pocket #13, #24, #41
- [Answering from the lock screen starts off](lock-screen-answers-off.md)
- [Using Automagik Pocket](../guides/using.md)
