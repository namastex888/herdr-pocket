---
title: "Automagik Pocket docs"
type: moc
created: 2026-11-01
updated: 2026-11-01
tags: [moc, pocket, docs]
aliases: ["docs", "Pocket docs"]
---
# Automagik Pocket docs

This folder is the documentation for people and agents, and an Obsidian vault that Brain can index
(`brain.json` names `brn_pocket`). Open it in Obsidian, or read it as plain Markdown on GitHub.
Rules for agents working in this repo: [AGENTS.md](../AGENTS.md).

## Use the app

- [README](../README.md): install and connect.
- [Using Automagik Pocket](guides/using.md): tabs (Today, Projects, Tasks, Machines), terminals, alerts, assistant, limits.
- [Privacy policy](privacy.md): what the app handles and where it goes.
- [Security](../SECURITY.md): report a vulnerability, check an APK.

## Work on the app

- [Development](guides/development.md): build, test, emulator, release key.
- [Releasing](guides/releasing.md): how a merge to `dev` becomes a release.
- [Pocket Link desktop mod](../desktop/pocket-link/README.md) and how it's built:
  [RECIPE](../desktop/pocket-link/RECIPE.md).

## Plans and decisions

- [RFCs](rfcs/README.md): proposals and the record of shipped changes. Process:
  [RFC 0000](rfcs/0000-rfc-process.md).
- [Decisions](decisions/_index.md): single choices with their reason.
- [Templates](_Templates/): [rfc](_Templates/rfc.md), [decision](_Templates/decision.md).

## Conventions

- Every note starts with YAML frontmatter: `title`, `type`, `created`, `updated`, `tags`. RFCs use
  `rfc`, `title`, `status`, `created`, `pr` instead.
- `_index.md` (or `README.md` in `rfcs/`) is a folder's map of content.
- Relative Markdown links only, so links work in Obsidian and on GitHub.
- Folders starting with `_` are for vault tooling (templates), not content.
