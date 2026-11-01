//! The per-machine summary doc (`herdr-meter`): a projection of the store small enough for the
//! phone to fetch over a slow link. Hourly rows for the last 48 hours, daily rows before that.
//!
//! $ is derived here and nowhere else: omp's reported $ wins; otherwise the price table prices
//! `exact` rows; a model without a price is `priced: false` and carries no $ at all.

use std::collections::BTreeMap;

use serde_json::{Map, Value, json};

use crate::event::Tokens;
use crate::prices::PriceTable;
use crate::store::UsageRow;
use crate::time::{DAY_MS, HOUR_MS, day_start, format_day, format_rfc3339, hour_start};

pub const SUMMARY_SCHEMA_VERSION: u32 = 1;
pub const DEFAULT_CAP_BYTES: usize = 64 * 1024;
pub const HOURLY_WINDOW_MS: i64 = 48 * HOUR_MS;

#[derive(Debug, Clone, Default)]
pub struct SourceHealth {
    pub harness: String,
    pub last_success_ms: Option<i64>,
    pub usage_lines: i64,
    pub events: i64,
    pub duplicates: i64,
    pub skipped_lines: i64,
    pub rewrites: i64,
}

pub struct Input<'a> {
    pub rows: &'a [UsageRow],
    pub prices: &'a PriceTable,
    pub now_ms: i64,
    pub machine: &'a str,
    pub meter_version: &'a str,
    pub counted_from_ms: i64,
    pub pending_bytes: u64,
    pub pending_files: u64,
    pub sources: &'a [SourceHealth],
}

/// Detail kept in the doc, from most to least. Each step trades detail for size.
#[derive(Debug, Clone, Copy)]
struct Detail {
    hourly_window_ms: i64,
    hourly_branch: bool,
    daily_branch: bool,
    daily_repo: bool,
    daily_days: Option<usize>,
}

#[derive(Default)]
struct Agg {
    tokens: Tokens,
    reported_usd: Option<f64>,
    total_only: bool,
    events: i64,
}

type GroupKey = (
    i64,
    String,
    String,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
);

fn group(
    rows: &[UsageRow],
    keep: impl Fn(&UsageRow) -> Option<(i64, bool, bool)>,
) -> BTreeMap<GroupKey, Agg> {
    let mut m: BTreeMap<GroupKey, Agg> = BTreeMap::new();
    for r in rows {
        let Some((bucket, with_repo, with_branch)) = keep(r) else {
            continue;
        };
        let k = (
            bucket,
            r.key.harness.clone(),
            r.key.model.clone(),
            if with_repo { r.key.repo.clone() } else { None },
            if with_branch {
                r.key.branch.clone()
            } else {
                None
            },
            r.key.bot.clone(),
            r.key.role.clone(),
            r.squad.clone(),
        );
        let a = m.entry(k).or_default();
        a.tokens.add(&r.tokens);
        if let Some(u) = r.reported_usd {
            a.reported_usd = Some(a.reported_usd.unwrap_or(0.0) + u);
        }
        a.total_only |= r.precision == "total_only";
        a.events += r.events;
    }
    m
}

fn round_usd(v: f64) -> f64 {
    (v * 10_000.0).round() / 10_000.0
}

/// The $ of one aggregate: (usd, source) or None when it cannot be priced honestly.
fn price(prices: &PriceTable, model: &str, a: &Agg) -> Option<(f64, &'static str)> {
    if let Some(u) = a.reported_usd {
        return Some((u, "reported"));
    }
    if a.total_only {
        return None;
    }
    prices.get(model).map(|p| (p.cost(&a.tokens), "estimate"))
}

