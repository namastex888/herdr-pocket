//! Codex: the thread list from its state database, usage from each thread's rollout JSONL.
//!
//! - `~/.codex/state_<N>.sqlite` (the highest N) is opened read-only for one indexed SELECT over
//!   `threads` changed since the last run, then closed. Its other databases (thread history, logs,
//!   memories) are never opened.
//! - A rollout's `event_msg` lines of type `token_count` carry `info.total_token_usage`: a running
//!   total per thread in which cached input is inside `input_tokens` and reasoning is inside
//!   `output_tokens`. The meter keeps the last total per thread (keyed by thread id, not path, as
//!   rollouts move to `archived_sessions`) and emits the difference as usage.
//! - A thread with no rollout file, or whose rollout has no readable total, is counted from
//!   `threads.tokens_used` as `total_only`: a total with no split, never priced.

use std::collections::HashMap;
use std::path::{Component, Path, PathBuf};
use std::time::Duration;

use rusqlite::{Connection, OpenFlags};
use serde::{Deserialize, Serialize};

use crate::event::{Precision, Tokens, UsageEvent};
use crate::repo::normalize_remote;
use crate::sources::{FileCtx, Line, clip, from_line};
use crate::time::parse_rfc3339;

/// A thread's running total, as Codex writes it.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Cum {
    #[serde(default)]
    pub input: i64,
    #[serde(default)]
    pub cached: i64,
    #[serde(default)]
    pub cache_write: i64,
    #[serde(default)]
    pub output: i64,
    #[serde(default)]
    pub reasoning: i64,
    #[serde(default)]
    pub total: i64,
}

impl Cum {
    fn max(self, o: Cum) -> Cum {
        Cum {
            input: self.input.max(o.input),
            cached: self.cached.max(o.cached),
            cache_write: self.cache_write.max(o.cache_write),
            output: self.output.max(o.output),
            reasoning: self.reasoning.max(o.reasoning),
            total: self.total.max(o.total),
        }
    }
}

/// What reading a thread's rollout needs, and the thread's running state. Lives in the rollout's
/// `FileCtx` while it is read and in `codex_threads` (by thread id) between runs.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CodexCtx {
    pub thread: String,
    pub model: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub branch: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub repo: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent: Option<String>,
    /// The highest running total seen (field by field).
    pub cum: Cum,
    /// How much of the thread's total is accounted for: counted, or before the import window.
    /// Above `cum.total` when part of it was counted from `tokens_used` without a split.
    pub counted: i64,
    /// This run only: `token_count` lines whose shape the meter does not know.
    #[serde(default, skip_serializing)]
    pub bad: i64,
    /// This run only: lines with a non-zero `cache_write_input_tokens` (none seen so far).
    #[serde(default, skip_serializing)]
    pub cache_writes: i64,
    /// This run only: running totals that went down (none seen so far).
    #[serde(default, skip_serializing)]
    pub decreases: i64,
}

#[derive(Deserialize)]
struct RolloutLine {
    timestamp: Option<String>,
    #[serde(rename = "type")]
    ty: Option<String>,
    payload: Option<Payload>,
}

#[derive(Deserialize)]
struct Payload {
    #[serde(rename = "type")]
    ty: Option<String>,
    info: Option<Info>,
}

#[derive(Deserialize)]
struct Info {
    total_token_usage: Option<RawUsage>,
}

#[derive(Deserialize)]
struct RawUsage {
    input_tokens: Option<i64>,
    cached_input_tokens: Option<i64>,
    cache_write_input_tokens: Option<i64>,
    output_tokens: Option<i64>,
    reasoning_output_tokens: Option<i64>,
    total_tokens: Option<i64>,
}

