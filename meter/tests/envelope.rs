//! `ae/1` revision 3 rows: precision is part of the row identity, and an envelope carries the
//! split fields or `unsplit`, never both. Synthetic fixtures only.

mod common;

use common::*;
use meter::event::{Precision, RollupKey, Tokens};
use meter::prices::PriceTable;
use meter::sources::codex::CodexCtx;
use meter::store::{Attributed, Health, Store, UsageRow};
use meter::summary::{self, Input};
use meter::time::{HOUR_MS, parse_rfc3339};
use serde_json::Value;

const SPLIT: [&str; 6] = [
    "input_uncached",
    "cache_read",
    "cache_write_5m",
    "cache_write_1h",
    "output",
    "reasoning",
];

fn hour() -> i64 {
    parse_rfc3339("2026-01-15T10:00:00Z").unwrap()
}

fn key() -> RollupKey {
    RollupKey {
        bot: None,
        role: None,
        harness: "codex".into(),
        model: "model-c".into(),
        machine: "machine-1".into(),
        repo: Some("example.com/acme/app".into()),
        branch: Some("feat/x".into()),
        hour_ms: hour(),
    }
}

fn exact() -> Tokens {
    Tokens {
        input_uncached: 30,
        cache_read: 200,
        output: 40,
        reasoning: 10,
        ..Tokens::default()
    }
}

fn whole() -> Tokens {
    Tokens {
        unsplit: 700,
        ..Tokens::default()
    }
}

fn ev(tokens: Tokens, precision: Precision, minute: i64) -> Attributed {
    Attributed {
        dedupe_key: String::new(),
        key: key(),
        squad: None,
        tokens,
        precision,
        reported_usd: None,
        session: None,
        parent_session: None,
        ts_ms: hour() + minute * 60_000,
    }
}

fn apply(store: &mut Store, events: &[Attributed]) {
    let ctx = CodexCtx {
        thread: "thread-1".into(),
        model: "model-c".into(),
        ..CodexCtx::default()
    };
    store
        .apply_codex(&ctx, events, Health::default(), hour())
        .unwrap();
}

fn envelopes(store: &Store) -> Vec<Value> {
    let mut st = store
        .conn
        .prepare("SELECT envelope FROM ae_usage ORDER BY id")
        .unwrap();
    st.query_map([], |r| r.get::<_, String>(0))
        .unwrap()
        .map(|s| serde_json::from_str(&s.unwrap()).unwrap())
        .collect()
}

fn by_precision(store: &Store, p: &str) -> UsageRow {
    let rows = store.rows_since(i64::MIN).unwrap();
    let mut it = rows.into_iter().filter(|r| r.precision == p);
    let r = it.next().unwrap_or_else(|| panic!("no {p} row"));
    assert!(it.next().is_none(), "more than one {p} row");
    r
}

#[test]
fn exact_and_total_only_in_one_hour_are_two_rows_and_keep_their_sums() {
    // Both orders: total_only first used to flip an exact row into a mixed one.
    for order in [[0, 1], [1, 0]] {
        let mut store = Store::open_in_memory().unwrap();
        let events = [
            ev(exact(), Precision::Exact, 5),
            ev(whole(), Precision::TotalOnly, 6),
        ];
        apply(&mut store, &[events[order[0]].clone()]);
        apply(&mut store, &[events[order[1]].clone()]);
        // A second batch of each lands on the same two rows.
        apply(&mut store, &events);

        let rows = store.rows_since(i64::MIN).unwrap();
        assert_eq!(rows.len(), 2);
        let e = by_precision(&store, "exact");
        let t = by_precision(&store, "total_only");
        let mut two = exact();
        two.add(&exact());
        assert_eq!(e.tokens, two);
        assert_eq!(t.tokens.unsplit, 1400);
        assert_eq!(t.tokens.split_total() + t.tokens.reasoning, 0);
        assert_eq!(e.events + t.events, 4);
        assert_ne!(e.id, t.id);
        assert_eq!(e.id, key().id(Precision::Exact));
        assert_eq!(t.id, key().id(Precision::TotalOnly));
    }
}

#[test]
fn a_total_only_envelope_has_no_split_keys_and_an_exact_one_has_no_unsplit() {
    let mut store = Store::open_in_memory().unwrap();
    apply(
        &mut store,
        &[
            ev(exact(), Precision::Exact, 1),
            ev(whole(), Precision::TotalOnly, 2),
        ],
    );
    let envs = envelopes(&store);
    assert_eq!(envs.len(), 2);
    for v in &envs {
        assert_eq!(v["schema"], "ae/1");
        assert_eq!(v["idempotency_key"], v["id"]);
        let data = v["data"].as_object().unwrap();
        match data["precision"].as_str().unwrap() {
            "total_only" => {
                for k in SPLIT {
                    assert!(!data.contains_key(k), "total_only row carries {k}: {v}");
                }
                assert_eq!(data["unsplit"], 700);
            }
            "exact" => {
                assert!(
                    !data.contains_key("unsplit"),
                    "exact row carries unsplit: {v}"
                );
                assert_eq!(data["input_uncached"], 30);
                assert_eq!(data["cache_read"], 200);
                assert_eq!(data["cache_write_5m"], 0);
                assert_eq!(data["output"], 40);
                assert_eq!(data["reasoning"], 10);
            }
            p => panic!("unexpected precision {p}"),
        }
    }
}

