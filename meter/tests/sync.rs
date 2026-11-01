mod common;

use std::fs;
use std::time::{Duration, SystemTime};

use common::*;
use meter::event::Tokens;
use meter::sync::{self, Outcome};

const TS: &str = "2026-01-15T10:15:00.000Z";

#[test]
fn claude_dedupe_drops_repeated_lines_within_and_across_files() {
    let env = Env::new();
    let a = env.claude("-work-app/sess-1.jsonl");
    let b = env.claude("-work-app/sess-2.jsonl");
    // One message is written once per content block: three identical usage lines.
    let m1 = claude_line("msg_1", "req_1", TS, "model-a", &U1, 0);
    let m2 = claude_line("msg_2", "req_2", TS, "model-a", &U2, 0);
    write_lines(
        &a,
        &[
            m1.clone(),
            m1.clone(),
            user_line("hi"),
            m1.clone(),
            m2.clone(),
        ],
    );
    // A resumed session copies an earlier message into a new file.
    write_lines(&b, std::slice::from_ref(&m1));
    let r = env.run(&env.opts());
    assert_eq!(r.health.events, 2);
    assert_eq!(r.health.duplicates, 3);
    assert_eq!(env.totals()["model-a"], sum(&[&U1, &U2]));
    // Same message id with a different request id is a different API call.
    common::append(
        &b,
        &(claude_line("msg_1", "req_9", TS, "model-a", &U2, 0) + "\n"),
    );
    env.run(&env.opts());
    assert_eq!(env.totals()["model-a"], sum(&[&U1, &U2, &U2]));
}

#[test]
fn advisor_iterations_become_usage_under_the_advisor_model() {
    let env = Env::new();
    let f = env.claude("-work-app/sess-1.jsonl");
    let line = r#"{"type":"assistant","requestId":"req_a","timestamp":"2026-01-15T10:20:00Z","cwd":"/work/app","message":{"id":"msg_a","model":"model-exec","usage":{"input_tokens":4,"output_tokens":285,"cache_read_input_tokens":2000,"cache_creation_input_tokens":30,"cache_creation":{"ephemeral_5m_input_tokens":30,"ephemeral_1h_input_tokens":0},"iterations":[{"type":"message","input_tokens":4,"output_tokens":285,"cache_read_input_tokens":2000,"cache_creation_input_tokens":30},{"type":"advisor_message","model":"model-advisor","input_tokens":10000,"output_tokens":1200,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}]}}}"#;
    write_lines(&f, &[line.to_string(), line.to_string()]);
    env.run(&env.opts());
    let t = env.totals();
    assert_eq!(
        t["model-advisor"],
        Tokens {
            input_uncached: 10000,
            output: 1200,
            ..Tokens::default()
        }
    );
    assert_eq!(
        t["model-exec"],
        Tokens {
            input_uncached: 4,
            output: 285,
            cache_read: 2000,
            cache_write_5m: 30,
            ..Tokens::default()
        }
    );
}