impl RawUsage {
    /// The running total, if it has the fields and the meaning the meter knows: cached input
    /// inside input, reasoning inside output, and total = input + output.
    fn cum(&self) -> Option<Cum> {
        let c = Cum {
            input: self.input_tokens?,
            cached: self.cached_input_tokens?,
            cache_write: self.cache_write_input_tokens.unwrap_or(0),
            output: self.output_tokens?,
            reasoning: self.reasoning_output_tokens?,
            total: self.total_tokens?,
        };
        let ok = c.cached >= 0
            && c.cache_write >= 0
            && c.reasoning >= 0
            && c.cached <= c.input
            && c.reasoning <= c.output
            && c.total == c.input + c.output;
        ok.then_some(c)
    }
}

/// Folds one rollout line into the thread's running total and pushes the new usage, if any.
pub fn parse_line(line: &[u8], ctx: &mut FileCtx, out: &mut Vec<UsageEvent>) -> Line {
    if memchr::memmem::find(line, b"\"token_count\"").is_none() {
        return Line::Ignored;
    }
    let Some(c) = ctx.codex.as_mut() else {
        return Line::Ignored;
    };
    let Some(l) = from_line::<RolloutLine>(line) else {
        c.bad += 1;
        return Line::Bad;
    };
    let Some(p) = l.payload else {
        return Line::Ignored;
    };
    if l.ty.as_deref() != Some("event_msg") || p.ty.as_deref() != Some("token_count") {
        return Line::Ignored;
    }
    // `info` is null before the first model reply: nothing to count yet.
    let Some(raw) = p.info.and_then(|i| i.total_token_usage) else {
        return Line::Ignored;
    };
    let (Some(e), Some(ts_ms)) = (raw.cum(), l.timestamp.as_deref().and_then(parse_rfc3339)) else {
        c.bad += 1;
        return Line::Bad;
    };
    if e.cache_write > 0 {
        c.cache_writes += 1;
    }
    if e.total <= c.counted {
        // Already counted (a repeated line, a re-read, or before the import window).
        c.cum = c.cum.max(e);
        return Line::Usage;
    }
    let (tokens, precision) = if c.cum.total >= c.counted {
        let p = c.cum;
        if e.input < p.input || e.cached < p.cached || e.output < p.output {
            c.decreases += 1;
        }
        let d = |a: i64, b: i64| (a - b).max(0);
        let input = d(e.input, p.input);
        let cached = d(e.cached, p.cached);
        let output = d(e.output, p.output);
        let reasoning = d(e.reasoning, p.reasoning);
        (
            Tokens {
                input_uncached: (input - cached).max(0),
                cache_read: cached.min(input),
                output,
                reasoning: reasoning.min(output),
                ..Tokens::default()
            },
            Precision::Exact,
        )
    } else {
        // Part of this total was already counted without a split (from `tokens_used`): the rest
        // has no reliable split either.
        (
            Tokens {
                unsplit: e.total - c.counted,
                ..Tokens::default()
            },
            Precision::TotalOnly,
        )
    };
    c.cum = c.cum.max(e);
    c.counted = e.total;
    if !tokens.is_zero() {
        out.push(event(c, ts_ms, tokens, precision));
    }
    Line::Usage
}

pub fn event(c: &CodexCtx, ts_ms: i64, tokens: Tokens, precision: Precision) -> UsageEvent {
    UsageEvent {
        dedupe_key: String::new(),
        ts_ms,
        harness: "codex",
        model: c.model.clone(),
        session: Some(c.thread.clone()),
        parent_session: c.parent.clone(),
        cwd: c.cwd.clone(),
        branch: c.branch.clone(),
        repo: c.repo.clone(),
        tokens,
        precision,
        reported_usd: None,
    }
}

/// One row of `threads`, reduced to what the meter uses.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Thread {
    pub id: String,
    pub rollout_path: Option<String>,
    pub tokens_used: i64,
    pub cwd: Option<String>,
    pub model: Option<String>,
    pub git_branch: Option<String>,
    pub git_origin_url: Option<String>,
    pub updated_at_ms: i64,
    pub archived: bool,
}

