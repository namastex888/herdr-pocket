//! The summary doc (schema 2). Synthetic rows only.

use meter::event::{RollupKey, Tokens};
use meter::prices::PriceTable;
use meter::store::UsageRow;
use meter::summary::{self, Input, SourceHealth};
use meter::time::{DAY_MS, HOUR_MS, hour_start, parse_rfc3339};
use serde_json::Value;

const NOW: &str = "2026-01-15T12:30:00Z";

fn now() -> i64 {
    parse_rfc3339(NOW).unwrap()
}

fn tok(input: i64, cr: i64, out: i64, unsplit: i64) -> Tokens {
    Tokens {
        input_uncached: input,
        cache_read: cr,
        cache_write_5m: 5,
        cache_write_1h: 5,
        output: out,
        unsplit,
        ..Tokens::default()
    }
}

fn row(
    harness: &str,
    model: &str,
    repo: Option<&str>,
    branch: Option<&str>,
    hour_ms: i64,
    tokens: Tokens,
) -> UsageRow {
    UsageRow {
        key: RollupKey {
            bot: None,
            role: None,
            harness: harness.into(),
            model: model.into(),
            machine: "machine-1".into(),
            repo: repo.map(Into::into),
            branch: branch.map(Into::into),
            hour_ms,
        },
        squad: None,
        tokens,
        precision: if tokens.unsplit > 0 {
            "total_only".into()
        } else {
            "exact".into()
        },
        reported_usd: None,
        events: 1,
    }
}

fn sources() -> Vec<SourceHealth> {
    ["omp", "claude-code", "codex"]
        .iter()
        .map(|h| SourceHealth {
            harness: (*h).into(),
            status: "ok".into(),
            ..Default::default()
        })
        .collect()
}

fn build(rows: &[UsageRow], cap: usize) -> (Value, usize) {
    let prices = PriceTable::bundled();
    let sources = sources();
    summary::build(
        &Input {
            rows,
            prices: &prices,
            now_ms: now(),
            machine: "machine-1",
            meter_version: "0.0.0",
            counted_from_ms: now() - 40 * DAY_MS,
            pending_bytes: 0,
            pending_files: 0,
            sources: &sources,
        },
        cap,
    )
}

fn tokens_of(t: &Value) -> i64 {
    ["in", "cr", "cw", "out", "un"]
        .iter()
        .map(|k| t.get(*k).and_then(Value::as_i64).unwrap_or(0))
        .sum()
}