#[test]
fn subagent_transcripts_roll_up_into_the_parent_row() {
    let env = Env::new();
    let parent = env.claude("-work-app/sess-1.jsonl");
    let child = env.claude("-work-app/sess-1/subagents/agent-a1.jsonl");
    write_lines(
        &parent,
        &[claude_line("msg_p", "req_p", TS, "model-a", &U1, 0)],
    );
    write_lines(
        &child,
        &[claude_line(
            "msg_c",
            "req_c",
            "2026-01-15T10:40:00Z",
            "model-a",
            &U2,
            0,
        )],
    );
    env.run(&env.opts());
    let store = env.store();
    let rows = store.rows_since(i64::MIN).unwrap();
    assert_eq!(
        rows.len(),
        1,
        "sub-agent usage must land in the parent's row"
    );
    assert_eq!(rows[0].tokens, sum(&[&U1, &U2]));
    let parent_of: Option<String> = store
        .conn
        .query_row(
            "SELECT parent_session FROM sessions WHERE session = 'agent-a1'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(parent_of.as_deref(), Some("sess-1"));
}

#[test]
fn chunk_boundaries_and_a_partial_last_line() {
    let env = Env::new();
    let f = env.claude("-work-app/sess-1.jsonl");
    let lines: Vec<String> = (0..6)
        .map(|i| {
            claude_line(
                &format!("msg_{i}"),
                &format!("req_{i}"),
                TS,
                "model-a",
                &U1,
                37 * i,
            )
        })
        .collect();
    let mut text = lines[..5].join("\n") + "\n";
    let last = &lines[5];
    let cut = last.len() / 2;
    text.push_str(&last[..cut]); // the writer is mid-line
    fs::write(&f, &text).unwrap();
    let mut o = env.opts();
    o.chunk_bytes = 97; // far smaller than a line: every line spans chunks
    let r = env.run(&o);
    assert_eq!(r.health.events, 5);
    assert_eq!(r.health.skipped_lines, 0);
    let saved = env.store().file(&f.to_string_lossy()).unwrap().unwrap();
    assert_eq!(
        saved.offset as usize,
        text.len() - cut,
        "offset stops before the partial line"
    );
    append(&f, &format!("{}\n", &last[cut..]));
    let r = env.run(&o);
    assert_eq!(r.health.events, 1);
    assert_eq!(r.health.skipped_lines, 0);
    assert_eq!(
        env.totals()["model-a"],
        sum(&[&U1, &U1, &U1, &U1, &U1, &U1])
    );
}

#[test]
fn over_long_lines_are_skipped_and_counted() {
    let env = Env::new();
    let f = env.claude("-work-app/sess-1.jsonl");
    write_lines(
        &f,
        &[
            claude_line("msg_big", "req_big", TS, "model-a", &U2, 5000),
            claude_line("msg_ok", "req_ok", TS, "model-a", &U1, 0),
        ],
    );
    let mut o = env.opts();
    o.chunk_bytes = 256;
    o.max_line = 1024;
    let r = env.run(&o);
    assert_eq!(r.health.skipped_lines, 1);
    assert_eq!(env.totals()["model-a"], U1.tokens());
}

fn bump_mtime(p: &std::path::Path, secs: u64) {
    let f = fs::OpenOptions::new().write(true).open(p).unwrap();
    f.set_modified(SystemTime::now() + Duration::from_secs(secs))
        .unwrap();
}

#[test]
fn truncated_below_the_offset_with_an_unchanged_head_is_read_again() {
    let env = Env::new();
    let f = env.claude("-work-app/sess-1.jsonl");
    let lines: Vec<String> = (0..10)
        .map(|i| {
            claude_line(
                &format!("msg_{i}"),
                &format!("req_{i}"),
                TS,
                "model-a",
                &U1,
                200,
            )
        })
        .collect();
    write_lines(&f, &lines);
    env.run(&env.opts());
    // Cut back to 8 lines (the first 4 KB are unchanged), then a new message.
    let mut kept = lines[..8].to_vec();
    kept.push(claude_line("msg_x", "req_x", TS, "model-a", &U2, 0));
    assert!(kept.join("\n").len() > 4096 && kept.join("\n").len() < lines.join("\n").len());
    write_lines(&f, &kept);
    bump_mtime(&f, 5);
    let r = env.run(&env.opts());
    assert_eq!(r.health.rewrites, 1);
    assert_eq!(r.health.events, 1);
    let mut want = Tokens::default();
    for _ in 0..10 {
        want.add(&U1.tokens());
    }
    want.add(&U2.tokens());
    assert_eq!(env.totals()["model-a"], want);
}

#[test]
fn rewritten_and_truncated_files_are_detected_and_not_double_counted() {
    let env = Env::new();
    let f = env.claude("-work-app/sess-1.jsonl");
    let a = claude_line("msg_a", "req_a", TS, "model-a", &U1, 0);
    let b = claude_line("msg_b", "req_b", TS, "model-a", &U1, 0);
    let c = claude_line("msg_c", "req_c", TS, "model-a", &U2, 0);
    write_lines(&f, &[a.clone(), b.clone()]);
    env.run(&env.opts());

    // Replaced by a new file (new inode) that repeats a and b and adds c.
    let tmp = f.with_extension("tmp");
    write_lines(&tmp, &[a.clone(), b.clone(), c.clone()]);
    fs::rename(&tmp, &f).unwrap();
    let r = env.run(&env.opts());
    assert_eq!(r.health.rewrites, 1);
    assert_eq!(r.health.events, 1);
    assert_eq!(env.totals()["model-a"], sum(&[&U1, &U1, &U2]));

    // Truncated in place below the saved offset, then a new message.
    let d = claude_line("msg_d", "req_d", TS, "model-a", &U2, 0);
    write_lines(&f, &[d]);
    bump_mtime(&f, 5);
    let r = env.run(&env.opts());
    assert_eq!(r.health.rewrites, 1);
    assert_eq!(env.totals()["model-a"], sum(&[&U1, &U1, &U2, &U2]));

    // Same inode, longer, but the first bytes changed: still a rewrite.
    let e = claude_line("msg_e", "req_e", TS, "model-a", &U1, 0);
    let f2 = claude_line("msg_f", "req_f", TS, "model-a", &U1, 0);
    write_lines(&f, &[e, f2]);
    bump_mtime(&f, 10);
    let r = env.run(&env.opts());
    assert_eq!(r.health.rewrites, 1);
    assert_eq!(
        env.totals()["model-a"],
        sum(&[&U1, &U1, &U2, &U2, &U1, &U1])
    );
}

#[test]
fn budget_stop_resumes_without_double_counting() {
    let env = Env::new();
    let want = big_fixture(&env, 3, 40, TS);
    let mut o = env.opts();
    o.chunk_bytes = 2048;
    o.chunk_limit = Some(3);
    o.force = false;
    let mut runs = 0;
    loop {
        runs += 1;
        let r = match sync::run(&o).unwrap() {
            Outcome::Ran(r) => r,
            other => panic!("a run with pending bytes must not be skipped as fresh: {other:?}"),
        };
        let doc = r.summary.as_ref().unwrap();
        if !r.stopped_by_budget {
            assert_eq!(r.pending_bytes, 0);
            assert_eq!(doc["complete"], true);
            break;
        }
        assert!(r.pending_bytes > 0);
        assert_eq!(doc["complete"], false);
        assert_eq!(doc["pending"]["bytes"], r.pending_bytes);
        assert!(runs < 200, "never finished");
    }
    assert!(runs > 3, "the limit should have sliced the import");
    assert_eq!(env.totals()["model-a"], want);
    // Complete and fresh now: the next run does nothing.
    assert!(matches!(sync::run(&o).unwrap(), Outcome::Fresh { .. }));
}

#[test]
fn omp_title_edits_in_place_are_not_rewrites() {
    let env = Env::new();
    let f = env.omp("-work-app/2026-01-15_sess.jsonl");
    let title = |t: &str, pad: usize| {
        format!(
            r#"{{"type":"title","v":1,"title":"{t}","pad":"{}"}}"#,
            " ".repeat(pad)
        )
    };
    let session = r#"{"type":"session","version":3,"id":"omp-sess-2","timestamp":"2026-01-15T09:00:00Z","cwd":"/work/app"}"#;
    let msg = |id: &str| {
        format!(
            r#"{{"type":"message","id":"{id}","timestamp":"2026-01-15T10:01:00Z","message":{{"role":"assistant","model":"model-o","usage":{{"input":10,"output":20,"cacheRead":30,"cacheWrite":0,"totalTokens":60,"cost":{{"total":0.01}}}}}}}}"#
        )
    };
    let body = |t: String, ids: &[&str]| {
        let mut v = vec![t, session.to_string()];
        v.extend(ids.iter().map(|i| msg(i)));
        v
    };
    write_lines(&f, &body(title("FAKE-A", 20), &["e1"]));
    env.run(&env.opts());
    // omp renames the session: same line length (the pad shrinks), then appends.
    write_lines(&f, &body(title("FAKE-RENAMED", 14), &["e1", "e2"]));
    bump_mtime(&f, 5);
    let r = env.run(&env.opts());
    assert_eq!(
        r.health.rewrites, 0,
        "a same-length title edit is not a rewrite"
    );
    assert_eq!(r.health.events, 1);
    // A longer title shifts every offset: that is a rewrite, and the dedupe set absorbs it.
    write_lines(
        &f,
        &body(title("FAKE-A-MUCH-LONGER-TITLE", 40), &["e1", "e2", "e3"]),
    );
    bump_mtime(&f, 10);
    let r = env.run(&env.opts());
    assert_eq!(r.health.rewrites, 1);
    assert_eq!(r.health.events, 1);
    assert_eq!(env.totals()["model-o"].total(), 180);
}

#[test]
fn omp_reported_usd_is_kept_and_wins_over_the_table() {
    let env = Env::new();
    let f = env.omp("-work-app/2026-01-15_sess.jsonl");
    write_lines(
        &f,
        &[
            r#"{"type":"title","v":1,"title":"FAKE-TITLE"}"#.to_string(),
            r#"{"type":"session","version":3,"id":"omp-sess-1","timestamp":"2026-01-15T09:00:00Z","cwd":"/work/app"}"#.to_string(),
            r#"{"type":"message","id":"e1","timestamp":"2026-01-15T10:01:00Z","message":{"role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":"FAKE"}],"usage":{"input":1000,"output":2000,"cacheRead":30000,"cacheWrite":400,"totalTokens":33400,"cost":{"total":0.5}}}}"#.to_string(),
            r#"{"type":"model_usage","id":"e2","timestamp":"2026-01-15T10:02:00Z","purpose":"title","model":"claude-opus-5-5","usage":{"input":10,"output":5,"cacheRead":0,"cacheWrite":0,"totalTokens":15,"cost":{"total":0.25}}}"#.to_string(),
        ],
    );
    let r = env.run(&env.opts());
    assert_eq!(r.health.events, 2);
    let doc = r.summary.unwrap();
    let total = doc["totals"]
        .as_array()
        .unwrap()
        .iter()
        .find(|t| t["harness"] == "omp")
        .unwrap()
        .clone();
    assert_eq!(total["usd"], 0.75);
    assert_eq!(total["usd_src"], "reported");
    let row = &doc["hourly"][0];
    assert_eq!(row["usd_src"], "reported");
    assert!(row.get("branch").is_none(), "omp records no branch");
    let rows = env.store().rows_since(i64::MIN).unwrap();
    assert_eq!(rows[0].reported_usd, Some(0.75));
}

#[test]
fn unknown_models_are_unpriced_never_zero() {
    let env = Env::new();
    let f = env.claude("-work-app/sess-1.jsonl");
    write_lines(
        &f,
        &[
            claude_line("msg_1", "req_1", TS, "model-nobody-prices", &U1, 0),
            claude_line("msg_2", "req_2", TS, "claude-sonnet-5-5", &U1, 0),
        ],
    );
    let doc = env.run(&env.opts()).summary.unwrap();
    for t in doc["totals"].as_array().unwrap() {
        if t["model"] == "model-nobody-prices" {
            assert_eq!(t["priced"], false);
            assert!(t.get("usd").is_none());
        } else {
            assert!(t["usd"].as_f64().unwrap() > 0.0);
            assert!(t.get("priced").is_none());
        }
    }
    assert_eq!(
        doc["sources"]["claude-code"]["unknown_models"][0],
        "model-nobody-prices"
    );
    assert!(
        doc["prices"]["label"]
            .as_str()
            .unwrap()
            .contains("estimate")
    );
    assert_eq!(doc["prices"]["date"], meter::prices::BUNDLED_DATE);
}

#[test]
fn hourly_upserts_are_idempotent() {
    let env = Env::new();
    let f = env.claude("-work-app/sess-1.jsonl");
    write_lines(&f, &[claude_line("msg_1", "req_1", TS, "model-a", &U1, 0)]);
    env.run(&env.opts());
    let before = env.store().rows_since(i64::MIN).unwrap();
    // Running again over the same data, and re-reading the whole file, changes nothing.
    env.run(&env.opts());
    env.store()
        .conn
        .execute("UPDATE files SET offset = 0, size = 0", [])
        .unwrap();
    env.run(&env.opts());
    assert_eq!(env.store().rows_since(i64::MIN).unwrap(), before);
    // New usage in the same hour updates the same row.
    append(
        &f,
        &(claude_line("msg_2", "req_2", "2026-01-15T10:59:59Z", "model-a", &U2, 0) + "\n"),
    );
    env.run(&env.opts());
    let after = env.store().rows_since(i64::MIN).unwrap();
    assert_eq!(after.len(), 1);
    assert_eq!(after[0].key.id(), before[0].key.id());
    assert_eq!(after[0].tokens, sum(&[&U1, &U2]));
    // The next hour is a new row.
    append(
        &f,
        &(claude_line("msg_3", "req_3", "2026-01-15T11:00:00Z", "model-a", &U2, 0) + "\n"),
    );
    env.run(&env.opts());
    assert_eq!(env.store().rows_since(i64::MIN).unwrap().len(), 2);
    let envelope: String = env
        .store()
        .conn
        .query_row("SELECT envelope FROM ae_usage LIMIT 1", [], |r| r.get(0))
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&envelope).unwrap();
    assert_eq!(v["schema"], "ae/1");
    assert_eq!(v["type"], "agent.usage");
    assert_eq!(v["idempotency_key"], v["id"]);
}

