---
title: "Pocket Link public-status.json contract (schema 1)"
type: decision
status: Accepted
created: 2026-11-01
updated: 2026-11-01
pr: "#39"
tags: [decisions, pocket-link, desktop, contract]
---
# Pocket Link public-status.json contract (schema 1)

The Pocket Link bridge writes `public-status.json` in its state folder so other Herdr mods can
tell whether a phone is connected. It is a **read-only contract for other mods**. Only the bridge
writes it, and it is the only file in the folder that other mods may read.

## Decision

- Owner, 2026-11-01: Pocket Link publishes whether phones are connected in a small, stable,
  versioned file for other mods, kept apart from `status.json`. It holds no ticket, endpoint id or
  secret, and a clean shutdown writes `running: false`. Requested by Felipe for PR #39, so that the
  Herdr sidebar can put a dot on its Pocket Link icon while a phone is connected.
- Owner, 2026-11-01: `status.json` keeps the pairing ticket and stays 0600, because the dialog's
  QR code is built from it (PR #39).

## Current state

**Where it is.** `public-status.json` in the folder `stateDir` resolves
(`desktop/pocket-link/mods/pocket-link/bridge/state.mjs:23`), the first that applies:

1. `$POCKET_LINK_STATE_DIR`, when set to an absolute path.
2. `${XDG_STATE_HOME:-~/.local/state}/automagik-pocket-link-<hash>/`, when `HERDR_APP_HOME` is set
   to an absolute path other than `~/.config/herdr-app`. `<hash>` is the first 8 hex characters of
   the SHA-256 of the resolved path.
3. `${XDG_STATE_HOME:-~/.local/state}/automagik-pocket-link/`.

The file is mode 0644 and the folder 0700, so only the same user can read it. Every other file in
the folder stays 0600.

**Fields** (`bridge/presence.mjs:42`, `publicStatus`):

```json
{
  "schema": 1,
  "running": true,
  "updated": 1793520000000,
  "phones_connected": 1,
  "phones": [{ "name": "Pixel 9", "since": 1793519400000 }]
}
```

| Field | Type | Means |
|---|---|---|
| `schema` | int | `1`. See Versioning. |
| `running` | bool | The bridge is serving. A clean stop writes `false`. |
| `updated` | epoch ms | When it was written. |
| `phones_connected` | int | Phones with a session open now. A phone with several sessions counts once. |
| `phones` | array | One `{name, since}` per connected phone, oldest first. `name` is the device name the phone sent when it paired; `since` is when its oldest open session began, in epoch ms. |

**When it's written** (`bridge/bridge.mjs`):

- At start, after the signal handlers are in place (`:166`).
- Every 20 seconds (`HEARTBEAT`, `presence.mjs:16`; `bridge.mjs:167`).
- When a session opens or closes. A session counts from its start until the phone hangs up or
  `hc` exits, whichever comes first (`bridge.mjs:129`).
- With `running: false` and no phones on SIGTERM or SIGINT (**Turn off**, `bridge.mjs stop`,
  logout), and when the accept loop ends (`:162`, `:181`).
- Atomically: a temp file, then a rename (`presence.mjs:48`).

**Staleness.** While serving, the bridge rewrites the file at least every 30 seconds. A reader
treats a file whose `updated` is more than 90 seconds old (`STALE`, `presence.mjs:17`) as
`running: false` with no phones: the bridge stopped without saying so, killed or asleep. A
missing or unreadable file means the same. `readPublic` (`presence.mjs:58`) is the reference
reader.

**Versioning.**

- Adding a field keeps `schema: 1`. Readers ignore fields they don't know.
- Removing or renaming a field, or changing its type or meaning, takes `schema: 2`. Readers treat
  any value but 1 as unknown, the same as a missing file.
- This rule was proposed in PR #39 with the contract.

**Never in it:** the pairing ticket, this computer's endpoint id, a phone's key, or any secret.
`publicStatus` copies only `name` and `since` from each phone. A test checks the exact field set
(`bridge/presence.test.mjs`, CI `.github/workflows/pocket-link.yml`).

**The dialog** ignores changes that only touch this file, so the 20-second rewrite doesn't reload
it (`mods/pocket-link/main.js:47`, `:51`).

Documented for mod authors in the [Pocket Link README](../../desktop/pocket-link/README.md#for-other-mods).

## Open questions

1. Should another Herdr home's folder hash (`automagik-pocket-link-<hash>/`) be published
   somewhere a mod can read, rather than each mod repeating `stateDir`'s rule? — the owner.

## Related

- automagik-dev/automagik-pocket #39
- [Pocket Link keeps its state outside the mod folder](pocket-link-state-outside-mod.md)
- [Pocket Link README, For other mods](../../desktop/pocket-link/README.md#for-other-mods)
- [Pocket Link recipe](../../desktop/pocket-link/RECIPE.md)
