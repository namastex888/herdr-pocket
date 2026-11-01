//! Codex: thread list from a synthetic state database, usage from synthetic rollouts.

mod common;

use std::fs;
use std::path::PathBuf;

use common::*;
use meter::event::Tokens;
use meter::time::parse_rfc3339;
use rusqlite::{Connection, params};

const COLUMNS: &str = "id TEXT PRIMARY KEY, rollout_path TEXT NOT NULL, created_at INTEGER,
    updated_at INTEGER, source TEXT, model_provider TEXT, cwd TEXT NOT NULL, title TEXT,
    tokens_used INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0, git_branch TEXT,
    git_origin_url TEXT, model TEXT, updated_at_ms INTEGER";

struct Codex {
    home: PathBuf,
    db: PathBuf,
}

impl Codex {
    fn new(env: &Env) -> Codex {
        Codex::with_columns(env, COLUMNS)
    }

    fn with_columns(env: &Env, columns: &str) -> Codex {
        let home = env.home().join(".codex");
        fs::create_dir_all(&home).unwrap();
        let db = home.join("state_3.sqlite");
        let c = Connection::open(&db).unwrap();
        c.execute_batch(&format!(
            "CREATE TABLE threads ({columns});
             CREATE INDEX threads_updated ON threads(updated_at_ms);
             CREATE TABLE thread_spawn_edges (parent_thread_id TEXT NOT NULL,
               child_thread_id TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL);"
        ))
        .unwrap();
        // An older state database the meter must ignore.
        fs::write(home.join("state_2.sqlite"), b"not a database").unwrap();
        Codex { home, db }
    }

    fn rollout(&self, id: &str) -> PathBuf {
        let p = self
            .home
            .join("sessions/2026/01/15")
            .join(format!("rollout-2026-01-15T09-00-00-{id}.jsonl"));
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        p
    }

    fn thread(&self, id: &str, path: &std::path::Path, tokens_used: i64, updated: &str) {
        let c = Connection::open(&self.db).unwrap();
        c.execute(
            "INSERT INTO threads(id, rollout_path, cwd, tokens_used, git_branch, git_origin_url,
               model, updated_at_ms, title)
             VALUES(?1, ?2, '/work/app', ?3, 'feat/x',
               'https://someone:FAKE-TOKEN@git.example.com/acme/app.git', 'model-codex', ?4,
               'FAKE-TITLE')
             ON CONFLICT(id) DO UPDATE SET rollout_path = excluded.rollout_path,
               tokens_used = excluded.tokens_used, updated_at_ms = excluded.updated_at_ms",
            params![
                id,
                path.to_string_lossy(),
                tokens_used,
                parse_rfc3339(updated).unwrap()
            ],
        )
        .unwrap();
    }
}

/// One `token_count` event carrying the thread's running total.
fn tc(ts: &str, input: i64, cached: i64, output: i64, reasoning: i64) -> String {
    format!(
        concat!(
            r#"{{"timestamp":"{ts}","type":"event_msg","ordinal":3,"payload":{{"type":"token_count","#,
            r#""info":{{"total_token_usage":{{"input_tokens":{i},"cached_input_tokens":{c},"#,
            r#""cache_write_input_tokens":0,"output_tokens":{o},"reasoning_output_tokens":{r},"#,
            r#""total_tokens":{t}}},"last_token_usage":{{"input_tokens":1}},"#,
            r#""model_context_window":1000}},"rate_limits":null}}}}"#
        ),
        ts = ts,
        i = input,
        c = cached,
        o = output,
        r = reasoning,
        t = input + output,
    )
}

fn other_line() -> String {
    r#"{"timestamp":"2026-01-15T09:00:00Z","type":"response_item","payload":{"type":"message","content":[{"type":"text","text":"FAKE-PROMPT-TEXT token_count"}]}}"#.into()
}

