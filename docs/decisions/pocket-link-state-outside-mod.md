---
title: "Pocket Link keeps its state outside the mod folder"
type: decision
status: Implemented
created: 2026-11-01
updated: 2026-11-01
pr: "#34"
tags: [decisions, pocket-link, desktop]
---
# Pocket Link keeps its state outside the mod folder

The desktop bridge keeps its key and pairings in `${XDG_STATE_HOME:-~/.local/state}/automagik-pocket-link/`,
so the mod folder can be deleted and copied again without unpairing a phone.

## Decision

- Owner, 2026-11-01: the mod folder must be disposable, and state must live outside it. A tool
  reinstalled the mod with `rm -rf` and a copy, which deleted `mods/pocket-link/.state/`, and with
  it this computer's key: the phone had to pair again.
- The location follows herdr-core, which keeps its state in `~/.local/state/herdr-core/` on macOS
  too, rather than `~/Library/Application Support`.

## Current state

- Where it lives, and the move out of the old folder: `desktop/pocket-link/mods/pocket-link/bridge/state.mjs:23`
  (`stateDir`) and `:81` (`open`), tested in `bridge/state.test.mjs` (CI:
  `.github/workflows/pocket-link.yml`).
  - The folder is mode 0700; every file stays 0600.
  - Another `HERDR_APP_HOME` gets its own folder, `automagik-pocket-link-<hash>/`.
  - `POCKET_LINK_STATE_DIR` overrides both. The login service is given the resolved folder that way,
    because launchd and systemd don't see the dialog's environment
    (`bridge/bridge.mjs:200`, `:221`).
- Moving from `mods/pocket-link/.state/`:
  - When the new folder has no `secret.key`, each old file moves with `link()` then `unlink()`:
    atomic, never over an existing file, the mode kept. One line goes to the log.
  - While an older bridge still runs from `.state/`, that run keeps using `.state/` and moves
    nothing. Moving the phones out from under that bridge would make it refuse them.
  - `.state/` is left empty: a LaunchAgent loaded by an older version still logs there.
- The dialog watches the folder that `bridge.mjs status` names (`state`), not a path in the mod
  (`mods/pocket-link/main.js:47`).
- An existing LaunchAgent or systemd unit gets the new log path and `POCKET_LINK_STATE_DIR` only
  when **Turn on** is pressed again (`bridge.mjs install`). The bridge doesn't rewrite it on its
  own: a test window with another `HERDR_APP_HOME` shares the service label, so a rewrite from
  `serve` could point the live service at the test folder.

## Open questions

1. Should the systemd unit also log to `bridge.log` in the state folder
   (`StandardError=append:`), as the LaunchAgent does? It logs to the journal today. — the owner.
2. Should the login service label include the `HERDR_APP_HOME` hash, so a test window's **Turn on**
   doesn't replace the live service? — the owner.

## Related

- [Pocket Link README](../../desktop/pocket-link/README.md)
- [Pocket Link recipe](../../desktop/pocket-link/RECIPE.md)
