---
rfc: "0004"
title: "One repo: android/ and desktop/pocket-link/"
status: Accepted
created: 2026-11-01
pr: "refactor/monorepo (open)"
---
# RFC 0004 — One repo: android/ and desktop/pocket-link/

## Summary

The Android app and the Pocket Link desktop mod live in one repo. The Gradle project moves into
`android/`; the mod is imported from `automagik-dev/automagik-pocket-link` into
`desktop/pocket-link/`. Release versioning, the README, the docs and the CI workflows stay at the
root.

## Motivation

No reason for the move was written down beyond the request itself; see Open questions. For
context only: the app and the mod share a protocol (the `herdr-pocket://pair` link and the iroh
ALPNs, see [RFC 0003](0003-pocket-link-pairing.md)).

## Current state

The `refactor/monorepo` branch makes the move. Layout after it merges:

| Path | What |
|---|---|
| `android/` | Gradle project: `android/gradlew`, `android/app/src/main/java/ai/namastex/automagik/…` |
| `android/scripts/` | `test_terminal.py`, `vendor-terminal.sh`, `ssh_test_host.py` |
| `desktop/pocket-link/` | The mod repo as it was: `README.md`, `RECIPE.md`, `keys.json`, `mods/pocket-link/` |
| `version.properties`, `scripts/version.sh` | Release version, at the root |
| `docs/`, `README.md`, `SECURITY.md`, `THIRD_PARTY_NOTICES.md`, `LICENSE`, `.github/` | Root |

The workflows run Gradle with `working-directory: android`.

## Design

- Build: `cd android && ./gradlew :app:assembleDebug`. The APK lands in
  `android/app/build/outputs/apk/`.
- The planned usage meter would sit beside them as `meter/`
  ([RFC 0005](0005-usage-meter.md)).

## Decisions

- Owner, 2026-11-01: move to one repo with `android/` and `desktop/pocket-link/`, importing
  `automagik-dev/automagik-pocket-link` (the request for the `refactor/monorepo` pull request).

## Alternatives considered

Keeping two repos, as before. Not otherwise discussed in writing.

## Open questions

1. Why one repo? Write down the owner's reason. — the owner.
2. What happens to the `automagik-dev/automagik-pocket-link` repo after the import: archived with
   a pointer here, or kept? — the owner.
3. Do desktop mod changes count as "shipping" commits that cut a new APK release? Today any `feat`
   or `fix` does, whatever folder it touches. — the owner.

## Related

- [RFC 0003 — Pocket Link pairing](0003-pocket-link-pairing.md)
- [Development guide](../guides/development.md)