impl Thread {
    /// The context a read of this thread starts from, given its stored running state.
    pub fn ctx(&self, parent: Option<&String>, cum: Cum, counted: i64) -> CodexCtx {
        let some = |s: &Option<String>, max: usize| {
            s.as_deref().filter(|s| !s.is_empty()).map(|s| clip(s, max))
        };
        CodexCtx {
            thread: clip(&self.id, 128),
            model: some(&self.model, 128).unwrap_or_else(|| "unknown".into()),
            branch: some(&self.git_branch, 256),
            repo: self.git_origin_url.as_deref().and_then(normalize_remote),
            cwd: some(&self.cwd, 4096),
            parent: parent.map(|p| clip(p, 128)),
            cum,
            counted,
            ..CodexCtx::default()
        }
    }
}

#[derive(Debug)]
pub enum State {
    /// No state database: Codex is not installed.
    Absent,
    /// The database is there but not in a shape the meter knows.
    Unsupported(String),
    Threads {
        threads: Vec<Thread>,
        /// child thread id → parent thread id.
        parents: HashMap<String, String>,
    },
}

/// The columns the meter reads. If Codex renames any of them, nothing is counted and the source
/// says so, instead of counting zeros.
pub const REQUIRED_COLUMNS: [&str; 9] = [
    "id",
    "rollout_path",
    "tokens_used",
    "cwd",
    "model",
    "git_branch",
    "git_origin_url",
    "updated_at_ms",
    "archived",
];

/// `state_<N>.sqlite` with the highest N.
pub fn state_db(codex_home: &Path) -> Option<PathBuf> {
    let entries = std::fs::read_dir(codex_home).ok()?;
    let mut best: Option<(u64, PathBuf)> = None;
    for e in entries.flatten() {
        let name = e.file_name();
        let Some(n) = name
            .to_str()
            .and_then(|s| s.strip_prefix("state_"))
            .and_then(|s| s.strip_suffix(".sqlite"))
            .and_then(|s| s.parse::<u64>().ok())
        else {
            continue;
        };
        if e.file_type().is_ok_and(|t| t.is_file()) && best.as_ref().is_none_or(|(b, _)| n > *b) {
            best = Some((n, e.path()));
        }
    }
    best.map(|(_, p)| p)
}

fn uri(path: &Path) -> String {
    let mut s = String::from("file:");
    for b in path.to_string_lossy().bytes() {
        if b.is_ascii_alphanumeric() || b"/-_.~".contains(&b) {
            s.push(b as char);
        } else {
            s.push_str(&format!("%{b:02X}"));
        }
    }
    s.push_str("?mode=ro");
    s
}

fn columns(conn: &Connection, table: &str) -> rusqlite::Result<Vec<String>> {
    let mut st = conn.prepare("SELECT name FROM pragma_table_info(?1)")?;
    let rows = st.query_map([table], |r| r.get(0))?;
    rows.collect()
}