fn entry(prices: &PriceTable, time_key: &str, time: String, k: &GroupKey, a: &Agg) -> Value {
    let mut o = Map::new();
    o.insert(time_key.into(), Value::String(time));
    o.insert("harness".into(), json!(k.1));
    o.insert("model".into(), json!(k.2));
    for (name, v) in [
        ("repo", &k.3),
        ("branch", &k.4),
        ("bot", &k.5),
        ("role", &k.6),
        ("squad", &k.7),
    ] {
        if let Some(v) = v {
            o.insert(name.into(), json!(v));
        }
    }
    let t = &a.tokens;
    o.insert("in".into(), json!(t.input_uncached));
    o.insert("cr".into(), json!(t.cache_read));
    o.insert("cw5m".into(), json!(t.cache_write_5m));
    o.insert("cw1h".into(), json!(t.cache_write_1h));
    o.insert("out".into(), json!(t.output));
    if t.reasoning > 0 {
        o.insert("rs".into(), json!(t.reasoning));
    }
    if a.total_only {
        o.insert("precision".into(), json!("total_only"));
    }
    match price(prices, &k.2, a) {
        Some((usd, src)) => {
            o.insert("usd".into(), json!(round_usd(usd)));
            if src == "reported" {
                o.insert("usd_src".into(), json!("reported"));
            }
        }
        None => {
            o.insert("priced".into(), json!(false));
        }
    }
    Value::Object(o)
}

fn render(input: &Input, d: Detail) -> Value {
    let hourly_from = hour_start(input.now_ms) - d.hourly_window_ms + HOUR_MS;
    let hourly = group(input.rows, |r| {
        (r.key.hour_ms >= hourly_from).then_some((r.key.hour_ms, true, d.hourly_branch))
    });
    let mut daily = group(input.rows, |r| {
        (r.key.hour_ms < hourly_from).then_some((
            day_start(r.key.hour_ms),
            d.daily_repo,
            d.daily_branch && d.daily_repo,
        ))
    });
    if let Some(n) = d.daily_days {
        let mut days: Vec<i64> = daily.keys().map(|k| k.0).collect();
        days.dedup();
        if n == 0 {
            daily.clear();
        } else if days.len() > n {
            let cut = days[days.len() - n];
            daily.retain(|k, _| k.0 >= cut);
        }
    }

    let mut totals: BTreeMap<(String, String), Agg> = BTreeMap::new();
    let mut unknown: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for r in input.rows {
        let a = totals
            .entry((r.key.harness.clone(), r.key.model.clone()))
            .or_default();
        a.tokens.add(&r.tokens);
        if let Some(u) = r.reported_usd {
            a.reported_usd = Some(a.reported_usd.unwrap_or(0.0) + u);
        }
        a.total_only |= r.precision == "total_only";
        a.events += r.events;
    }
    let totals: Vec<Value> = totals
        .iter()
        .map(|((h, m), a)| {
            if price(input.prices, m, a).is_none() && a.reported_usd.is_none() {
                let list = unknown.entry(h.clone()).or_default();
                list.push(m.clone());
            }
            let k: GroupKey = (0, h.clone(), m.clone(), None, None, None, None, None);
            let mut v = entry(
                input.prices,
                "since",
                format_rfc3339(input.counted_from_ms),
                &k,
                a,
            );
            v.as_object_mut()
                .unwrap()
                .insert("events".into(), json!(a.events));
            v
        })
        .collect();

    let mut sources = Map::new();
    for s in input.sources {
        let mut models = unknown.get(&s.harness).cloned().unwrap_or_default();
        models.truncate(20);
        sources.insert(
            s.harness.clone(),
            json!({
                "last_success": s.last_success_ms.map(format_rfc3339),
                "usage_lines": s.usage_lines,
                "events": s.events,
                "duplicates": s.duplicates,
                "skipped_lines": s.skipped_lines,
                "rewrites": s.rewrites,
                "unknown_models": models,
            }),
        );
    }

    json!({
        "schema_version": SUMMARY_SCHEMA_VERSION,
        "kind": "herdr-meter",
        "rows_schema": "ae/1 agent.usage",
        "meter_version": input.meter_version,
        "generatedAt": format_rfc3339(input.now_ms),
        "machine": input.machine,
        "counted_from": format_rfc3339(input.counted_from_ms),
        "complete": input.pending_bytes == 0,
        "pending": {"bytes": input.pending_bytes, "files": input.pending_files},
        "prices": {
            "date": input.prices.date,
            "source": input.prices.source,
            "label": "API-equivalent estimate at list price, not an amount billed",
        },
        "sources": sources,
        "totals": totals,
        "hourly": hourly.iter().map(|(k, a)| entry(input.prices, "hour", format_rfc3339(k.0), k, a)).collect::<Vec<_>>(),
        "daily": daily.iter().map(|(k, a)| entry(input.prices, "day", format_day(k.0), k, a)).collect::<Vec<_>>(),
    })
}

