# meter

`meter` counts the tokens coding agents use on one machine and publishes a small summary to that
machine's herdr-core, where Pocket can read it. Design: RFC 0005, usage meter
(`docs/rfcs/0005-usage-meter.md`, Draft). Rows follow `ae/1` (agent event envelope, revision 2,
owned by automagik-herdr).

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
version (retrying on `conflict`). It stays under 64 KB: hourly rows for the last 48 hours, daily
rows before that; if it would be larger, detail is dropped step by step and listed in `truncated`.
It carries `schema_version`, `generatedAt`, `meter_version`, `counted_from`, `complete`,
`pending` (bytes and files still to read), per-harness parser health, the price table's date, and
`totals` per harness and model.

$ is computed when the doc is written, never stored: omp's reported $ (`usd_src: "reported"`) wins;
otherwise a bundled, dated list-price table (plus an optional `prices.json` next to the database)
prices the tokens. Every $ is an API-equivalent estimate, not an amount billed. A model with no
price has `priced: false` and no $.

`prices.json` format, USD per million tokens:

```json
{"date": "2026-01-01", "models": {"some-model": {"input": 1.0, "output": 5.0, "cache_read": 0.1, "cache_write_5m": 1.25, "cache_write_1h": 2.0}}}
```

## Develop

```sh
cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
```

Tests use synthetic fixtures only. CI runs the same on Linux and macOS (`.github/workflows/meter.yml`).
