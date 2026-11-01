//! The per-machine summary doc (`herdr-meter`), schema version 2: what the phone shows, small
//! enough (64 KiB) to fetch over a slow link. The contract is documented in `meter/README.md`.
//!
//! - Per project (repo), per task (repo + branch) and per model: rolling 24 h / 7 d / 30 d windows.
//! - Per harness: an hourly series for 48 hours and a daily series for 30 days.
//! - To fit, the number of tasks, then of projects, then the hourly window shrink; what is cut is
//!   summed into `*_other` and listed in `truncated`. Kept entries keep their repo and branch.
//!
//! $ is derived here and nowhere else: a harness's reported $ wins; otherwise the price table
//! prices the split tokens. Tokens of unpriced models and `total_only` tokens are never priced and
//! are counted in `unpriced` instead.

use std::collections::{BTreeMap, BTreeSet};

use serde_json::{Map, Value, json};

use crate::prices::PriceTable;
use crate::store::UsageRow;
use crate::time::{DAY_MS, HOUR_MS, day_start, format_day, format_rfc3339, hour_start};

pub const SUMMARY_SCHEMA_VERSION: u32 = 2;
pub const DEFAULT_CAP_BYTES: usize = 64 * 1024;
pub const HOURLY_HOURS: usize = 48;
pub const DAILY_DAYS: usize = 30;
/// Branches that are not a task: their work goes to the project's `untasked` bucket.
pub const UNTASKED_BRANCHES: [&str; 5] = ["main", "master", "dev", "develop", "trunk"];

#[derive(Debug, Clone, Default)]
pub struct SourceHealth {
    pub harness: String,
    /// `ok`, `degraded`, `unsupported` or `absent`.
    pub status: String,
    pub reason: Option<String>,
    pub last_success_ms: Option<i64>,
    pub usage_lines: i64,
    pub events: i64,
    pub duplicates: i64,
    pub skipped_lines: i64,
    pub rewrites: i64,
    /// Codex: (exact, total_only) threads.
    pub threads: Option<(i64, i64)>,
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

/// Tokens by type plus their $, for any aggregate.
#[derive(Debug, Clone, Copy, Default)]
struct T {
    input: i64,
    cr: i64,
    cw: i64,
    out: i64,
    rs: i64,
    un: i64,
    usd: f64,
    usd_rep: f64,
    priced: bool,
    unpriced: i64,
}

fn round_usd(v: f64) -> f64 {
    (v * 10_000.0).round() / 10_000.0
}

impl T {
    fn of(prices: &PriceTable, r: &UsageRow) -> T {
        let t = &r.tokens;
        let mut x = T {
            input: t.input_uncached,
            cr: t.cache_read,
            cw: t.cache_write_5m + t.cache_write_1h,
            out: t.output,
            rs: t.reasoning,
            un: t.unsplit,
            unpriced: t.unsplit,
            ..T::default()
        };
        if let Some(u) = r.reported_usd {
            x.usd = u;
            x.usd_rep = u;
            x.priced = true;
        } else if let Some(p) = prices.get(&r.key.model) {
            if t.split_total() > 0 {
                x.usd = p.cost(t);
                x.priced = true;
            }
        } else {
            x.unpriced += t.split_total();
        }
        x
    }

    fn add(&mut self, o: &T) {
        self.input += o.input;
        self.cr += o.cr;
        self.cw += o.cw;
        self.out += o.out;
        self.rs += o.rs;
        self.un += o.un;
        self.usd += o.usd;
        self.usd_rep += o.usd_rep;
        self.priced |= o.priced;
        self.unpriced += o.unpriced;
    }

    fn tokens(&self) -> i64 {
        self.input + self.cr + self.cw + self.out + self.un
    }

    fn is_empty(&self) -> bool {
        self.tokens() == 0 && !self.priced
    }