/// Builds the doc and steps detail down until it fits in `cap` bytes. `truncated` lists the steps
/// taken, so the reader knows what is missing.
pub fn build(input: &Input, cap: usize) -> (Value, usize) {
    let mut d = Detail {
        hourly_window_ms: HOURLY_WINDOW_MS,
        hourly_branch: true,
        daily_branch: true,
        daily_repo: true,
        daily_days: None,
    };
    let days_held = ((input.now_ms - input.counted_from_ms).max(0) / DAY_MS + 1) as usize;
    let attempt = |d: Detail, steps: &[String]| -> (Value, usize) {
        let mut v = render(input, d);
        if !steps.is_empty() {
            v.as_object_mut()
                .unwrap()
                .insert("truncated".into(), json!(steps));
        }
        let size = serde_json::to_vec(&v).map_or(usize::MAX, |b| b.len());
        (v, size)
    };
    // Largest n in [lo, hi] for which `fits(n)`, assuming smaller n is always smaller output.
    let search = |lo: usize, hi: usize, fits: &dyn Fn(usize) -> bool| -> Option<usize> {
        if !fits(lo) {
            return None;
        }
        let (mut lo, mut hi) = (lo, hi);
        while lo < hi {
            let mid = lo + (hi - lo).div_ceil(2);
            if fits(mid) { lo = mid } else { hi = mid - 1 }
        }
        Some(lo)
    };
    let mut steps: Vec<String> = Vec::new();
    let r = attempt(d, &steps);
    if r.1 <= cap {
        return r;
    }
    for step in ["daily rows without branch", "daily rows without repo"] {
        if step.contains("branch") {
            d.daily_branch = false;
        } else {
            d.daily_repo = false;
        }
        steps.push(step.into());
        let r = attempt(d, &steps);
        if r.1 <= cap {
            return r;
        }
    }
    let days_note = |n: usize| format!("daily rows for the last {n} days only");
    let with =
        |steps: &[String], extra: String| steps.iter().cloned().chain([extra]).collect::<Vec<_>>();
    let fits_days = |n: usize| {
        attempt(
            Detail {
                daily_days: Some(n),
                ..d
            },
            &with(&steps, days_note(n)),
        )
        .1 <= cap
    };
    if let Some(n) = search(0, days_held, &fits_days) {
        d.daily_days = Some(n);
        steps.push(days_note(n));
        return attempt(d, &steps);
    }
    d.daily_days = Some(0);
    steps.push(days_note(0));
    d.hourly_branch = false;
    steps.push("hourly rows without branch".into());
    let r = attempt(d, &steps);
    if r.1 <= cap {
        return r;
    }
    let hours_note = |h: usize| format!("hourly rows for the last {h} hours only");
    let max_hours = (HOURLY_WINDOW_MS / HOUR_MS) as usize;
    let fits_hours = |h: usize| {
        let dd = Detail {
            hourly_window_ms: h as i64 * HOUR_MS,
            ..d
        };
        attempt(dd, &with(&steps, hours_note(h))).1 <= cap
    };
    let h = search(1, max_hours, &fits_hours).unwrap_or(1);
    d.hourly_window_ms = h as i64 * HOUR_MS;
    steps.push(hours_note(h));
    attempt(d, &steps)
}
