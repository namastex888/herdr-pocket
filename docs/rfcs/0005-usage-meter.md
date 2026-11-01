---
rfc: "0005"
title: "Usage meter: token usage per machine as ae/1 rows, a summary doc in herdr-core"
status: Draft
created: 2026-11-01
pr: ""
---
# RFC 0005 — Usage meter: token usage per machine as ae/1 rows, a summary doc in herdr-core

## Summary

A small Rust program, `meter/`, runs on each of the user's machines. It records the token usage
of coding agents as `ae/1` (agent event envelope, revision 2, owned by automagik-herdr) rows in a
SQLite file on that machine, and publishes a short summary as a herdr-core document. Pocket can
then show usage by reading that document over the connection it already has. Nothing leaves the
user's machines.

## Motivation

The meter is planned work. The owner's reason for it is not written down yet (open question 6).
This draft collects a proposed design so it can be accepted or changed before code is written;
everything under Design and Alternatives is a proposal, not a decision.

## Current state

- `meter/` ([meter/README.md](../../meter/README.md)): `meter sync` reads omp, Claude Code and
  Codex usage into hourly `agent.usage` rows in a per-machine SQLite file and publishes a
  `herdr-meter` summary doc with `doc.get`/`doc.set`. The design below predates it; where they
  differ (one row per rollup hour rather than per event; the doc's fields), the code is current.
- Codex: thread list from its state database (read-only, one query), usage from the running
  totals in each rollout. Threads with no rollout file are counted from `tokens_used` as
  `total_only` (no split, no $); on a busy machine they can be a large share of usage. `ae/1`
  revision 2 has no field for an unsplit total, so the meter adds `data.unsplit`; this needs to
  go back to the envelope's owner.
- Summary doc schema version 2 (the phone's contract, in the README): per-project, per-task
  (repo + branch) and per-model 24 h / 7 d / 30 d windows, per-harness hourly (48 h) and daily
  (30 d) series. It fits 64 KiB by cutting the task list, then the project list, into `*_other`.
- Each release carries the meter for macOS (arm64, x86_64) and Linux (static x86_64, aarch64)
  with `SHA256SUMS`. While Pocket Link's bridge runs, it installs the matching build on this
  computer and every connected herdr-core machine (hash checked first) and runs
  `nice -n 19 automagik-meter sync` ten minutes after each pass, skipping machines on battery.
  This answers open question 4 for Pocket Link users. A machine reached only over SSH, without
  a desktop running the bridge, still needs the meter installed and scheduled by hand.
- The app already reads herdr-core documents: it calls `doc.get` for the `herdr-app` document,
  locally and on forwarded machines (`android/app/src/main/java/ai/namastex/automagik/PocketViewModel.kt:440`,
  `:446`). A summary document would travel the same way.
- `ae/1` is defined outside this repo. Its JSON Schema will be owned by automagik-herdr. This RFC
  does not restate it.

## Design

### Parts

| Part | Where | Does |
|---|---|---|
| `meter` binary | each machine | collects usage events, writes rows, publishes the summary |
| SQLite file | each machine, in the user's state directory | one row per `ae/1` event; never synced |
| summary doc | herdr-core on that machine | small totals the phone and desktop can read |
| Pocket | phone | reads the summary doc; writes nothing |

### Row

Each row stores the `ae/1` envelope as JSON plus a few columns for queries. Column names are a
proposal:

| Column | Example (synthetic) |
|---|---|
| `id` | `evt-0001` |
| `machine` | `laptop-1` |
| `repo` | `example/app` |
| `at` | `2026-01-01T09:00:00Z` |
| `envelope` | the `ae/1` JSON, validated against the automagik-herdr schema |

### Summary doc

A herdr-core document, one per machine, with totals per day, per repo and per agent program. A
synthetic example of its shape:

```json
{
  "machine": "laptop-1",
  "day": "2026-01-01",
  "repos": [{ "repo": "example/app", "input": 1000, "output": 200 }]
}
```

The numbers above are made up. The document name and fields are open question 2.

### Privacy

- Rows and the summary stay on the user's machine. Pocket reads the summary over Pocket Link or
  SSH like any other document; [privacy.md](../privacy.md) gets a line when this ships.
- No prompts or agent output go into the meter, only counts and labels.

## Decisions

None yet.

## Alternatives considered

- **Count in the app.** The phone only sees what is on screen when it is connected, so it would
  miss most usage.
- **One shared database for all machines.** Needs sync and a server; per-machine files keep the
  data where it was produced.

## Open questions

1. Where does the meter read usage from (agent logs, herdr-core events, a hook)? — the owner, with
   automagik-herdr.
2. Name and fields of the summary document, and whether Herdr desktop shows it too. — the owner.
3. Which `ae/1` event types carry token usage, and how revision changes are handled. —
   automagik-herdr.
4. How the meter is installed and kept running on macOS and Linux. — the owner.
5. Does a meter change cut an app release? See [RFC 0004](0004-monorepo.md), question 3. — the
   owner.
6. What the meter is for: budgets, a per-repo view, alerts on the phone? — the owner.

## Related

- `ae/1` (agent event envelope, revision 2, owned by automagik-herdr)
- [RFC 0004 — One repo](0004-monorepo.md)
