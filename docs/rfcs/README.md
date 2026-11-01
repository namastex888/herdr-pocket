---
title: "RFCs"
type: moc
created: 2026-11-01
updated: 2026-11-01
tags: [moc, rfcs]
---
# RFCs

Proposals for changes that others rely on. How to write one: [RFC 0000](0000-rfc-process.md).

## In short

- **Write one** for a contract (update feed, pairing, wire protocol, signing key, app ID), privacy
  or permissions, a new component, or reversing a decision. Not for fixes, refactors or docs.
- **Start** from [`_Templates/rfc.md`](../_Templates/rfc.md), take the next number, add a row
  below, open a pull request into `dev`.
- **Status:** Draft → Accepted (dated owner line) → Implemented (merged), or Superseded /
  Withdrawn.

## Index

| RFC | Title | Status | PR |
|---|---|---|---|
| [0000](0000-rfc-process.md) | How this repo proposes and records changes | Draft | #21 |
| [0001](0001-identity.md) | Identity: Automagik name and colours, app ID, repo home | Implemented | #14, #15, #16 |
| [0002](0002-release-and-update-channel.md) | Release and update channel: v0.N.0 per merge to dev, latest.json | Implemented | #6, #18 |
| [0003](0003-pocket-link-pairing.md) | Pocket Link: pair with Herdr desktop over iroh, no SSH | Implemented | #4 |
| [0004](0004-monorepo.md) | One repo: android/ and desktop/pocket-link/ | Implemented | #20 |
| [0005](0005-usage-meter.md) | Usage meter: token usage per machine as ae/1 rows, a summary doc in herdr-core | Draft | — |
| [0006](0006-distribution-flavors.md) | Distribution flavors: github (self-updating APK) and play (Google Play bundle) | Draft | — |

Back to the [docs home](../_index.md).