#[test]
fn symlinks_out_of_the_source_roots_are_not_read() {
    let env = Env::new();
    let outside = env.dir.path().join("elsewhere");
    fs::create_dir_all(&outside).unwrap();
    write_lines(
        &outside.join("x.jsonl"),
        &[claude_line("msg_1", "req_1", TS, "model-a", &U1, 0)],
    );
    let root = env.home().join(".claude/projects");
    fs::create_dir_all(&root).unwrap();
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(&outside, root.join("linked-dir")).unwrap();
        std::os::unix::fs::symlink(outside.join("x.jsonl"), root.join("linked.jsonl")).unwrap();
    }
    let r = env.run(&env.opts());
    assert_eq!(r.files_seen, 0);
    assert!(env.totals().is_empty());
}

#[test]
fn no_prompt_text_titles_or_secrets_reach_the_store_or_the_doc() {
    let env = Env::new();
    let f = env.claude("-work-app/sess-1.jsonl");
    write_lines(
        &f,
        &[
            user_line("FAKE-PROMPT-TEXT sk-FAKE0000"),
            claude_line("msg_1", "req_1", TS, "model-a", &U1, 0),
            r#"{"type":"ai-title","sessionId":"sess-1","aiTitle":"FAKE-TITLE"}"#.to_string(),
        ],
    );
    let doc = env.run(&env.opts()).summary.unwrap();
    let doc = serde_json::to_string(&doc).unwrap();
    let mut bytes = Vec::new();
    for entry in fs::read_dir(env.state()).unwrap() {
        bytes.extend(fs::read(entry.unwrap().path()).unwrap());
    }
    let hay = String::from_utf8_lossy(&bytes);
    for needle in ["FAKE-PROMPT-TEXT", "sk-FAKE0000", "FAKE-TITLE"] {
        assert!(
            !hay.contains(needle),
            "{needle} reached the state directory"
        );
        assert!(!doc.contains(needle), "{needle} reached the doc");
    }
    assert!(!doc.contains("/work/app"), "the doc carries no paths");
}

