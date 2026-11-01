//! `meter sync`: one bounded pass over the harness logs, then the summary doc.

use std::collections::{BTreeSet, HashMap};
use std::io;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use crate::event::{Actor, RollupKey, UsageEvent};
use crate::hc::{Hc, LiveTerminal};
use crate::paths;
use crate::prices::PriceTable;
use crate::scan::{self, Candidate, Plan, ReadLimits};
use crate::sources::Source;
use crate::store::{Attributed, FileState, Health, SEEN_CAP, Store};
use crate::summary::{self, SourceHealth};
use crate::time::{DAY_MS, day_start};

/// Phase timings on stderr when `METER_TRACE` is set. Durations only, never content.
struct Trace {
    on: bool,
    last: std::cell::Cell<Instant>,
}

impl Trace {
    fn new() -> Trace {
        Trace {
            on: std::env::var_os("METER_TRACE").is_some(),
            last: std::cell::Cell::new(Instant::now()),
        }
    }

    fn mark(&self, phase: &str) {
        if self.on {
            let now = Instant::now();
            eprintln!(
                "meter: {phase} {:.0} ms",
                (now - self.last.get()).as_secs_f64() * 1000.0
            );
            self.last.set(now);
        }
    }
}

pub const DEFAULT_DAYS: i64 = 14;
pub const DEFAULT_BUDGET: Duration = Duration::from_secs(20);
pub const DEFAULT_STALE_MS: i64 = 10 * 60 * 1000;
pub const DEFAULT_DOC: &str = "herdr-meter";
const REPO_RECHECK_MS: i64 = 7 * DAY_MS;

#[derive(Debug, Clone)]
pub struct Options {
    /// `~/.omp/agent/sessions`.
    pub omp_root: PathBuf,
    /// `~/.claude/projects` (or `$CLAUDE_CONFIG_DIR/projects`).
    pub claude_root: PathBuf,
    pub state_dir: PathBuf,
    /// Window for the first import, and for a widening re-import.
    pub days: Option<i64>,
    pub backfill: bool,
    pub force: bool,
    pub budget: Duration,
    pub stale_ms: i64,
    pub publish: bool,
    pub doc_name: String,
    /// Talk to herdr-core (core id, labels, publish). Off in tests.
    pub use_hc: bool,
    /// Ask git for the repo of each cwd. Off in most tests.
    pub resolve_repos: bool,
    pub chunk_bytes: usize,
    pub max_line: usize,
    pub summary_cap: usize,
    pub now_ms: i64,
    /// Stop after this many chunks, as the time budget would (tests).
    #[doc(hidden)]
    pub chunk_limit: Option<usize>,
}

impl Options {
    pub fn new(home: PathBuf, state_dir: PathBuf, now_ms: i64) -> Options {
        let claude_root = std::env::var_os("CLAUDE_CONFIG_DIR")
            .filter(|d| !d.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".claude"))
            .join("projects");
        Options {
            omp_root: home.join(".omp/agent/sessions"),
            claude_root,
            state_dir,
            days: None,
            backfill: false,
            force: false,
            budget: DEFAULT_BUDGET,
            stale_ms: DEFAULT_STALE_MS,
            publish: true,
            doc_name: DEFAULT_DOC.into(),
            use_hc: true,
            resolve_repos: true,
            chunk_bytes: 8 << 20,
            max_line: 16 << 20,
            summary_cap: summary::DEFAULT_CAP_BYTES,
            now_ms,
            chunk_limit: None,
        }
    }
}

#[derive(Debug, Default)]
pub struct Report {
    pub files_seen: usize,
    pub files_read: usize,
    pub unreadable: usize,
    pub bytes_read: u64,
    pub health: Health,
    pub pending_bytes: u64,
    pub pending_files: u64,
    pub stopped_by_budget: bool,
    pub elapsed: Duration,
    pub counted_from_ms: i64,
    pub reimported: bool,
    pub machine: String,
    pub summary_bytes: usize,
    pub summary: Option<serde_json::Value>,
    /// None: not attempted. Some(Ok(version)) or Some(Err(reason)).
    pub published: Option<Result<u64, String>>,
}