/// Threads updated at or after `since_ms`, oldest first. Read-only, one short query, then closed.
pub fn read_state(db: &Path, since_ms: i64) -> State {
    let conn = match Connection::open_with_flags(
        uri(db),
        OpenFlags::SQLITE_OPEN_READ_ONLY
            | OpenFlags::SQLITE_OPEN_URI
            | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    ) {
        Ok(c) => c,
        Err(e) => return State::Unsupported(format!("cannot open the state database: {e}")),
    };
    let run = || -> Result<State, String> {
        conn.busy_timeout(Duration::from_secs(2))
            .map_err(|e| e.to_string())?;
        let cols = columns(&conn, "threads").map_err(|e| e.to_string())?;
        if cols.is_empty() {
            return Ok(State::Unsupported(
                "the state database has no threads table".into(),
            ));
        }
        let missing: Vec<&str> = REQUIRED_COLUMNS
            .iter()
            .copied()
            .filter(|c| !cols.iter().any(|x| x == c))
            .collect();
        if !missing.is_empty() {
            return Ok(State::Unsupported(format!(
                "threads lacks {}",
                missing.join(", ")
            )));
        }
        let mut st = conn
            .prepare(
                "SELECT id, rollout_path, tokens_used, cwd, model, git_branch, git_origin_url,
                        updated_at_ms, archived
                 FROM threads WHERE updated_at_ms >= ?1 ORDER BY updated_at_ms, id LIMIT 200000",
            )
            .map_err(|e| e.to_string())?;
        let threads = st
            .query_map([since_ms], |r| {
                Ok(Thread {
                    id: r.get(0)?,
                    rollout_path: r.get(1)?,
                    tokens_used: r.get::<_, Option<i64>>(2)?.unwrap_or(0).max(0),
                    cwd: r.get(3)?,
                    model: r.get(4)?,
                    git_branch: r.get(5)?,
                    git_origin_url: r.get(6)?,
                    updated_at_ms: r.get::<_, Option<i64>>(7)?.unwrap_or(0),
                    archived: r.get::<_, Option<i64>>(8)?.unwrap_or(0) != 0,
                })
            })
            .and_then(|rows| rows.collect::<rusqlite::Result<Vec<_>>>())
            .map_err(|e| e.to_string())?;
        let mut parents = HashMap::new();
        let edge_cols = columns(&conn, "thread_spawn_edges").map_err(|e| e.to_string())?;
        if ["parent_thread_id", "child_thread_id"]
            .iter()
            .all(|c| edge_cols.iter().any(|x| x == c))
        {
            let mut st = conn
                .prepare(
                    "SELECT child_thread_id, parent_thread_id FROM thread_spawn_edges LIMIT 200000",
                )
                .map_err(|e| e.to_string())?;
            let rows = st
                .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
                .and_then(|rows| rows.collect::<rusqlite::Result<Vec<_>>>())
                .map_err(|e| e.to_string())?;
            parents.extend(rows);
        }
        Ok(State::Threads { threads, parents })
    };
    let out = run().unwrap_or_else(|e| State::Unsupported(format!("cannot read threads: {e}")));
    drop(conn);
    out
}

/// A relative path with no `..`, `.` or root: safe to join under a directory.
fn plain(p: &Path) -> bool {
    p.components().all(|c| matches!(c, Component::Normal(_)))
}