#[test]
fn single_flight_and_staleness() {
    let env = Env::new();
    let f = env.claude("-work-app/sess-1.jsonl");
    write_lines(&f, &[claude_line("msg_1", "req_1", TS, "model-a", &U1, 0)]);
    let mut o = env.opts();
    o.force = false;
    assert!(matches!(sync::run(&o).unwrap(), Outcome::Ran(_)));
    assert!(matches!(sync::run(&o).unwrap(), Outcome::Fresh { .. }));
    o.now_ms += 11 * 60 * 1000;
    let held = meter::paths::try_lock(&env.state()).unwrap().unwrap();
    assert!(matches!(sync::run(&o).unwrap(), Outcome::Locked));
    drop(held);
    assert!(matches!(sync::run(&o).unwrap(), Outcome::Ran(_)));
}

#[test]
fn the_first_run_imports_14_days_and_backfill_imports_the_rest() {
    let env = Env::new();
    let f = env.claude("-work-app/sess-1.jsonl");
    write_lines(
        &f,
        &[
            claude_line(
                "msg_old",
                "req_old",
                "2025-12-01T10:00:00Z",
                "model-a",
                &U2,
                0,
            ),
            claude_line("msg_new", "req_new", TS, "model-a", &U1, 0),
        ],
    );
    let r = env.run(&env.opts());
    assert_eq!(meter::time::format_day(r.counted_from_ms), "2026-01-02");
    assert_eq!(env.totals()["model-a"], U1.tokens());
    assert_eq!(r.summary.unwrap()["counted_from"], "2026-01-02T00:00:00Z");
    let mut o = env.opts();
    o.backfill = true;
    let r = env.run(&o);
    assert!(r.reimported);
    assert_eq!(r.counted_from_ms, 0);
    assert_eq!(env.totals()["model-a"], sum(&[&U1, &U2]));
}