    fn json(&self) -> Value {
        let mut o = Map::new();
        for (k, v) in [
            ("in", self.input),
            ("cr", self.cr),
            ("cw", self.cw),
            ("out", self.out),
            ("rs", self.rs),
            ("un", self.un),
        ] {
            if v != 0 {
                o.insert(k.into(), json!(v));
            }
        }
        if self.priced {
            o.insert("usd".into(), json!(round_usd(self.usd)));
            if self.usd_rep != 0.0 {
                o.insert("usd_rep".into(), json!(round_usd(self.usd_rep)));
            }
        }
        if self.unpriced != 0 {
            o.insert("unpriced".into(), json!(self.unpriced));
        }
        Value::Object(o)
    }

    /// Ranking: $ first, then tokens.
    fn rank(&self) -> (i64, i64) {
        ((self.usd * 10_000.0) as i64, self.tokens())
    }
}

/// Rolling windows: d1 (24 h), d7, d30.
#[derive(Debug, Clone, Copy, Default)]
struct Win {
    d1: T,
    d7: T,
    d30: T,
}

#[derive(Debug, Clone, Copy)]
struct Bounds {
    d1: i64,
    d7: i64,
    d30: i64,
}

impl Win {
    fn add(&mut self, b: &Bounds, hour: i64, t: &T) {
        if hour >= b.d1 {
            self.d1.add(t);
        }
        if hour >= b.d7 {
            self.d7.add(t);
        }
        self.d30.add(t);
    }

    fn add_win(&mut self, o: &Win) {
        self.d1.add(&o.d1);
        self.d7.add(&o.d7);
        self.d30.add(&o.d30);
    }

