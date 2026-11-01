---
rfc: "0003"
title: "Pocket Link: pair with Herdr desktop over iroh, no SSH"
status: Implemented
created: 2026-11-01
pr: "#4"
---
# RFC 0003 — Pocket Link: pair with Herdr desktop over iroh, no SSH

## Summary

The phone pairs with Herdr desktop by scanning a QR code from the Pocket Link desktop mod. After
that it reaches the computer's herdr-core over iroh: dialled by public key, direct when possible,
otherwise through n0's public relays, end-to-end encrypted either way. No SSH, password or open
port. SSH stays as the other way to connect.

## Motivation

Recorded after the fact from #4. Its stated goal: pair "like a TV pairs with a streaming account.
No SSH, no password, no open port."

## Current state

Phone (`android/app/src/main/java/ai/namastex/automagik/`):

- Pairing link `herdr-pocket://pair?v=1&n=<desktop>&c=<code>&t=<ticket>`, parsed at
  `IrohLink.kt:27-46`. Only `v=1`, a valid `endpoint…` ticket and a 16-64 character code pass. The
  desktop name loses control characters and is cut to 60 characters (`:42`).
- The phone's iroh key: generated once, kept in no-backup storage (`IrohLink.kt:63-64`).
- ALPNs: `herdr-pocket/pair/1` to pair, `herdr-pocket/1` for sessions (`IrohLink.kt:55-56`). A
  session sends `{"session": …}` and then speaks the herdr-core protocol through the same
  `CoreClient` as SSH (`IrohLink.kt:97`).
- Pairing sends the code and the phone's make and model (`IrohLink.kt:83`,
  `PocketViewModel.kt:254`).
- QR scanning: bundled ZXing scanner (`android/app/build.gradle.kts:65`). Deep links open the app
  through the `herdr-pocket://pair` intent filter (`AndroidManifest.xml:15`).
- iroh library: `android/app/build.gradle.kts:63`. ABIs kept: arm64-v8a, armeabi-v7a, x86_64
  (`:27`); native libraries compressed (`:45`).

Desktop (`desktop/pocket-link/mods/pocket-link/bridge/bridge.mjs`):

- A code lasts 10 minutes by default (1 to 60 allowed) (`:158-163`) and works once.
- Five wrong tries end the code (`:79`).
- Removing a phone ends its open session (`:98`).

## Design

```
desktop mod ──QR: ticket + one-time code──▶ phone
phone ──iroh, ALPN herdr-pocket/pair/1: {code, device}──▶ bridge ──▶ paired list
phone ──iroh, ALPN herdr-pocket/1: {session}──▶ bridge ──▶ hc connect ──▶ herdr-core
```

Paired desktops sit in the machine list next to SSH machines.

## Decisions

- Owner, 2026-10-31: pair with Herdr desktop over iroh through the Pocket Link mod, with no SSH,
  password or open port (PR #4).
- Owner, 2026-10-31: scan QR codes with the bundled ZXing scanner. Reason in the PR: it works
  without Google Play services, and Google's code scanner couldn't download its module on the
  emulator (PR #4).
- Owner, 2026-10-31: ship only arm64-v8a, armeabi-v7a and x86_64 and compress the native
  libraries. Reason in the PR: the APK went from 73 MB to 40 MB (PR #4).

## Alternatives considered

SSH remains supported. No other transport was written down as considered.

## Open questions

1. Why iroh rather than another way through NAT? No reason was written. — the owner.
2. The scheme and ALPNs still say `herdr-pocket`. Rename them with the desktop mod, or keep them
   as a stable protocol name? — the owner.
3. #4 lists "connecting paired machines automatically at launch" as not done. Still wanted? —
   the owner.

## Related

- automagik-dev/automagik-pocket #4
- Desktop mod: [`desktop/pocket-link/`](../../desktop/pocket-link/README.md)
- [Privacy](../privacy.md)