/// The thread's rollout file, if it is a regular file under `sessions/` or `archived_sessions/`.
/// A rollout that moved between the two (archiving) is found by its file name.
pub fn resolve_rollout(codex_home: &Path, rollout_path: Option<&str>) -> Option<PathBuf> {
    let given = PathBuf::from(rollout_path?);
    let roots = [
        codex_home.join("sessions"),
        codex_home.join("archived_sessions"),
    ];
    let mut tries: Vec<PathBuf> = Vec::new();
    if let Some(rel) = roots
        .iter()
        .find_map(|r| given.strip_prefix(r).ok().filter(|rel| plain(rel)))
    {
        tries.push(roots[0].join(rel));
        tries.push(roots[1].join(rel));
    }
    let name = given.file_name()?;
    if !plain(Path::new(name)) {
        return None;
    }
    tries.push(roots[1].join(name));
    // rollout-YYYY-MM-DDT…: live rollouts sit in sessions/YYYY/MM/DD/.
    if let Some(date) = name
        .to_str()
        .and_then(|n| n.strip_prefix("rollout-"))
        .and_then(|n| n.get(..10))
        && date.as_bytes().get(4) == Some(&b'-')
        && date.as_bytes().get(7) == Some(&b'-')
    {
        tries.push(
            roots[0]
                .join(&date[..4])
                .join(&date[5..7])
                .join(&date[8..10])
                .join(name),
        );
    }
    tries
        .into_iter()
        .find(|p| std::fs::symlink_metadata(p).is_ok_and(|m| m.file_type().is_file()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(ts: &str, input: i64, cached: i64, output: i64, reasoning: i64) -> String {
        format!(
            r#"{{"timestamp":"{ts}","type":"event_msg","ordinal":1,"payload":{{"type":"token_count","info":{{"total_token_usage":{{"input_tokens":{input},"cached_input_tokens":{cached},"cache_write_input_tokens":0,"output_tokens":{output},"reasoning_output_tokens":{reasoning},"total_tokens":{}}},"last_token_usage":{{}}}},"rate_limits":null}}}}"#,
            input + output
        )
    }

    fn ctx() -> FileCtx {
        FileCtx {
            codex: Some(CodexCtx {
                thread: "t-1".into(),
                model: "model-x".into(),
                ..CodexCtx::default()
            }),
            ..FileCtx::default()
        }
    }

    #[test]
    fn cumulative_totals_become_deltas() {
        let mut c = ctx();
        let mut out = Vec::new();
        let a = line("2026-01-15T10:00:00Z", 1000, 800, 50, 20);
        let b = line("2026-01-15T10:05:00Z", 2500, 2000, 90, 30);
        for l in [&a, &a, &b] {
            assert_eq!(parse_line(l.as_bytes(), &mut c, &mut out), Line::Usage);
        }
        assert_eq!(out.len(), 2, "the repeated line adds nothing");
        assert_eq!(
            out[0].tokens,
            Tokens {
                input_uncached: 200,
                cache_read: 800,
                output: 50,
                reasoning: 20,
                ..Tokens::default()
            }
        );
        assert_eq!(
            out[1].tokens,
            Tokens {
                input_uncached: 300,
                cache_read: 1200,
                output: 40,
                reasoning: 10,
                ..Tokens::default()
            }
        );
        let cx = c.codex.unwrap();
        assert_eq!(cx.counted, 2590);
        assert_eq!(cx.cum.total, 2590);
    }

    #[test]
    fn unknown_shapes_are_bad_not_zero() {
        let mut c = ctx();
        let mut out = Vec::new();
        let renamed = line("2026-01-15T10:00:00Z", 10, 5, 3, 1).replace("input_tokens", "in_tok");
        assert_eq!(parse_line(renamed.as_bytes(), &mut c, &mut out), Line::Bad);
        // total no longer input + output: the meaning changed.
        let off = line("2026-01-15T10:00:00Z", 10, 5, 3, 1)
            .replace("\"total_tokens\":13", "\"total_tokens\":99");
        assert_eq!(parse_line(off.as_bytes(), &mut c, &mut out), Line::Bad);
        assert!(out.is_empty());
        assert_eq!(c.codex.unwrap().bad, 2);
    }

    #[test]
    fn highest_state_db_wins_and_moved_rollouts_are_found() {
        let d = tempfile::tempdir().unwrap();
        for n in [
            "state_5.sqlite",
            "state_10.sqlite",
            "state_x.sqlite",
            "logs_99.sqlite",
        ] {
            std::fs::write(d.path().join(n), b"").unwrap();
        }
        assert_eq!(
            state_db(d.path()).unwrap(),
            d.path().join("state_10.sqlite")
        );
        let name = "rollout-2026-01-15T10-00-00-t-1.jsonl";
        let live = d.path().join("sessions/2026/01/15").join(name);
        let gone = live.to_string_lossy().into_owned();
        std::fs::create_dir_all(d.path().join("archived_sessions")).unwrap();
        std::fs::write(d.path().join("archived_sessions").join(name), b"").unwrap();
        assert_eq!(
            resolve_rollout(d.path(), Some(&gone)).unwrap(),
            d.path().join("archived_sessions").join(name)
        );
        assert_eq!(resolve_rollout(d.path(), Some("/etc/passwd")), None);
        let up = format!("{}/sessions/../../outside.jsonl", d.path().display());
        assert_eq!(resolve_rollout(d.path(), Some(&up)), None);
    }
}