/// (input_uncached, cache_read, output, reasoning) from a running total of
/// (input, cached, output, reasoning).
fn split(input: i64, cached: i64, output: i64, reasoning: i64) -> Tokens {
    Tokens {
        input_uncached: input - cached,
        cache_read: cached,
        output,
        reasoning,
        ..Tokens::default()
    }
}

fn codex_rows(env: &Env) -> Vec<meter::store::UsageRow> {
    env.store()
        .rows_since(i64::MIN)
        .unwrap()
        .into_iter()
        .filter(|r| r.key.harness == "codex")
        .collect()
}

fn codex_total(env: &Env) -> Tokens {
    let mut t = Tokens::default();
    for r in codex_rows(env) {
        t.add(&r.tokens);
    }
    t
}

#[test]
fn running_totals_become_hourly_deltas_across_runs() {
    let env = Env::new();
    let cx = Codex::new(&env);
    let f = cx.rollout("t-1");
    write_lines(
        &f,
        &[
            other_line(),
            tc("2026-01-15T09:10:00Z", 1000, 800, 100, 40),
            tc("2026-01-15T09:10:00Z", 1000, 800, 100, 40), // repeated
            tc("2026-01-15T10:20:00Z", 3000, 2500, 250, 90),
        ],
    );
    cx.thread("t-1", &f, 3250, "2026-01-15T10:20:00Z");
    let r = env.run(&env.opts());
    assert_eq!(codex_total(&env), split(3000, 2500, 250, 90));
    let rows = codex_rows(&env);
    assert_eq!(rows.len(), 2, "one row per hour");
    assert_eq!(rows[0].tokens, split(1000, 800, 100, 40));
    assert_eq!(rows[0].precision, "exact");
    assert_eq!(
        rows[0].key.repo.as_deref(),
        Some("git.example.com/acme/app")
    );
    assert_eq!(rows[0].key.branch.as_deref(), Some("feat/x"));
    assert_eq!(rows[0].key.model, "model-codex");
    let doc = r.summary.unwrap();
    assert_eq!(doc["sources"]["codex"]["status"], "ok");
    assert_eq!(doc["sources"]["codex"]["threads"]["exact"], 1);

    // The thread goes on: the next run reads only the new tail.
    append(
        &f,
        &format!("{}\n", tc("2026-01-15T11:05:00Z", 5000, 4200, 400, 100)),
    );
    cx.thread("t-1", &f, 5400, "2026-01-15T11:05:00Z");
    let r = env.run(&env.opts());
    assert!(
        r.bytes_read < 1000,
        "read {} bytes: only the tail",
        r.bytes_read
    );
    assert_eq!(codex_total(&env), split(5000, 4200, 400, 100));
    // Running again changes nothing.
    env.run(&env.opts());
    assert_eq!(codex_total(&env), split(5000, 4200, 400, 100));
}

#[test]
fn a_thread_without_a_rollout_counts_its_total_only() {
    let env = Env::new();
    let cx = Codex::new(&env);
    let gone = cx.rollout("t-gone");
    cx.thread("t-gone", &gone, 7000, "2026-01-15T08:30:00Z");
    let r = env.run(&env.opts());
    let rows = codex_rows(&env);
    assert_eq!(rows.len(), 1);
    assert_eq!(
        rows[0].tokens,
        Tokens {
            unsplit: 7000,
            ..Tokens::default()
        }
    );
    assert_eq!(rows[0].precision, "total_only");
    let doc = r.summary.unwrap();
    let m = &doc["models"][0];
    assert_eq!(m["d30"]["un"], 7000);
    assert_eq!(m["d30"]["unpriced"], 7000);
    assert!(
        m["d30"].get("in").is_none(),
        "a total is never counted as input"
    );
    assert!(m["d30"].get("usd").is_none());
    assert_eq!(doc["sources"]["codex"]["threads"]["total_only"], 1);

    // tokens_used is a running total too: only the growth is new.
    cx.thread("t-gone", &gone, 9000, "2026-01-15T09:30:00Z");
    env.run(&env.opts());
    assert_eq!(codex_total(&env).unsplit, 9000);
    env.run(&env.opts());
    assert_eq!(codex_total(&env).unsplit, 9000);
}