    fn put(&self, o: &mut Map<String, Value>) {
        for (k, t) in [("d1", &self.d1), ("d7", &self.d7), ("d30", &self.d30)] {
            if !t.is_empty() {
                o.insert(k.into(), t.json());
            }
        }
    }
}

#[derive(Default)]
struct Project {
    win: Win,
    untasked: T,
    tasks: BTreeSet<String>,
    hours: BTreeSet<i64>,
    last: i64,
    tasked: T,
}

#[derive(Default)]
struct Task {
    win: Win,
    first: i64,
    last: i64,
    hours: BTreeSet<i64>,
    agents: BTreeSet<String>,
    bots: BTreeSet<String>,
}

pub fn is_task(branch: Option<&str>) -> bool {
    match branch {
        None => false,
        Some(b) => {
            let b = b.trim();
            !b.is_empty() && b != "HEAD" && !UNTASKED_BRANCHES.contains(&b)
        }
    }
}

/// Everything the doc needs, aggregated once; `render` then only picks how much to show.
struct Agg {
    bounds: Bounds,
    models: BTreeMap<(String, String), (Win, bool)>,
    projects: Vec<(Option<String>, Project)>,
    tasks: Vec<((Option<String>, String), Task)>,
    hourly_from: i64,
    hourly: BTreeMap<String, Vec<T>>,
    daily_from: i64,
    days: usize,
    daily: BTreeMap<String, Vec<T>>,
    unknown: BTreeMap<String, Vec<String>>,
}

fn aggregate(input: &Input) -> Agg {
    let now_hour = hour_start(input.now_ms);
    let from = |hours: i64| (now_hour - (hours - 1) * HOUR_MS).max(input.counted_from_ms);
    let bounds = Bounds {
        d1: from(24),
        d7: from(7 * 24),
        d30: from(30 * 24),
    };
    let hourly_from = now_hour - (HOURLY_HOURS as i64 - 1) * HOUR_MS;
    let today = day_start(input.now_ms);
    let daily_from =
        (today - (DAILY_DAYS as i64 - 1) * DAY_MS).max(day_start(input.counted_from_ms));
    let days = ((today - daily_from) / DAY_MS + 1).max(1) as usize;

    let mut models: BTreeMap<(String, String), (Win, bool)> = BTreeMap::new();
    let mut projects: BTreeMap<Option<String>, Project> = BTreeMap::new();
    let mut tasks: BTreeMap<(Option<String>, String), Task> = BTreeMap::new();
    let mut hourly: BTreeMap<String, Vec<T>> = BTreeMap::new();
    let mut daily: BTreeMap<String, Vec<T>> = BTreeMap::new();
    for r in input.rows {
        let hour = r.key.hour_ms;
        if hour < bounds.d30 || hour > now_hour {
            continue;
        }
        let t = T::of(input.prices, r);
        let harness = &r.key.harness;
        let m = models
            .entry((harness.clone(), r.key.model.clone()))
            .or_default();
        m.0.add(&bounds, hour, &t);
        m.1 |= r.reported_usd.is_some() || input.prices.get(&r.key.model).is_some();

        let p = projects.entry(r.key.repo.clone()).or_default();
        p.win.add(&bounds, hour, &t);
        p.hours.insert(hour);
        p.last = p.last.max(hour);
        let branch = r.key.branch.as_deref();
        if is_task(branch) {
            let b = branch.unwrap_or_default().to_string();
            p.tasks.insert(b.clone());
            p.tasked.add(&t);
            let k = tasks.entry((r.key.repo.clone(), b)).or_default();
            k.win.add(&bounds, hour, &t);
            k.first = if k.hours.is_empty() {
                hour
            } else {
                k.first.min(hour)
            };
            k.last = k.last.max(hour);
            k.hours.insert(hour);
            k.agents.insert(harness.clone());
            if let Some(bot) = &r.key.bot {
                k.bots.insert(bot.clone());
            }
        } else {
            p.untasked.add(&t);
        }
        if hour >= hourly_from {
            let s = hourly
                .entry(harness.clone())
                .or_insert_with(|| vec![T::default(); HOURLY_HOURS]);
            s[((hour - hourly_from) / HOUR_MS) as usize].add(&t);
        }
        if hour >= daily_from {
            let s = daily
                .entry(harness.clone())
                .or_insert_with(|| vec![T::default(); days]);
            let i = ((day_start(hour) - daily_from) / DAY_MS) as usize;
            if let Some(v) = s.get_mut(i) {
                v.add(&t);
            }
        }
    }
    let mut unknown: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for ((h, m), (_, priced)) in &models {
        if !priced {
            unknown.entry(h.clone()).or_default().push(m.clone());
        }
    }
    let mut projects: Vec<(Option<String>, Project)> = projects.into_iter().collect();
    projects.sort_by(|a, b| {
        b.1.win
            .d30
            .rank()
            .cmp(&a.1.win.d30.rank())
            .then_with(|| a.0.cmp(&b.0))
    });
    let mut tasks: Vec<((Option<String>, String), Task)> = tasks.into_iter().collect();
    tasks.sort_by(|a, b| {
        b.1.win
            .d30
            .rank()
            .cmp(&a.1.win.d30.rank())
            .then_with(|| a.0.cmp(&b.0))
    });
    Agg {
        bounds,
        models,
        projects,
        tasks,
        hourly_from,
        hourly,
        daily_from,
        days,
        daily,
        unknown,
    }
}

/// `{from, <len_key>: len, series: [{harness, in: […], …}]}`; value `i` is `from + i` steps.
fn series(
    from: String,
    len_key: &str,
    len: usize,
    by: &BTreeMap<String, Vec<T>>,
    skip: usize,
) -> Value {
    let mut out = Vec::new();
    for (harness, v) in by {
        let v = &v[skip.min(v.len())..];
        let col = |f: &dyn Fn(&T) -> i64| v.iter().map(f).collect::<Vec<i64>>();
        let mut o = Map::new();
        o.insert("harness".into(), json!(harness));
        o.insert("in".into(), json!(col(&|t| t.input)));
        o.insert("cr".into(), json!(col(&|t| t.cr)));
        o.insert("cw".into(), json!(col(&|t| t.cw)));
        o.insert("out".into(), json!(col(&|t| t.out)));
        if v.iter().any(|t| t.un != 0) {
            o.insert("un".into(), json!(col(&|t| t.un)));
        }
        o.insert(
            "usd".into(),
            json!(v.iter().map(|t| round_usd(t.usd)).collect::<Vec<f64>>()),
        );
        out.push(Value::Object(o));
    }
    let mut o = Map::new();
    o.insert("from".into(), json!(from));
    o.insert(len_key.into(), json!(len));
    o.insert("series".into(), Value::Array(out));
    Value::Object(o)
}

/// How much of the aggregate to show.
#[derive(Debug, Clone, Copy)]
struct Detail {
    tasks: usize,
    projects: usize,
    hours: usize,
}

fn render(input: &Input, a: &Agg, d: Detail, truncated: &[String]) -> Value {
    let mut sources = Map::new();
    for s in input.sources {
        let mut o = Map::new();
        o.insert(
            "status".into(),
            json!(if s.status.is_empty() { "ok" } else { &s.status }),
        );
        if let Some(r) = &s.reason {
            o.insert("reason".into(), json!(r));
        }
        o.insert(
            "last_success".into(),
            json!(s.last_success_ms.map(format_rfc3339)),
        );
        o.insert("usage_lines".into(), json!(s.usage_lines));
        o.insert("events".into(), json!(s.events));
        o.insert("duplicates".into(), json!(s.duplicates));
        o.insert("skipped_lines".into(), json!(s.skipped_lines));
        o.insert("rewrites".into(), json!(s.rewrites));
        let mut unknown = a.unknown.get(&s.harness).cloned().unwrap_or_default();
        unknown.truncate(20);
        o.insert("unknown_models".into(), json!(unknown));
        if let Some((exact, total_only)) = s.threads {
            o.insert(
                "threads".into(),
                json!({"exact": exact, "total_only": total_only}),
            );
        }
        sources.insert(s.harness.clone(), Value::Object(o));
    }

    let models: Vec<Value> = a
        .models
        .iter()
        .map(|((h, m), (w, priced))| {
            let mut o = Map::new();
            o.insert("harness".into(), json!(h));
            o.insert("model".into(), json!(m));
            if !priced {
                o.insert("priced".into(), json!(false));
            }
            w.put(&mut o);
            Value::Object(o)
        })
        .collect();

    let mut projects = Vec::new();
    for (repo, p) in a.projects.iter().take(d.projects) {
        let mut o = Map::new();
        o.insert("repo".into(), json!(repo));
        o.insert("tasks".into(), json!(p.tasks.len()));
        o.insert("hours".into(), json!(p.hours.len()));
        if !p.tasks.is_empty() && p.tasked.priced {
            o.insert(
                "usd_per_task".into(),
                json!(round_usd(p.tasked.usd / p.tasks.len() as f64)),
            );
        }
        o.insert("last".into(), json!(format_rfc3339(p.last)));
        p.win.put(&mut o);
        if !p.untasked.is_empty() {
            o.insert("untasked".into(), p.untasked.json());
        }
        projects.push(Value::Object(o));
    }

    let mut tasks: Vec<&((Option<String>, String), Task)> = a.tasks.iter().take(d.tasks).collect();
    tasks.sort_by(|x, y| y.1.last.cmp(&x.1.last).then_with(|| x.0.cmp(&y.0)));
    let tasks: Vec<Value> = tasks
        .into_iter()
        .map(|((repo, branch), t)| {
            let mut o = Map::new();
            o.insert("repo".into(), json!(repo));
            o.insert("branch".into(), json!(branch));
            o.insert("agents".into(), json!(t.agents));
            if !t.bots.is_empty() {
                o.insert("bots".into(), json!(t.bots));
            }
            o.insert("first".into(), json!(format_rfc3339(t.first)));
            o.insert("last".into(), json!(format_rfc3339(t.last)));
            o.insert("hours".into(), json!(t.hours.len()));
            t.win.put(&mut o);
            Value::Object(o)
        })
        .collect();

    let hours = d.hours.clamp(1, HOURLY_HOURS);
    let skip = HOURLY_HOURS - hours;
    let hourly = series(
        format_rfc3339(a.hourly_from + skip as i64 * HOUR_MS),
        "hours",
        hours,
        &a.hourly,
        skip,
    );
    let daily = series(format_day(a.daily_from), "days", a.days, &a.daily, 0);

    let mut doc = json!({
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
        "windows": {
            "d1": format_rfc3339(a.bounds.d1),
            "d7": format_rfc3339(a.bounds.d7),
            "d30": format_rfc3339(a.bounds.d30),
        },
        "models": models,
        "projects": projects,
        "tasks": tasks,
        "hourly": hourly,
        "daily": daily,
        "truncated": truncated,
    });
    let o = doc.as_object_mut().expect("doc is an object");
    if a.projects.len() > d.projects {
        let mut w = Win::default();
        let mut n_tasks = 0;
        for (_, p) in &a.projects[d.projects..] {
            w.add_win(&p.win);
            n_tasks += p.tasks.len();
        }
        let mut po = Map::new();
        po.insert("count".into(), json!(a.projects.len() - d.projects));
        po.insert("tasks".into(), json!(n_tasks));
        w.put(&mut po);
        o.insert("projects_other".into(), Value::Object(po));
    }
    if a.tasks.len() > d.tasks {
        let mut w = Win::default();
        for (_, t) in &a.tasks[d.tasks..] {
            w.add_win(&t.win);
        }
        let mut to = Map::new();
        to.insert("count".into(), json!(a.tasks.len() - d.tasks));
        w.put(&mut to);
        o.insert("tasks_other".into(), Value::Object(to));
    }
    doc
}

fn size(v: &Value) -> usize {
    serde_json::to_vec(v).map_or(usize::MAX, |b| b.len())
}

/// Largest n in [lo, hi] with `fits(n)`, assuming fewer is never bigger. None if even `lo` fails.
fn search(lo: usize, hi: usize, fits: &dyn Fn(usize) -> bool) -> Option<usize> {
    if !fits(lo) {
        return None;
    }
    let (mut lo, mut hi) = (lo, hi);
    while lo < hi {
        let mid = lo + (hi - lo).div_ceil(2);
        if fits(mid) { lo = mid } else { hi = mid - 1 }
    }
    Some(lo)
}

/// Builds the doc and cuts it until it fits in `cap` bytes: first the number of tasks, then of
/// projects, then the hourly window. `truncated` lists each cut.
pub fn build(input: &Input, cap: usize) -> (Value, usize) {
    let a = aggregate(input);
    let all = Detail {
        tasks: a.tasks.len(),
        projects: a.projects.len(),
        hours: HOURLY_HOURS,
    };
    let attempt = |d: Detail, steps: &[String]| -> (Value, usize) {
        let v = render(input, &a, d, steps);
        let n = size(&v);
        (v, n)
    };
    let r = attempt(all, &[]);
    if r.1 <= cap {
        return r;
    }
    let tasks_note = |n: usize| format!("tasks: kept {n} of {}", a.tasks.len());
    let projects_note = |n: usize| format!("projects: kept {n} of {}", a.projects.len());
    let hours_note = |n: usize| format!("hourly: last {n} hours only");
    let mut d = all;
    let mut steps: Vec<String> = Vec::new();
    let with = |steps: &[String], s: String| steps.iter().cloned().chain([s]).collect::<Vec<_>>();

    let fits = |d: Detail, steps: &[String]| attempt(d, steps).1 <= cap;
    if let Some(n) = search(0, all.tasks, &|n| {
        fits(Detail { tasks: n, ..d }, &with(&steps, tasks_note(n)))
    }) {
        d.tasks = n;
        steps.push(tasks_note(n));
        return attempt(d, &steps);
    }
    d.tasks = 0;
    steps.push(tasks_note(0));
    if let Some(n) = search(0, all.projects, &|n| {
        fits(Detail { projects: n, ..d }, &with(&steps, projects_note(n)))
    }) {
        d.projects = n;
        steps.push(projects_note(n));
        return attempt(d, &steps);
    }
    d.projects = 0;
    steps.push(projects_note(0));
    let h = search(1, HOURLY_HOURS, &|n| {
        fits(Detail { hours: n, ..d }, &with(&steps, hours_note(n)))
    })
    .unwrap_or(1);
    d.hours = h;
    steps.push(hours_note(h));
    attempt(d, &steps)
}