#[derive(Debug)]
pub enum Outcome {
    /// Another `meter sync` holds the lock.
    Locked,
    /// The last complete sync is younger than the staleness window.
    Fresh {
        age_ms: i64,
    },
    Ran(Box<Report>),
}

#[derive(Debug)]
pub struct Error(pub String);

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl From<rusqlite::Error> for Error {
    fn from(e: rusqlite::Error) -> Error {
        Error(format!("meter database: {e}"))
    }
}

/// Attribution: machine, repo per cwd (cached), and labels from live hc terminals.
struct Attributor {
    machine: String,
    terminals: Vec<LiveTerminal>,
    repos: HashMap<String, Option<String>>,
    resolve: bool,
    now_ms: i64,
}

impl Attributor {
    fn repo(&mut self, store: &Store, cwd: Option<&str>) -> rusqlite::Result<Option<String>> {
        let Some(cwd) = cwd else { return Ok(None) };
        if let Some(r) = self.repos.get(cwd) {
            return Ok(r.clone());
        }
        let r = match store.cached_repo(cwd)? {
            Some((r, checked)) if self.now_ms - checked < REPO_RECHECK_MS => r,
            _ => {
                let r = if self.resolve {
                    crate::repo::resolve(cwd)
                } else {
                    None
                };
                store.cache_repo(cwd, r.as_deref(), self.now_ms)?;
                r
            }
        };
        self.repos.insert(cwd.to_string(), r.clone());
        Ok(r)
    }

    /// bot/role/squad from the one live terminal running this harness in this cwd since before the
    /// event. Ambiguous matches give nothing.
    fn actor(&self, e: &UsageEvent) -> Actor {
        let Some(cwd) = e.cwd.as_deref() else {
            return Actor::default();
        };
        let mut found: BTreeSet<Actor> = BTreeSet::new();
        for t in &self.terminals {
            if t.harness == e.harness && t.cwd == cwd && e.ts_ms >= t.created_ms {
                found.insert(Actor {
                    bot: t.bot.clone(),
                    role: t.role.clone(),
                    squad: t.squad.clone(),
                });
            }
        }
        if found.len() == 1 {
            found.pop_first().unwrap_or_default()
        } else {
            Actor::default()
        }
    }

    fn attribute(
        &mut self,
        store: &Store,
        events: Vec<UsageEvent>,
        from_ms: i64,
    ) -> rusqlite::Result<Vec<Attributed>> {
        let mut out = Vec::with_capacity(events.len());
        for e in events {
            if e.ts_ms < from_ms {
                continue;
            }
            let repo = self.repo(store, e.cwd.as_deref())?;
            let actor = self.actor(&e);
            out.push(Attributed {
                key: RollupKey {
                    bot: actor.bot,
                    role: actor.role,
                    harness: e.harness.to_string(),
                    model: e.model,
                    machine: self.machine.clone(),
                    repo,
                    branch: e.branch,
                    hour_ms: crate::time::hour_start(e.ts_ms),
                },
                squad: actor.squad,
                dedupe_key: e.dedupe_key,
                tokens: e.tokens,
                precision: e.precision,
                reported_usd: e.reported_usd,
                session: e.session,
                parent_session: e.parent_session,
                ts_ms: e.ts_ms,
            });
        }
        Ok(out)
    }
}

fn random_id() -> String {
    use std::io::Read;
    let mut b = [0u8; 12];
    if std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut b))
        .is_err()
    {
        let seed = format!("{}-{}", crate::time::now_ms(), std::process::id());
        b.copy_from_slice(
            &sha2::Digest::finalize(<sha2::Sha256 as sha2::Digest>::new_with_prefix(seed))[..12],
        );
    }
    format!("m-{}", crate::event::hex(&b))
}

/// The machine id: herdr-core's core id, else a random id generated once and kept in the store.
fn machine_id(store: &Store, hc: Option<&mut Hc>) -> rusqlite::Result<String> {
    if let Some(id) = hc.and_then(|h| h.core_id()) {
        store.set_meta("machine", &id)?;
        return Ok(id);
    }
    if let Some(id) = store.meta("machine")? {
        return Ok(id);
    }
    let id = match store.meta("machine_fallback")? {
        Some(id) => id,
        None => {
            let id = random_id();
            store.set_meta("machine_fallback", &id)?;
            id
        }
    };
    Ok(id)
}

