---
rfc: "0004"
title: "One repo: android/ and desktop/pocket-link/"
status: Implemented
created: 2026-11-01
pr: "#20"
---
# RFC 0004 — One repo: android/ and desktop/pocket-link/

## Summary

This repo is the Automagik Pocket product monorepo. The Android app is in `android/`, the Pocket
Link desktop mod is in `desktop/pocket-link/` with its git history, and the product version, docs
and CI stay at the root. Build with `cd android && ./gradlew …`.

## Motivation

Recorded after the fact from #20. The PR describes what moved, not why; see Open questions. For
context only: the app and the mod share a protocol (the `herdr-pocket://pair` link and the iroh
ALPNs, see [RFC 0003](0003-pocket-link-pairing.md)).

## Current state

| Path | What |
|---|---|
| `android/` | Gradle project: `gradlew`, `settings.gradle.kts`, `gradle.properties`, `app/` |
| `android/scripts/` | `test_terminal.py`, `vendor-terminal.sh`, `ssh_test_host.py` |
| `desktop/pocket-link/` | The mod repo, imported with its history: `README.md`, `RECIPE.md`, `keys.json`, `mods/pocket-link/` |
| `version.properties`, `scripts/version.sh` | Product version, at the root |
| `docs/`, `README.md`, `SECURITY.md`, `THIRD_PARTY_NOTICES.md`, `LICENSE`, `.github/` | Root |

- The app reads the root version: `android/app/build.gradle.kts:20`
  (`rootProject.file("../version.properties")`).
- CI runs Gradle with `working-directory: android`: `.github/workflows/android.yml:37`,
  `.github/workflows/release.yml:63`, `:117`.
- Commit linting walks first-parent commits only, so the mod's imported pre-convention commits
  are not checked: `.github/workflows/commitlint.yml:30`.
- Pairing and wire identifiers did not change (`herdr-pocket://pair?v=1`, `herdr-pocket/1`,
  `~/.config/herdr-pocket`).
- The mod has no CI or tests of its own (`desktop/pocket-link/mods/pocket-link/bridge/package.json`
  declares no scripts).

## Design

- One product version for the app and the mod.
- Any shipping commit cuts a release, whichever folder it touches. #20 notes that a mod-only change
  cuts a new APK release too.
- The planned usage meter would sit beside them as `meter/` ([RFC 0005](0005-usage-meter.md)).

## Decisions

- Owner, 2026-11-01: make this repo the product monorepo, with the app in `android/` and Pocket
  Link imported into `desktop/pocket-link/` with its history; merge with a merge commit so that
  history survives (PR #20).
- Owner, 2026-11-01: commit linting checks only the pull request's first-parent commits (PR #20).

## Alternatives considered

Keeping two repos, as before. Not otherwise discussed in writing.

## Open questions

1. Why one repo? Write down the owner's reason. — the owner.
2. What happens to the `automagik-dev/automagik-pocket-link` repo now: archived with a pointer
   here, or kept? — the owner.
3. Should mod-only changes cut an APK release, or should `release.yml` filter by path? — the owner.
4. When the mod gets checks, a workflow with `paths: ['desktop/pocket-link/**']` is the suggestion
   in #20. — the owner.

## Related

- automagik-dev/automagik-pocket #20
- [RFC 0003 — Pocket Link pairing](0003-pocket-link-pairing.md)
- [Development guide](../guides/development.md)
