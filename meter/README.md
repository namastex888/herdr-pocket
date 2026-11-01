# meter

`meter` counts the tokens coding agents use on one machine and publishes a small summary to that
machine's herdr-core, where Pocket can read it. Design:
[RFC 0005](../docs/rfcs/0005-usage-meter.md) (Draft). Rows follow `ae/1` (agent event envelope,
revision 2, owned by automagik-herdr).

It runs once and exits. It has no daemon, no listener and makes no network calls; the only process
it talks to is the local `hc`.

## What it reads

| Harness | Files | Notes |
|---|---|---|
| omp | `~/.omp/agent/sessions/**/*.jsonl` | Assistant messages and side calls (`model_usage`). omp's own `cost.total` is kept as reported $. No branch is recorded. |
| Claude Code | `~/.claude/projects/**/*.jsonl` (or `$CLAUDE_CONFIG_DIR/projects`) | Includes `<session>/subagents/*.jsonl`, rolled up to the parent session. Repeated lines are deduplicated by `message.id` + `requestId`. Advisor calls (`usage.iterations[]`, `type: advisor_message`) count under the advisor's model. 5-minute and 1-hour cache writes are kept apart. |

Codex is not read yet.

Logs are read incrementally from a saved byte offset, in 8 MB chunks. A line still being written
waits for the next run. A file whose inode, size or first 4 KB changed is read again from the start,
and the dedupe set absorbs what was already counted. Symlinks are never followed. Lines over 16 MB
are skipped and counted.

## What it stores

One SQLite file (WAL) per machine:

- macOS: `~/Library/Application Support/automagik/meter.db`
- Linux: `$XDG_STATE_HOME/automagik/meter.db`, else `~/.local/state/automagik/meter.db`
- `METER_STATE_DIR` overrides both.

The directory is 0700 and the file 0600. Rows are hourly `agent.usage` rollups keyed by (bot, role,
harness, model, machine, repo, branch, hour), with `input_uncached`, `cache_read`,
`cache_write_5m`, `cache_write_1h`, `output`, `reasoning` (part of output) and `precision`. The
`ae_usage` view renders each row as an `ae/1` envelope. No prompt text, titles, file contents or
per-turn rows are stored, and no $ except omp's reported cost.

- `repo` is `host/owner/repo` from the cwd's git remote, without credentials, port, query or
  fragment; worktrees resolve to their repository. No remote: null.
- `branch` is the session's `gitBranch` (Claude Code); null for omp.
- `bot`, `role`, `squad` come from the `bot=`, `role=`, `squad=` labels of a live hc terminal
  running the same harness in the same cwd. Usually null today.
- `machine` is herdr-core's core id, or a random id kept in the store when hc is absent.

## Run

```sh
meter sync                 # the normal run: at most 20 s, then publish
meter sync --no-publish    # update the store only
meter sync --backfill      # import the full history instead of 14 days (resumes across runs)
meter status               # what the store holds, as JSON
meter --version
```

`sync` options: `--force` (ignore the 10-minute freshness check), `--days N` (import window;
widening it re-imports), `--budget-secs N` (default 20), `--no-publish`, `--doc NAME` (default
`herdr-meter`).

- One run per machine: a second `meter sync` while one runs exits 0 at once.
- A sync within 10 minutes of a complete one does nothing unless `--force`.
- When the budget runs out, progress so far is committed and the rest waits for the next run. A
  killed run loses at most the chunk it was on and never counts anything twice.
- A missing `hc` is not an error: the summary is just not published.

## The summary doc