#[test]
fn a_chunk_commits_its_rows_keys_and_offset_together() {
    use meter::event::{Precision, RollupKey};
    use meter::store::{Attributed, FileState, Health, Store};
    let mut store = Store::open_in_memory().unwrap();
    let file = FileState {
        path: "/logs/a.jsonl".into(),
        source: "claude-code".into(),
        offset: 4096,
        ..Default::default()
    };
    let event = Attributed {
        dedupe_key: "claude|msg_1|req_1".into(),
        key: RollupKey {
            bot: None,
            role: None,
            harness: "claude-code".into(),
            model: "model-a".into(),
            machine: "m".into(),
            repo: None,
            branch: None,
            hour_ms: 0,
        },
        squad: None,
        tokens: U1.tokens(),
        precision: Precision::Exact,
        reported_usd: None,
        session: None,
        parent_session: None,
        ts_ms: 0,
    };
    store
        .apply_chunk(&file, std::slice::from_ref(&event), Health::default(), 1)
        .unwrap();
    assert_eq!(
        store.file("/logs/a.jsonl").unwrap().unwrap().offset,
        4096,
        "the offset is saved with the rows"
    );
    assert_eq!(store.count("seen").unwrap(), 1);
    // Applying the same chunk again adds nothing.
    let h = store
        .apply_chunk(&file, &[event], Health::default(), 2)
        .unwrap();
    assert_eq!((h.events, h.duplicates), (0, 1));
    assert_eq!(store.rows_since(i64::MIN).unwrap()[0].tokens, U1.tokens());
}