#[test]
fn the_doc_has_the_documented_shape() {
    let h = hour_start(now());
    let rows = vec![
        row(
            "claude-code",
            "claude-opus-5-5",
            Some("git.example.com/acme/app"),
            Some("feat/a"),
            h,
            tok(10, 1000, 50, 0),
        ),
        row(
            "claude-code",
            "claude-opus-5-5",
            Some("git.example.com/acme/app"),
            Some("feat/a"),
            h - 30 * HOUR_MS,
            tok(10, 1000, 50, 0),
        ),
        row(
            "codex",
            "model-x",
            Some("git.example.com/acme/app"),
            Some("main"),
            h - 3 * DAY_MS,
            tok(1, 2, 3, 0),
        ),
        row(
            "omp",
            "claude-opus-5-5",
            None,
            None,
            h - 20 * DAY_MS,
            tok(1, 2, 3, 0),
        ),
        // Older than 30 days: in the store, not in the doc.
        row(
            "omp",
            "claude-opus-5-5",
            None,
            None,
            h - 35 * DAY_MS,
            tok(1, 2, 3, 0),
        ),
    ];
    let (doc, size) = build(&rows, summary::DEFAULT_CAP_BYTES);
    assert_eq!(serde_json::to_vec(&doc).unwrap().len(), size);
    assert_eq!(doc["schema_version"], 2);
    for key in [
        "kind",
        "rows_schema",
        "meter_version",
        "generatedAt",
        "machine",
        "counted_from",
        "complete",
        "pending",
        "prices",
        "sources",
        "windows",
        "models",
        "projects",
        "tasks",
        "hourly",
        "daily",
        "truncated",
    ] {
        assert!(doc.get(key).is_some(), "{key} missing");
    }
    assert!(doc.get("projects_other").is_none());
    assert!(doc.get("tasks_other").is_none());
    assert_eq!(doc["truncated"].as_array().unwrap().len(), 0);
    assert_eq!(doc["windows"]["d1"], "2026-01-14T13:00:00Z");
    assert_eq!(doc["windows"]["d30"], "2025-12-16T13:00:00Z");
    for s in ["omp", "claude-code", "codex"] {
        assert_eq!(doc["sources"][s]["status"], "ok");
    }
    assert_eq!(doc["sources"]["codex"]["unknown_models"][0], "model-x");

    let models = doc["models"].as_array().unwrap();
    assert_eq!(models.len(), 3);
    let opus = models
        .iter()
        .find(|m| m["harness"] == "claude-code")
        .unwrap();
    assert_eq!(
        tokens_of(&opus["d1"]),
        1070,
        "d1 holds the current hour only"
    );
    assert_eq!(tokens_of(&opus["d7"]), 2140);
    assert!(opus["d30"]["usd"].as_f64().unwrap() > 0.0);
    let omp = models.iter().find(|m| m["harness"] == "omp").unwrap();
    assert!(omp.get("d7").is_none(), "an empty window is left out");
    assert_eq!(
        tokens_of(&omp["d30"]),
        16,
        "rows older than 30 days are left out"
    );

    let projects = doc["projects"].as_array().unwrap();
    assert_eq!(projects.len(), 2);
    let app = &projects[0];
    assert_eq!(app["repo"], "git.example.com/acme/app");
    assert_eq!(app["tasks"], 1);
    assert_eq!(app["hours"], 3);
    assert_eq!(app["last"], "2026-01-15T12:00:00Z");
    assert_eq!(tokens_of(&app["untasked"]), 16, "main is not a task");
    assert_eq!(app["usd_per_task"], opus["d30"]["usd"]);
    assert_eq!(projects[1]["repo"], Value::Null);

    let tasks = doc["tasks"].as_array().unwrap();
    assert_eq!(tasks.len(), 1);
    assert_eq!(tasks[0]["branch"], "feat/a");
    assert_eq!(tasks[0]["agents"][0], "claude-code");
    assert_eq!(tasks[0]["first"], "2026-01-14T06:00:00Z");
    assert_eq!(tasks[0]["hours"], 2);

    let hourly = &doc["hourly"];
    assert_eq!(hourly["hours"], 48);
    assert_eq!(hourly["from"], "2026-01-13T13:00:00Z");
    let s = &hourly["series"][0];
    assert_eq!(s["harness"], "claude-code");
    assert_eq!(s["in"].as_array().unwrap().len(), 48);
    assert_eq!(s["in"][47], 10);
    assert_eq!(s["in"][17], 10);
    assert_eq!(doc["daily"]["days"], 30);
    assert_eq!(doc["daily"]["from"], "2025-12-17");
    assert_eq!(doc["daily"]["series"].as_array().unwrap().len(), 3);
}

#[test]
fn total_only_and_unpriced_tokens_never_unprice_the_rest() {
    let h = hour_start(now());
    let repo = Some("git.example.com/acme/app");
    let rows = vec![
        row(
            "claude-code",
            "claude-opus-5-5",
            repo,
            Some("feat/a"),
            h,
            tok(10, 1000, 50, 0),
        ),
        // A Codex thread known only by its total, on the same task.
        row(
            "codex",
            "model-x",
            repo,
            Some("feat/a"),
            h,
            tok(0, 0, 0, 7000),
        ),
    ];
    let (doc, _) = build(&rows, summary::DEFAULT_CAP_BYTES);
    let only_priced = build(&rows[..1], summary::DEFAULT_CAP_BYTES).0;
    let t = &doc["tasks"][0]["d30"];
    assert_eq!(t["un"], 7000);
    assert_eq!(
        t["unpriced"],
        7000 + 10,
        "codex's split part (5+5 cache writes) is unpriced too"
    );
    assert_eq!(t["usd"], only_priced["tasks"][0]["d30"]["usd"]);
    assert_eq!(
        doc["tasks"][0]["agents"],
        serde_json::json!(["claude-code", "codex"])
    );
    let codex = doc["models"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["harness"] == "codex")
        .unwrap();
    assert_eq!(codex["priced"], false);
    assert!(codex["d30"].get("usd").is_none());
    assert_eq!(doc["hourly"]["series"][1]["un"][47], 7000);
}

