---
rfc: "0001"
title: "Identity: Automagik name and colours, app ID, repo home"
status: Implemented
created: 2026-11-01
pr: "#14, #15, #16"
---
# RFC 0001 — Identity: Automagik name and colours, app ID, repo home

## Summary

The app is **Automagik Pocket**: app ID `ai.namastex.automagik`, Automagik Stage colours, released
from `automagik-dev/automagik-pocket`. It used to be "Herdr Pocket" (`dev.herdr.pocket`) in
`namastex888/herdr-pocket`. Copies with the old app ID can't update in place: uninstall, then
install `automagik.apk`.

## Motivation

Recorded after the fact, from three merged pull requests (#14, #15, #16). Their reasons are under
Decisions where the pull request gave one.

## Current state

- App ID and namespace: `android/app/build.gradle.kts:23` and `:25`, `ai.namastex.automagik`.
- Launcher label "Automagik": `android/app/src/main/AndroidManifest.xml:11`.
- Stage colours, light and dark, picked by the phone's setting:
  `android/app/src/main/java/ai/namastex/automagik/Theme.kt:14-35`. The terminal stays dark in
  both modes: `Theme.kt:37`.
- Update feed of installed copies points at the new repo: `android/app/build.gradle.kts:29`.
- Old names still in the code (not user-facing unless noted):
  - URL scheme `herdr-pocket://pair` and iroh ALPNs `herdr-pocket/1`, `herdr-pocket/pair/1`:
    `IrohLink.kt:34`, `:55-56`. These are a protocol shared with the desktop mod.
  - SSH key comment `herdr-pocket@android`: `Ssh.kt:36`.
  - Update request User-Agent `HerdrPocket/<version>`: `Updater.kt:265`.
  - Theme style names `Theme.Herdr`: `android/app/src/main/res/values/styles.xml:3`.
  - The settings text that still said "Graphite colors" and "open Herdr" now names Automagik and
    its light and dark colours (`Screens.kt:183`, `:211`). The SSH host-key repository JSch can
    name in its messages is "Automagik verified hosts" (`Ssh.kt:56`).

## Design

| Thing | Before | Now | PR |
|---|---|---|---|
| Name and icon | Herdr Pocket | Automagik, Stage tile icon with a monochrome layer | #14 |
| Colours | Herdr palette | Automagik Stage, light and dark | #14 |
| App ID | `dev.herdr.pocket` | `ai.namastex.automagik` | #15 |
| Release asset | `herdr-pocket.apk` | `automagik.apk` | #15 |
| Repo | `namastex888/herdr-pocket` | `automagik-dev/automagik-pocket` | #16 |
| Signing key | — | unchanged across all three | #15 |

Migration for people on the old app ID: uninstall it, install `automagik.apk`, pair again (#15).

## Decisions

- Owner, 2026-11-01: rebrand to Automagik with Stage colours, light and dark, and a new icon; the
  app ID stays `dev.herdr.pocket` for this step so it updates in place (PR #14).
- Owner, 2026-11-01: app ID and namespace become `ai.namastex.automagik`, "the same ID as
  Automagik desktop", with the title "ready for Google Play"; same release key; the asset becomes
  `automagik.apk` (PR #15).
- Owner, 2026-11-01: the repo moves to `automagik-dev/automagik-pocket`; installed copies keep
  finding updates because GitHub answers the old `latest.json` URL with a 301 (PR #16).

## Alternatives considered

None written down in the pull requests.

## Open questions

1. Why the rebrand to Automagik and Stage (#14)? No reason was written. — the owner.
2. Why the repo moved to `automagik-dev` (#16)? No reason was written. — the owner.
3. Should the remaining "Herdr" strings in Settings (`Screens.kt:183`, `:211`) and the
   `HerdrPocket` User-Agent change? The URL scheme and ALPNs are a shared protocol and need the
   desktop mod to change with them. — the owner.
4. How long can installed copies rely on GitHub's redirect from the old repo URL? See
   [RFC 0002](0002-release-and-update-channel.md). — the owner.

## Related

- automagik-dev/automagik-pocket #14, #15, #16
- [RFC 0002 — Release and update channel](0002-release-and-update-channel.md)
