use meter::event::{RollupKey, Tokens};
use meter::prices::PriceTable;
use meter::store::UsageRow;
use meter::summary::{self, Input, SourceHealth};
use meter::time::{DAY_MS, HOUR_MS, parse_rfc3339};

fn rows(days: i64, repos: usize, now: i64) -> Vec<UsageRow> {
    let mut v = Vec::new();
    for h in 0..days * 24 {
        for r in 0..repos {
            v.push(UsageRow {
                key: RollupKey {
                    bot: None,
                    role: None,
                    harness: "claude-code".into(),
                    model: if r % 2 == 0 {
                        "claude-opus-5-5".into()
                    } else {
                        "model-unpriced".into()
                    },
                    machine: "machine-1".into(),
                    repo: Some(format!("git.example.com/acme/repo-{r}")),
                    branch: Some(format!("feat/branch-{}", h % 7)),
                    hour_ms: now - h * HOUR_MS,
                },
                squad: None,
                tokens: Tokens {
                    input_uncached: 10,
                    cache_read: 1000,
                    cache_write_5m: 5,
                    cache_write_1h: 5,
                    output: 50,
                    reasoning: 0,
                },
                precision: "exact".into(),
                reported_usd: None,
                events: 1,
            });
        }
    }
    v
}

fn build(rows: &[UsageRow], cap: usize, now: i64) -> (serde_json::Value, usize) {
    let prices = PriceTable::bundled();
    let sources = vec![SourceHealth {
        harness: "claude-code".into(),
        ..Default::default()
    }];
    summary::build(
        &Input {
            rows,
            prices: &prices,
            now_ms: now,
            machine: "machine-1",
            meter_version: "0.0.0",
            counted_from_ms: now - 30 * DAY_MS,
            pending_bytes: 0,
            pending_files: 0,
            sources: &sources,
        },
        cap,
    )
}

#[test]
fn small_history_fits_whole_with_hourly_then_daily_rows() {
    let now = parse_rfc3339("2026-01-15T12:30:00Z").unwrap();
    let r = rows(4, 2, meter::time::hour_start(now));
    let (doc, size) = build(&r, summary::DEFAULT_CAP_BYTES, now);
    assert!(size <= summary::DEFAULT_CAP_BYTES);
    assert!(doc.get("truncated").is_none());
    let hourly = doc["hourly"].as_array().unwrap();
    assert!(!hourly.is_empty());
    let first_hour = parse_rfc3339(hourly[0]["hour"].as_str().unwrap()).unwrap();
    assert!(now - first_hour <= 48 * HOUR_MS);
    assert!(!doc["daily"].as_array().unwrap().is_empty());
    assert_eq!(doc["schema_version"], 1);
    assert_eq!(doc["meter_version"], "0.0.0");
    for key in [
        "generatedAt",
        "counted_from",
        "pending",
        "prices",
        "sources",
        "complete",
    ] {
        assert!(doc.get(key).is_some(), "{key} missing");
    }
}

#[test]
fn a_large_history_is_cut_to_the_cap_and_says_what_was_dropped() {
    let now = parse_rfc3339("2026-01-15T12:30:00Z").unwrap();
    let r = rows(30, 40, meter::time::hour_start(now));
    let (full, full_size) = build(&r, usize::MAX, now);
    assert!(
        full_size > summary::DEFAULT_CAP_BYTES,
        "fixture must overflow the cap"
    );
    assert!(full.get("truncated").is_none());
    for cap in [summary::DEFAULT_CAP_BYTES, 16 * 1024] {
        let (doc, size) = build(&r, cap, now);
        assert!(size <= cap, "{size} > {cap}");
        assert_eq!(serde_json::to_vec(&doc).unwrap().len(), size);
        let steps = doc["truncated"].as_array().unwrap();
        assert!(!steps.is_empty());
        // Totals are never cut: they still cover every token.
        let tokens: i64 = doc["totals"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| t["cr"].as_i64().unwrap())
            .sum();
        assert_eq!(tokens, 1000 * r.len() as i64);
    }
}