#[test]
fn an_archived_rollout_is_not_counted_again() {
    let env = Env::new();
    let cx = Codex::new(&env);
    let f = cx.rollout("t-1");
    write_lines(
        &f,
        &[
            tc("2026-01-15T09:10:00Z", 1000, 800, 100, 40),
            tc("2026-01-15T10:20:00Z", 3000, 2500, 250, 90),
        ],
    );
    cx.thread("t-1", &f, 3250, "2026-01-15T10:20:00Z");
    env.run(&env.opts());
    let want = split(3000, 2500, 250, 90);
    assert_eq!(codex_total(&env), want);

    // Codex moves the file to archived_sessions; the database may still name the old path.
    let archived = cx
        .home
        .join("archived_sessions")
        .join(f.file_name().unwrap());
    fs::create_dir_all(archived.parent().unwrap()).unwrap();
    fs::rename(&f, &archived).unwrap();
    cx.thread("t-1", &f, 3250, "2026-01-15T11:00:00Z");
    let r = env.run(&env.opts());
    assert_eq!(codex_total(&env), want);
    assert_eq!(r.bytes_read, 0, "a rename keeps the offset");

    // A copy (new inode) is read again from the start, and still counts nothing twice.
    let copy = cx
        .home
        .join("archived_sessions/copy")
        .join(f.file_name().unwrap());
    fs::create_dir_all(copy.parent().unwrap()).unwrap();
    fs::copy(&archived, &copy).unwrap();
    fs::remove_file(&archived).unwrap();
    append(
        &copy,
        &format!("{}\n", tc("2026-01-15T11:30:00Z", 3500, 2900, 300, 95)),
    );
    cx.thread("t-1", &copy, 3800, "2026-01-15T11:30:00Z");
    let r = env.run(&env.opts());
    assert!(r.bytes_read > 0);
    assert_eq!(codex_total(&env), split(3500, 2900, 300, 95));
}

#[test]
fn a_rewritten_rollout_never_double_counts_the_running_total() {
    let env = Env::new();
    let cx = Codex::new(&env);
    let f = cx.rollout("t-1");
    let lines = [
        tc("2026-01-15T09:10:00Z", 1000, 800, 100, 40),
        tc("2026-01-15T10:20:00Z", 3000, 2500, 250, 90),
    ];
    write_lines(&f, &lines);
    cx.thread("t-1", &f, 3250, "2026-01-15T10:20:00Z");
    env.run(&env.opts());
    // Compaction or a resume rewrites the file with different first bytes.
    let mut rewritten = vec![other_line()];
    rewritten.extend(lines.iter().cloned());
    write_lines(&f, &rewritten);
    cx.thread("t-1", &f, 3250, "2026-01-15T10:30:00Z");
    let r = env.run(&env.opts());
    assert_eq!(r.health.rewrites, 1);
    assert_eq!(codex_total(&env), split(3000, 2500, 250, 90));
}

#[test]
fn usage_before_the_window_advances_the_total_without_counting() {
    let env = Env::new();
    let cx = Codex::new(&env);
    let f = cx.rollout("t-old");
    write_lines(
        &f,
        &[
            tc("2025-11-01T09:00:00Z", 900_000, 800_000, 9000, 0),
            tc("2026-01-15T09:00:00Z", 901_000, 800_500, 9100, 0),
        ],
    );
    cx.thread("t-old", &f, 910_100, "2026-01-15T09:00:00Z");
    env.run(&env.opts());
    assert_eq!(codex_total(&env), split(1000, 500, 100, 0));
}

