//! Spawns processes, so it runs in its own test binary: a child forked while another test
//! thread holds the sync lock would inherit it for a moment.

mod common;

use std::fs;
use std::time::Duration;

use common::*;

const TS: &str = "2026-01-15T10:15:00.000Z";

#[test]
fn a_killed_run_resumes_without_double_counting() {
    let env = Env::new();
    let ts = meter::time::format_rfc3339(meter::time::now_ms() - 3_600_000);
    let want = big_fixture(&env, 6, 3000, &ts);
    let bin = env!("CARGO_BIN_EXE_meter");
    let spawn = || {
        std::process::Command::new(bin)
            .args(["sync", "--force", "--no-publish"])
            .env("HOME", env.home())
            .env("METER_STATE_DIR", env.state())
            .env("METER_HC", env.dir.path().join("no-hc"))
            .env_remove("CLAUDE_CONFIG_DIR")
            .stdout(std::process::Stdio::null())
            .spawn()
            .unwrap()
    };
    // Kill one run right after its first commit, mid-import (SIGKILL: no cleanup runs).
    let mut child = spawn();
    let db = env.state().join("meter.db");
    let started = std::time::Instant::now();
    while child.try_wait().unwrap().is_none() && started.elapsed() < Duration::from_secs(60) {
        let committed = db.exists()
            && meter::store::Store::open(&db).is_ok_and(|s| s.count("files").unwrap_or(0) > 0);
        if committed {
            break;
        }
        std::thread::sleep(Duration::from_millis(1));
    }
    let _ = child.kill();
    let _ = child.wait();
    let partial = env.totals().get("model-a").copied().unwrap_or_default();
    assert_ne!(partial, want, "the kill should land mid-import");
    assert!(spawn().wait().unwrap().success());
    assert_eq!(env.totals()["model-a"], want);
}

#[test]
fn repo_key_strips_credentials_and_collapses_worktrees() {
    let git = |dir: &std::path::Path, args: &[&str]| {
        std::process::Command::new("git")
            .arg("-C")
            .arg(dir)
            .args(args)
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    };
    let env = Env::new();
    let repo = env.dir.path().join("src/app");
    fs::create_dir_all(repo.join("sub")).unwrap();
    if !git(&repo, &["init", "-q"]) {
        eprintln!("git not available; skipping");
        return;
    }
    assert!(git(
        &repo,
        &[
            "remote",
            "add",
            "origin",
            "https://someone:FAKE-TOKEN-123@Git.Example.com/acme/app.git?x=FAKE#y"
        ]
    ));
    let f = env.claude("-src-app/sess-1.jsonl");
    let gone = repo.join(".claude/worktrees/agent-1"); // a deleted worktree inside the repo
    let mut lines = Vec::new();
    for (i, cwd) in [repo.join("sub"), gone].iter().enumerate() {
        let l = claude_line(
            &format!("msg_{i}"),
            &format!("req_{i}"),
            TS,
            "model-a",
            &U1,
            0,
        )
        .replace(
            "\"cwd\":\"/work/app\"",
            &format!(
                "\"cwd\":{}",
                serde_json::to_string(&cwd.to_string_lossy()).unwrap()
            ),
        );
        lines.push(l);
    }
    write_lines(&f, &lines);
    let mut o = env.opts();
    o.resolve_repos = true;
    env.run(&o);
    let rows = env.store().rows_since(i64::MIN).unwrap();
    assert_eq!(
        rows.len(),
        1,
        "a subdirectory and a worktree resolve to one repo"
    );
    assert_eq!(
        rows[0].key.repo.as_deref(),
        Some("git.example.com/acme/app")
    );
    let db = fs::read(env.state().join("meter.db")).unwrap();
    assert!(!String::from_utf8_lossy(&db).contains("FAKE-TOKEN-123"));
}