/// A machine much busier than any seen so far: 3 harnesses, 20 models, 60 repos and 500
/// branches, active every hour for 30 days.
fn heavy_corpus() -> Vec<UsageRow> {
    let h = hour_start(now());
    let harnesses = ["claude-code", "codex", "omp"];
    let mut rows = Vec::new();
    let mut seed: u64 = 7;
    let mut next = |n: u64| {
        seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
        (seed >> 33) % n
    };
    for hour in 0..30 * 24 {
        for _ in 0..20 {
            let model = next(20);
            let harness = harnesses[(model % 3) as usize];
            let repo = format!("git.example.com/team-{}/service-{}", next(6), next(10));
            let branch = format!("feat/issue-{}-some-descriptive-branch-name", next(500));
            let m = if model == 0 {
                "claude-opus-5-5".to_string()
            } else {
                format!("model-{model:02}")
            };
            rows.push(row(
                harness,
                &m,
                Some(&repo),
                Some(&branch),
                h - hour * HOUR_MS,
                tok(
                    123_456,
                    98_765_432,
                    54_321,
                    if model == 5 { 1_000_000 } else { 0 },
                ),
            ));
        }
    }
    rows
}

#[test]
fn a_heavy_month_fits_the_cap_and_keeps_repo_and_branch() {
    let rows = heavy_corpus();
    let (full, full_size) = build(&rows, usize::MAX);
    assert!(
        full_size > summary::DEFAULT_CAP_BYTES,
        "the corpus must overflow the cap"
    );
    let all_tokens: i64 = full["models"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| tokens_of(&m["d30"]))
        .sum();
    for cap in [summary::DEFAULT_CAP_BYTES, 24 * 1024] {
        let (doc, size) = build(&rows, cap);
        assert!(size <= cap, "{size} > {cap}");
        assert_eq!(serde_json::to_vec(&doc).unwrap().len(), size);
        assert!(!doc["truncated"].as_array().unwrap().is_empty());
        // Models are never cut.
        assert_eq!(doc["models"], full["models"]);
        // Kept entries keep their repo and branch.
        for t in doc["tasks"].as_array().unwrap() {
            assert!(t["repo"].as_str().unwrap().starts_with("git.example.com/"));
            assert!(t["branch"].as_str().unwrap().starts_with("feat/"));
        }
        for p in doc["projects"].as_array().unwrap() {
            assert!(p["repo"].as_str().is_some());
        }
        // What is cut is summed, never lost.
        let projects: i64 = doc["projects"]
            .as_array()
            .unwrap()
            .iter()
            .map(|p| tokens_of(&p["d30"]))
            .sum::<i64>()
            + doc
                .get("projects_other")
                .map_or(0, |o| tokens_of(&o["d30"]));
        assert_eq!(projects, all_tokens);
        let tasks: i64 = doc["tasks"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| tokens_of(&t["d30"]))
            .sum::<i64>()
            + doc.get("tasks_other").map_or(0, |o| tokens_of(&o["d30"]));
        assert_eq!(tasks, all_tokens);
    }
    // At 64 KiB this corpus still shows every project and the hourly window whole.
    let (doc, _) = build(&rows, summary::DEFAULT_CAP_BYTES);
    assert!(doc.get("projects_other").is_none());
    assert_eq!(doc["hourly"]["hours"], 48);
    assert!(doc["tasks"].as_array().unwrap().len() >= 50);
}