#[test]
fn an_unknown_rollout_shape_is_degraded_and_falls_back_to_the_total() {
    let env = Env::new();
    let cx = Codex::new(&env);
    let f = cx.rollout("t-1");
    // A future Codex renamed the fields.
    let line = tc("2026-01-15T09:10:00Z", 1000, 800, 100, 40).replace("input_tokens", "tokens_in");
    write_lines(&f, &[line]);
    cx.thread("t-1", &f, 1100, "2026-01-15T09:10:00Z");
    let r = env.run(&env.opts());
    let doc = r.summary.unwrap();
    let codex = &doc["sources"]["codex"];
    assert_eq!(codex["status"], "degraded");
    assert!(codex["reason"].as_str().unwrap().contains("unknown format"));
    assert_eq!(
        codex_total(&env),
        Tokens {
            unsplit: 1100,
            ..Tokens::default()
        },
        "counted as a total, not as zero"
    );
}

#[test]
fn a_state_database_of_another_shape_is_unsupported_not_zero() {
    let env = Env::new();
    let cx = Codex::with_columns(&env, &COLUMNS.replace("git_origin_url TEXT,", ""));
    let f = cx.rollout("t-1");
    write_lines(&f, &[tc("2026-01-15T09:10:00Z", 1000, 800, 100, 40)]);
    let r = env.run(&env.opts());
    let doc = r.summary.unwrap();
    let codex = &doc["sources"]["codex"];
    assert_eq!(codex["status"], "unsupported");
    assert!(codex["reason"].as_str().unwrap().contains("git_origin_url"));
    assert!(codex_rows(&env).is_empty());
    // The other harnesses are unaffected.
    assert_eq!(doc["sources"]["claude-code"]["status"], "ok");
}

#[test]
fn no_codex_is_absent() {
    let env = Env::new();
    let r = env.run(&env.opts());
    assert_eq!(r.summary.unwrap()["sources"]["codex"]["status"], "absent");
}

#[test]
fn the_state_database_is_left_untouched_and_no_text_is_kept() {
    let env = Env::new();
    let cx = Codex::new(&env);
    let f = cx.rollout("t-1");
    write_lines(
        &f,
        &[other_line(), tc("2026-01-15T09:10:00Z", 1000, 800, 100, 40)],
    );
    cx.thread("t-1", &f, 1100, "2026-01-15T09:10:00Z");
    let before = fs::read(&cx.db).unwrap();
    let r = env.run(&env.opts());
    assert_eq!(fs::read(&cx.db).unwrap(), before);
    let doc = serde_json::to_string(&r.summary.unwrap()).unwrap();
    let mut db = Vec::new();
    for e in fs::read_dir(env.state()).unwrap() {
        db.extend(fs::read(e.unwrap().path()).unwrap());
    }
    let db = String::from_utf8_lossy(&db);
    for secret in ["FAKE-TOKEN", "FAKE-PROMPT-TEXT", "FAKE-TITLE", "someone"] {
        assert!(!doc.contains(secret), "{secret} reached the doc");
        assert!(!db.contains(secret), "{secret} reached the store");
    }
}

#[test]
fn a_budget_stop_resumes_codex_without_double_counting() {
    let env = Env::new();
    let cx = Codex::new(&env);
    let mut want = Tokens::default();
    for t in 0..4 {
        let id = format!("t-{t}");
        let f = cx.rollout(&id);
        let mut lines = Vec::new();
        for i in 1..=200i64 {
            lines.push(tc(
                &format!("2026-01-15T{:02}:{:02}:00Z", 9 + i / 60, i % 60),
                i * 100,
                i * 80,
                i * 10,
                i,
            ));
        }
        write_lines(&f, &lines);
        cx.thread(&id, &f, 200 * 110, &format!("2026-01-15T10:0{t}:00Z"));
        want.add(&split(20_000, 16_000, 2000, 200));
    }
    let mut o = env.opts();
    o.chunk_bytes = 16 * 1024;
    o.chunk_limit = Some(3);
    let mut runs = 0;
    loop {
        runs += 1;
        let r = env.run(&o);
        if !r.stopped_by_budget {
            break;
        }
        assert!(r.pending_files > 0);
        assert!(runs < 50);
    }
    assert!(runs > 2);
    assert_eq!(codex_total(&env), want);
}