`meter sync` writes one doc per machine, `herdr-meter`, with `doc.get` then `doc.set` on the
version (retrying on `conflict`). It is a projection of the store for the phone, and stays under
64 KiB (see [Summary doc schema](#summary-doc-schema)).

$ is computed when the doc is written, never stored: omp's reported $ wins; otherwise a bundled,
dated list-price table (plus an optional `prices.json` next to the database) prices the tokens.
Every $ is an API-equivalent estimate, not an amount billed. Tokens of a model with no price, and
tokens known only as a total (`total_only`), are never priced: they are counted in `unpriced`.

## Summary doc schema

This is the contract the phone reads. Any change to it bumps `schema_version` and is listed under
[Schema history](#schema-history).

A synthetic example (`schema_version` 2, numbers made up, arrays shortened):

```json
{
  "schema_version": 2,
  "kind": "herdr-meter",
  "rows_schema": "ae/1 agent.usage",
  "meter_version": "0.2.0",
  "generatedAt": "2026-01-15T12:04:00Z",
  "machine": "core-1",
  "counted_from": "2025-12-17T00:00:00Z",
  "complete": true,
  "pending": {"bytes": 0, "files": 0},
  "prices": {"date": "2026-10-06", "source": "bundled",
             "label": "API-equivalent estimate at list price, not an amount billed"},
  "sources": {
    "omp": {"status": "ok", "last_success": "2026-01-15T12:04:00Z", "usage_lines": 40,
            "events": 40, "duplicates": 0, "skipped_lines": 0, "rewrites": 0, "unknown_models": []},
    "claude-code": {"status": "ok", "last_success": "2026-01-15T12:04:00Z", "usage_lines": 900,
                    "events": 450, "duplicates": 450, "skipped_lines": 0, "rewrites": 0,
                    "unknown_models": []},
    "codex": {"status": "degraded", "reason": "2 threads have no rollout file; counted as total_only",
              "last_success": "2026-01-15T12:04:00Z", "usage_lines": 120, "events": 118,
              "duplicates": 0, "skipped_lines": 0, "rewrites": 0, "unknown_models": ["model-x"],
              "threads": {"exact": 30, "total_only": 2}}
  },
  "windows": {"d1": "2026-01-14T13:00:00Z", "d7": "2026-01-08T13:00:00Z",
              "d30": "2025-12-17T00:00:00Z"},
  "models": [
    {"harness": "claude-code", "model": "model-a",
     "d1": {"in": 120, "cr": 90000, "cw": 4000, "out": 2100, "usd": 1.2345},
     "d7": {"in": 800, "cr": 610000, "cw": 30000, "out": 15000, "usd": 8.5},
     "d30": {"in": 2500, "cr": 2400000, "cw": 110000, "out": 52000, "usd": 31.02}},
    {"harness": "codex", "model": "model-x", "priced": false,
     "d30": {"in": 5000, "cr": 400000, "out": 9000, "rs": 3000, "un": 70000, "unpriced": 484000}}
  ],
  "projects": [
    {"repo": "github.com/acme/app", "tasks": 3, "hours": 41, "usd_per_task": 9.1,
     "last": "2026-01-15T11:00:00Z",
     "d1": {"in": 120, "cr": 90000, "cw": 4000, "out": 2100, "usd": 1.2345},
     "d7": {"in": 800, "cr": 610000, "cw": 30000, "out": 15000, "usd": 8.5},
     "d30": {"in": 2500, "cr": 2400000, "cw": 110000, "out": 52000, "usd": 31.02},
     "untasked": {"in": 100, "cr": 50000, "out": 900, "usd": 3.72}},
    {"repo": null, "tasks": 0, "hours": 5, "last": "2026-01-14T20:00:00Z",
     "d30": {"in": 10, "cr": 2000, "out": 300, "usd": 0.05, "usd_rep": 0.05},
     "untasked": {"in": 10, "cr": 2000, "out": 300, "usd": 0.05, "usd_rep": 0.05}}
  ],
  "projects_other": {"count": 12, "tasks": 9, "d30": {"in": 40, "cr": 9000, "out": 700, "usd": 0.4}},
  "tasks": [
    {"repo": "github.com/acme/app", "branch": "feat/login", "agents": ["claude-code", "codex"],
     "first": "2026-01-13T09:00:00Z", "last": "2026-01-15T11:00:00Z", "hours": 12,
     "d1": {"in": 60, "cr": 40000, "cw": 2000, "out": 1000, "usd": 0.61},
     "d7": {"in": 400, "cr": 300000, "cw": 15000, "out": 8000, "usd": 4.4},
     "d30": {"in": 400, "cr": 300000, "cw": 15000, "out": 8000, "usd": 4.4}}
  ],
  "tasks_other": {"count": 40, "d30": {"in": 900, "cr": 80000, "out": 4000, "usd": 2.2}},
  "hourly": {"from": "2026-01-13T13:00:00Z", "hours": 48, "series": [
    {"harness": "claude-code", "in": [0, 12, 30], "cr": [0, 9000, 20000], "cw": [0, 400, 800],
     "out": [0, 210, 500], "usd": [0, 0.12, 0.3]}]},
  "daily": {"from": "2025-12-17", "days": 30, "series": [
    {"harness": "claude-code", "in": [100, 90], "cr": [80000, 70000], "cw": [3000, 2000],
     "out": [1500, 1200], "usd": [1.1, 0.9]}]},
  "truncated": []
}
```

Top-level fields:

| Field | Meaning |
|---|---|
| `schema_version` | 2. A reader that does not know the version should show nothing rather than guess. |
| `generatedAt` | When the doc was written (UTC, RFC 3339). |
| `machine` | herdr-core's core id (stable, not the hostname). |
| `counted_from` | Start of what the store holds. A window that starts before it is cut to it. |
| `complete`, `pending` | `false` while a first import is still running; bytes and files left. |
| `prices` | Date and source of the price table that produced every `usd`. |
| `sources` | Per harness (`omp`, `claude-code`, `codex`): `status` is `ok`, `degraded` (some usage could not be split or read; `reason` says why), `unsupported` (the harness's format is not one the meter knows; nothing from it is counted), or `absent` (not installed). A non-`ok` status is shown, never hidden behind zeros. `unknown_models` are models with no price. `codex.threads` counts threads by precision. |
| `windows` | Start of each rolling window, ending at `generatedAt`'s hour: `d1` = the last 24 hours, `d7` = the last 7 days, `d30` = the last 30 days (never before `counted_from`). |
| `models` | Every (harness, model) with usage in `d30`, never cut. `priced: false` when the model has no price. |
| `projects` | One entry per repo, biggest `d30` $ first (then tokens). `repo` is `host/owner/repo` from the git remote, without credentials or port; `null` is work outside any repo (or a repo without a remote). |
| `projects_other` | The repos cut from `projects` to fit, summed. Absent when nothing was cut. |
| `tasks` | One entry per (repo, branch) that is a task, most recently active first. |
| `tasks_other` | The tasks cut from `tasks` to fit, summed. Absent when nothing was cut. |
| `hourly` | Per-harness series for the last 48 hours: value `i` is the hour `from + i` hours. The phone can sum it to its own local "today". |
| `daily` | Per-harness series per UTC day for the last 30 days: value `i` is the day `from + i`. |
| `truncated` | What was cut to fit 64 KiB, in order. Empty when nothing was. |

Token object (`d1`, `d7`, `d30`, `untasked`, `*_other`): a field that is zero is left out, and a
window with no usage is left out.

| Key | Meaning |
|---|---|
| `in` | Input tokens not read from cache. |
| `cr` | Cache reads. |
| `cw` | Cache writes (5-minute and 1-hour together). |
| `out` | Output tokens, reasoning included. |
| `rs` | Reasoning tokens, already inside `out`: never add it to a total. |
| `un` | Tokens known only as a total, with no split (`total_only`, e.g. a Codex thread without its rollout file). Never priced. |
| `usd` | $ of the priced part: list-price estimate plus any $ the harness reported. |
| `usd_rep` | The part of `usd` the harness reported itself (omp). |
| `unpriced` | Tokens not covered by `usd`: unknown models plus `un`. When every token is unpriced, `usd` is absent. |

Total tokens = `in + cr + cw + out + un`.

Projects and tasks:

| Key | Meaning |
|---|---|
| `branch` | The git branch the agent recorded. omp records none. |
| task | A (repo, branch) whose branch is not `main`, `master`, `dev`, `develop`, `trunk` or missing. Work on those goes to the project's `untasked` bucket instead. |
| `tasks` (on a project) | Number of tasks with usage in `d30`. |
| `usd_per_task` | The project's tasked `d30` $ (excluding `untasked`) divided by `tasks`. Absent with no tasks or no $. |
| `hours` | Distinct hours with usage in `d30`: a rough measure of time spent. |
| `first`, `last` | First and last hour with usage in `d30`. |
| `agents` | Harnesses that worked on the task. `bots` lists hc bot labels when known. |

Fitting in 64 KiB: the doc first keeps every project and task. If it is too big it cuts, in
order, the number of tasks, then the number of projects (the rest go to `*_other`), then the hourly
window; each step is listed in `truncated`. Entries that stay always keep their repo and branch.
`models`, `windows` and the totals in `daily` are never cut.

### Schema history

- 2 — per-project, per-task and per-model windows (`d1`/`d7`/`d30`), hourly and daily series per
  harness, `un`/`unpriced`, `sources.*.status`. Replaces version 1's `totals`, `hourly` and `daily`
  rows.
- 1 — hourly rows for 48 hours and daily rows before that, keyed by harness, model, repo and branch.

`prices.json` format, USD per million tokens:

```json
{"date": "2026-01-01", "models": {"some-model": {"input": 1.0, "output": 5.0, "cache_read": 0.1, "cache_write_5m": 1.25, "cache_write_1h": 2.0}}}
```

## Develop

```sh
cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
```

Tests use synthetic fixtures only. CI runs the same on Linux and macOS (`.github/workflows/meter.yml`).