#[test]
fn two_stores_built_from_the_same_input_have_identical_ids() {
    let events = [
        ev(exact(), Precision::Exact, 1),
        ev(whole(), Precision::TotalOnly, 2),
    ];
    let ids = |events: &[Attributed]| {
        let mut s = Store::open_in_memory().unwrap();
        apply(&mut s, events);
        let mut v: Vec<String> = envelopes(&s)
            .iter()
            .map(|e| e["id"].as_str().unwrap().to_string())
            .collect();
        v.sort();
        v
    };
    assert_eq!(ids(&events), ids(&events));

    // Through a whole sync too, with the machine pinned (it is part of the key).
    let run = || {
        let env = Env::new();
        std::fs::create_dir_all(env.state()).unwrap();
        env.store().set_meta("machine", "machine-1").unwrap();
        let f = env.claude("-proj/s1.jsonl");
        write_lines(
            &f,
            &[
                claude_line("msg_1", "req_1", "2026-01-15T10:00:00Z", "model-a", &U1, 0),
                claude_line("msg_2", "req_2", "2026-01-15T11:00:00Z", "model-a", &U2, 0),
            ],
        );
        env.run(&env.opts());
        let mut ids: Vec<String> = env
            .store()
            .rows_since(i64::MIN)
            .unwrap()
            .into_iter()
            .map(|r| r.id)
            .collect();
        ids.sort();
        ids
    };
    let a = run();
    assert_eq!(a.len(), 2);
    assert_eq!(a, run());
}

#[test]
fn a_store_from_before_revision_3_is_rebuilt_once_without_double_counting() {
    let env = Env::new();
    let f = env.claude("-proj/s1.jsonl");
    write_lines(
        &f,
        &[
            claude_line("msg_1", "req_1", "2026-01-15T10:00:00Z", "model-a", &U1, 0),
            claude_line("msg_2", "req_2", "2026-01-15T11:00:00Z", "model-a", &U2, 0),
        ],
    );
    env.run(&env.opts());
    let want = env.totals();
    let ids: Vec<String> = env
        .store()
        .rows_since(i64::MIN)
        .unwrap()
        .into_iter()
        .map(|r| r.id)
        .collect();

    // What an older meter left: version 1, ids without precision, one mixed row.
    {
        let s = env.store();
        s.set_meta("schema_version", "1").unwrap();
        s.conn
            .execute_batch(
                "UPDATE usage SET id = 'old-' || id;
                 UPDATE usage SET precision = 'total_only', unsplit = 9
                   WHERE hour_ms = (SELECT min(hour_ms) FROM usage);",
            )
            .unwrap();
    }

    // A plain sync (not forced, the installed trigger's) rebuilds, and the doc it builds counts
    // every token once.
    let mut o = env.opts();
    o.force = false;
    let r = env.run(&o);
    assert!(r.reimported);
    assert_eq!(env.totals(), want);
    let rows = env.store().rows_since(i64::MIN).unwrap();
    let mut got: Vec<String> = rows.iter().map(|r| r.id.clone()).collect();
    got.sort();
    let mut ids = ids;
    ids.sort();
    assert_eq!(got, ids);
    assert!(
        rows.iter()
            .all(|r| r.precision == "exact" && r.tokens.unsplit == 0)
    );
    assert_eq!(
        env.store().meta("schema_version").unwrap().as_deref(),
        Some(meter::store::SCHEMA_VERSION.to_string().as_str())
    );

    // Once rebuilt, it is not rebuilt again.
    let r = env.run(&env.opts());
    assert!(!r.reimported);
    assert_eq!(env.totals(), want);
}

#[test]
fn the_summary_keeps_unsplit_apart_when_both_rows_share_an_hour() {
    let mut store = Store::open_in_memory().unwrap();
    apply(
        &mut store,
        &[
            ev(exact(), Precision::Exact, 1),
            ev(whole(), Precision::TotalOnly, 2),
        ],
    );
    let rows = store.rows_since(i64::MIN).unwrap();
    let prices = PriceTable::bundled();
    let (doc, _) = summary::build(
        &Input {
            rows: &rows,
            prices: &prices,
            now_ms: hour() + 2 * HOUR_MS,
            machine: "machine-1",
            meter_version: "0.0.0",
            counted_from_ms: hour() - 24 * HOUR_MS,
            pending_bytes: 0,
            pending_files: 0,
            sources: &[],
        },
        64 * 1024,
    );
    assert_eq!(doc["schema_version"], 2);
    let models = doc["models"].as_array().unwrap();
    assert_eq!(models.len(), 1, "both rows are one model: {doc}");
    let d1 = &models[0]["d1"];
    assert_eq!(d1["in"], 30, "{doc}");
    assert_eq!(d1["cr"], 200);
    assert_eq!(d1["out"], 40);
    assert_eq!(d1["rs"], 10);
    assert_eq!(d1["un"], 700);
    // model-c has no price: the split is unpriced as well, and unsplit always is.
    assert_eq!(d1["unpriced"], 700 + 270);
}