pub fn source_health(store: &Store) -> rusqlite::Result<Vec<SourceHealth>> {
    let mut by: HashMap<String, SourceHealth> = HashMap::new();
    for (src, last, h) in store.health()? {
        by.insert(
            src.clone(),
            SourceHealth {
                harness: src,
                last_success_ms: last,
                usage_lines: h.usage_lines,
                events: h.events,
                duplicates: h.duplicates,
                skipped_lines: h.skipped_lines,
                rewrites: h.rewrites,
            },
        );
    }
    Ok(Source::ALL
        .iter()
        .map(|s| {
            by.remove(s.harness()).unwrap_or(SourceHealth {
                harness: s.harness().into(),
                ..Default::default()
            })
        })
        .collect())
}

pub fn run(opts: &Options) -> Result<Outcome, Error> {
    let started = Instant::now();
    let deadline = started + opts.budget;
    let db = paths::prepare(&opts.state_dir).map_err(|e| {
        Error(format!(
            "cannot create the state directory {}: {e}",
            opts.state_dir.display()
        ))
    })?;
    let Some(_lock) = paths::try_lock(&opts.state_dir).map_err(|e| {
        Error(format!(
            "cannot take the lock in {}: {e}",
            opts.state_dir.display()
        ))
    })?
    else {
        return Ok(Outcome::Locked);
    };
    let trace = Trace::new();
    let mut store = Store::open(&db)?;
    let prices = PriceTable::load(&opts.state_dir.join("prices.json")).map_err(Error)?;
    let now = opts.now_ms;

    // The import window. Widening it (or --backfill) re-imports: rows are a projection.
    let stored = store.meta_i64("counted_from_ms")?;
    let wanted = if opts.backfill {
        Some(0)
    } else {
        opts.days
            .map(|d| day_start(now - d.max(1) * DAY_MS + DAY_MS))
    };
    let mut reimported = false;
    let counted_from = match (stored, wanted) {
        (None, w) => {
            let from = w.unwrap_or_else(|| day_start(now - DEFAULT_DAYS * DAY_MS + DAY_MS));
            store.reset(from)?;
            from
        }
        (Some(s), Some(w)) if w < s => {
            store.reset(w)?;
            reimported = true;
            w
        }
        (Some(s), _) => s,
    };

    if !opts.force && !reimported {
        let last = store.meta_i64("last_success_ms")?;
        let pending = store.meta_i64("last_pending_bytes")?.unwrap_or(0);
        if let Some(last) = last
            && pending == 0
            && now - last < opts.stale_ms
            && now >= last
        {
            return Ok(Outcome::Fresh { age_ms: now - last });
        }
    }

    trace.mark("open");
    let mut hc = if opts.use_hc {
        Hc::connect(Duration::from_secs(3))
    } else {
        None
    };
    let machine = machine_id(&store, hc.as_mut())?;
    let terminals = hc.as_mut().map(|h| h.terminals()).unwrap_or_default();
    let mut attr = Attributor {
        machine: machine.clone(),
        terminals,
        repos: HashMap::new(),
        resolve: opts.resolve_repos,
        now_ms: now,
    };

    trace.mark("hc");
    let mut candidates: Vec<Candidate> = Vec::new();
    scan::discover(&opts.omp_root, Source::Omp, &mut candidates);
    scan::discover(&opts.claude_root, Source::Claude, &mut candidates);
    // Newest first: recent usage is what people look at, and an old backlog can wait a run.
    candidates.sort_by(|a, b| {
        b.mtime_ms
            .cmp(&a.mtime_ms)
            .then_with(|| a.path.cmp(&b.path))
    });

    let mut report = Report {
        files_seen: candidates.len(),
        counted_from_ms: counted_from,
        reimported,
        machine,
        ..Report::default()
    };
    let limits = ReadLimits {
        chunk_bytes: opts.chunk_bytes,
        max_line: opts.max_line,
        deadline,
        chunks_left: opts.chunk_limit.map(std::cell::Cell::new),
    };
    let mut pending_sources: BTreeSet<Source> = BTreeSet::new();
    let mut stopped_at = candidates.len();

    trace.mark("discover");
    for (i, c) in candidates.iter().enumerate() {
        if limits.exhausted() {
            stopped_at = i;
            break;
        }
        let path = c.path.to_string_lossy().into_owned();
        let prev = store.file(&path)?;
        if prev.is_none() && c.mtime_ms < counted_from {
            continue; // older than the window and never read
        }
        let plan = match scan::plan(c, prev) {
            Ok(p) => p,
            Err(_) => {
                report.unreadable += 1;
                continue;
            }
        };
        let (mut state, rewritten) = match plan {
            Plan::Unchanged => continue,
            Plan::Read { state, rewritten } => (*state, rewritten),
        };
        let mut first = true;
        let res = scan::read_file(
            &mut state,
            c.source,
            &limits,
            |st: &FileState, events, mut health| {
                if first && rewritten {
                    health.rewrites += 1;
                }
                first = false;
                let attributed = attr
                    .attribute(&store, events, counted_from)
                    .map_err(io::Error::other)?;
                let applied = store
                    .apply_chunk(st, &attributed, health, now)
                    .map_err(io::Error::other)?;
                report.health.add(&applied);
                Ok(())
            },
        );
        match res {
            Ok(outcome) => {
                report.files_read += 1;
                report.bytes_read += outcome.bytes;
                if rewritten && first {
                    // Nothing was read (an emptied file): still count the rewrite.
                    let h = Health {
                        rewrites: 1,
                        ..Health::default()
                    };
                    store.apply_chunk(&state, &[], h, now)?;
                    report.health.add(&h);
                } else {
                    store.put_file(&state)?;
                }
                if outcome.stopped {
                    report.stopped_by_budget = true;
                    report.pending_bytes += c.size.saturating_sub(state.offset);
                    report.pending_files += 1;
                    pending_sources.insert(c.source);
                    stopped_at = i + 1;
                    break;
                }
            }
            Err(_) => report.unreadable += 1,
        }
    }
    for c in &candidates[stopped_at..] {
        let prev = store.file(&c.path.to_string_lossy())?;
        let offset = match &prev {
            Some(p)
                if p.dev == c.dev
                    && p.ino == c.ino
                    && p.size == c.size
                    && p.mtime_ms == c.mtime_ms =>
            {
                continue;
            }
            Some(p) if p.dev == c.dev && p.ino == c.ino && c.size >= p.offset => p.offset,
            None if c.mtime_ms < counted_from => continue,
            _ => 0,
        };
        if c.size > offset {
            report.stopped_by_budget = true;
            report.pending_bytes += c.size - offset;
            report.pending_files += 1;
            pending_sources.insert(c.source);
        }
    }

    trace.mark("read");
    store.trim_seen(SEEN_CAP)?;
    store.set_meta("last_run_ms", &now.to_string())?;
    store.set_meta("last_pending_bytes", &report.pending_bytes.to_string())?;
    store.set_meta("last_pending_files", &report.pending_files.to_string())?;
    for s in Source::ALL {
        if !pending_sources.contains(&s) {
            store.mark_success(s.harness(), now)?;
        }
    }
    if report.pending_bytes == 0 {
        store.set_meta("last_success_ms", &now.to_string())?;
    }

    trace.mark("commit");
    let rows = store.rows_since(counted_from)?;
    let sources = source_health(&store)?;
    let (doc, size) = summary::build(
        &summary::Input {
            rows: &rows,
            prices: &prices,
            now_ms: now,
            machine: &report.machine,
            meter_version: env!("CARGO_PKG_VERSION"),
            counted_from_ms: counted_from,
            pending_bytes: report.pending_bytes,
            pending_files: report.pending_files,
            sources: &sources,
        },
        opts.summary_cap,
    );
    trace.mark("summary");
    report.summary_bytes = size;
    if opts.publish {
        report.published = Some(match hc.as_mut() {
            Some(h) => h.doc_put(&opts.doc_name, &doc).map_err(|e| e.to_string()),
            None => Err("hc is not available".into()),
        });
    }
    trace.mark("publish");
    report.summary = Some(doc);
    report.elapsed = started.elapsed();
    Ok(Outcome::Ran(Box::new(report)))
}
