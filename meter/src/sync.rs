//! `meter sync`: one bounded pass over the harness logs, then the summary doc.

use std::collections::{BTreeSet, HashMap};
use std::io;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use crate::event::{Actor, Precision, RollupKey, Tokens, UsageEvent};
use crate::hc::{Hc, LiveTerminal};
use crate::paths;
use crate::prices::PriceTable;
use crate::scan::{self, Candidate, Plan, ReadLimits, ReadOutcome};
use crate::sources::Source;
use crate::sources::codex::{self, State};
use crate::store::{Attributed, FileState, Health, SEEN_CAP, Store};
use crate::summary::{self, SourceHealth};
use crate::time::{DAY_MS, HOUR_MS, day_start};

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

pub const DEFAULT_DAYS: i64 = 30;
pub const DEFAULT_BUDGET: Duration = Duration::from_secs(20);
pub const DEFAULT_STALE_MS: i64 = 10 * 60 * 1000;
pub const DEFAULT_DOC: &str = "herdr-meter";
const REPO_RECHECK_MS: i64 = 7 * DAY_MS;
/// Threads are re-listed from a little before the last run's newest `updated_at_ms`, in case
/// Codex stamped a thread before it finished writing the rollout.
const CODEX_SLACK_MS: i64 = 10 * 60 * 1000;
/// A rollout with no running total is counted from `tokens_used` only once its thread has been
/// idle this long (a new thread writes its first total after its first reply).
const CODEX_IDLE_MS: i64 = HOUR_MS;

#[derive(Debug, Clone)]
pub struct Options {
    /// `~/.omp/agent/sessions`.
    pub omp_root: PathBuf,
    /// `~/.claude/projects` (or `$CLAUDE_CONFIG_DIR/projects`).
    pub claude_root: PathBuf,
    /// `~/.codex` (or `$CODEX_HOME`).
    pub codex_home: PathBuf,
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
        let codex_home = std::env::var_os("CODEX_HOME")
            .filter(|d| !d.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".codex"));
        Options {
            omp_root: home.join(".omp/agent/sessions"),
            claude_root,
            codex_home,
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
            let repo = match &e.repo {
                Some(r) => Some(r.clone()),
                None => self.repo(store, e.cwd.as_deref())?,
            };
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
                ..Default::default()
            },
        );
    }
    let mut out = Vec::new();
    for s in Source::ALL {
        let mut h = by.remove(s.harness()).unwrap_or(SourceHealth {
            harness: s.harness().into(),
            ..Default::default()
        });
        h.status = "ok".into();
        if s == Source::Codex {
            if let Some(st) = store.meta("codex_status")? {
                h.status = st;
            }
            h.reason = store.meta("codex_reason")?.filter(|r| !r.is_empty());
            h.threads = Some(store.codex_thread_counts()?);
        }
        out.push(h);
    }
    Ok(out)
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
    // A store that holds less than the default window (one filled when the default was 14 days)
    // widens once on its own: the installed trigger runs a plain `sync`.
    let wanted = if opts.backfill {
        0
    } else {
        let days = opts.days.unwrap_or(DEFAULT_DAYS).max(1);
        day_start(now - days * DAY_MS + DAY_MS)
    };
    let mut reimported = false;
    let counted_from = match stored {
        None => {
            store.reset(wanted)?;
            wanted
        }
        Some(s) if wanted < s => {
            store.reset(wanted)?;
            reimported = true;
            wanted
        }
        Some(s) => s,
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
    let mut report = Report {
        counted_from_ms: counted_from,
        reimported,
        machine,
        ..Report::default()
    };
    let mut pending_sources: BTreeSet<Source> = BTreeSet::new();
    // Codex first, with at most half the budget: once imported, a run reads a few small tails.
    let codex_limits = ReadLimits {
        chunk_bytes: opts.chunk_bytes,
        max_line: opts.max_line,
        deadline: started + opts.budget / 2,
        chunks_left: opts.chunk_limit.map(std::cell::Cell::new),
    };
    let codex_read = codex_pass(
        opts,
        &mut store,
        &mut attr,
        counted_from,
        &codex_limits,
        &mut report,
        &mut pending_sources,
    )?;
    trace.mark("codex");

    let mut candidates: Vec<Candidate> = Vec::new();
    scan::discover(&opts.omp_root, Source::Omp, &mut candidates);
    scan::discover(&opts.claude_root, Source::Claude, &mut candidates);
    // Newest first: recent usage is what people look at, and an old backlog can wait a run.
    candidates.sort_by(|a, b| {
        b.mtime_ms
            .cmp(&a.mtime_ms)
            .then_with(|| a.path.cmp(&b.path))
    });

    report.files_seen += candidates.len();
    let limits = ReadLimits {
        chunk_bytes: opts.chunk_bytes,
        max_line: opts.max_line,
        deadline,
        chunks_left: opts.chunk_limit.map(std::cell::Cell::new),
    };
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
        let res = read_into_store(
            &mut store,
            &mut attr,
            &mut state,
            c.source,
            rewritten,
            &limits,
            counted_from,
            now,
            &mut report,
        );
        match res {
            Ok(outcome) => {
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
        if !pending_sources.contains(&s) && (s != Source::Codex || codex_read) {
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

/// Reads one file from its saved state, committing each chunk (rows, offset and, for Codex, the
/// thread's running total) in one transaction, then records the file's final state.
#[allow(clippy::too_many_arguments)]
fn read_into_store(
    store: &mut Store,
    attr: &mut Attributor,
    state: &mut FileState,
    source: Source,
    rewritten: bool,
    limits: &ReadLimits,
    counted_from: i64,
    now: i64,
    report: &mut Report,
) -> io::Result<ReadOutcome> {
    let mut first = true;
    let outcome = scan::read_file(
        state,
        source,
        limits,
        |st: &FileState, events, mut health| {
            if first && rewritten {
                health.rewrites += 1;
            }
            first = false;
            let attributed = attr
                .attribute(store, events, counted_from)
                .map_err(io::Error::other)?;
            let applied = store
                .apply_chunk(st, &attributed, health, now)
                .map_err(io::Error::other)?;
            report.health.add(&applied);
            Ok(())
        },
    )?;
    report.files_read += 1;
    report.bytes_read += outcome.bytes;
    if rewritten && first {
        // Nothing was read (an emptied file): still count the rewrite.
        let h = Health {
            rewrites: 1,
            ..Health::default()
        };
        store
            .apply_chunk(state, &[], h, now)
            .map_err(io::Error::other)?;
        report.health.add(&h);
    } else {
        store.put_file(state).map_err(io::Error::other)?;
    }
    Ok(outcome)
}

fn set_codex_status(store: &Store, status: &str, reason: &str) -> rusqlite::Result<()> {
    store.set_meta("codex_status", status)?;
    store.set_meta("codex_reason", reason)
}

/// Codex threads changed since the last run: each rollout from where the last run stopped (its
/// tail, once imported), else the thread's `tokens_used` as `total_only`. Returns whether the
/// thread list could be read.
fn codex_pass(
    opts: &Options,
    store: &mut Store,
    attr: &mut Attributor,
    counted_from: i64,
    limits: &ReadLimits,
    report: &mut Report,
    pending: &mut BTreeSet<Source>,
) -> Result<bool, Error> {
    let now = opts.now_ms;
    let Some(db) = codex::state_db(&opts.codex_home) else {
        set_codex_status(store, "absent", "")?;
        return Ok(false);
    };
    let watermark = store.meta_i64("codex_watermark_ms")?;
    let since = watermark.map_or(counted_from, |w| (w - CODEX_SLACK_MS).max(counted_from));
    let (threads, parents) = match codex::read_state(&db, since) {
        State::Threads { threads, parents } => (threads, parents),
        State::Absent => {
            set_codex_status(store, "absent", "")?;
            return Ok(false);
        }
        State::Unsupported(reason) => {
            set_codex_status(store, "unsupported", &reason)?;
            return Ok(false);
        }
        State::Unreadable(reason) => {
            // Try again next run; what was counted stays.
            set_codex_status(store, "degraded", &reason)?;
            return Ok(false);
        }
    };
    report.files_seen += threads.len();
    let mut mark = watermark.unwrap_or(counted_from);
    let mut blocked = false;
    let (mut bad, mut unreadable, mut cache_writes, mut decreases) = (0i64, 0i64, 0i64, 0i64);
    for t in &threads {
        let rollout = codex::resolve_rollout(&opts.codex_home, t.rollout_path.as_deref());
        let c = rollout.as_deref().and_then(scan::candidate_of);
        if blocked || limits.exhausted() {
            blocked = true;
            report.stopped_by_budget = true;
            if let Some(c) = &c {
                let offset = store
                    .file(&c.path.to_string_lossy())?
                    .map_or(0, |f| f.offset);
                report.pending_bytes += c.size.saturating_sub(offset);
            }
            report.pending_files += 1;
            pending.insert(Source::Codex);
            continue;
        }
        let (old_path, cum, counted) = store.codex_thread(&t.id)?.unwrap_or_default();
        let mut ctx = t.ctx(parents.get(&t.id), cum, counted);
        // `complete`: everything the rollout holds is folded into `ctx`.
        let mut complete = rollout.is_none();
        match &c {
            None if rollout.is_some() => unreadable += 1,
            None => {}
            Some(c) => {
                let key = c.path.to_string_lossy().into_owned();
                let mut prev = store.file(&key)?;
                if prev.is_none()
                    && let Some(op) = old_path.as_deref().filter(|op| *op != key)
                    && let Some(mut o) = store.file(op)?
                    && o.dev == c.dev
                    && o.ino == c.ino
                {
                    // Moved (archived) in place: carry the offset over instead of reading again.
                    o.path = key.clone();
                    prev = Some(o);
                }
                match scan::plan(c, prev) {
                    Err(_) => unreadable += 1,
                    Ok(Plan::Unchanged) => complete = true,
                    Ok(Plan::Read { state, rewritten }) => {
                        let mut state = *state;
                        // The running total belongs to the thread, so a rollout read again from
                        // the start (rewritten or copied elsewhere) counts nothing twice.
                        state.ctx.codex = Some(ctx.clone());
                        match read_into_store(
                            store,
                            attr,
                            &mut state,
                            Source::Codex,
                            rewritten,
                            limits,
                            counted_from,
                            now,
                            report,
                        ) {
                            Ok(outcome) => {
                                if let Some(cx) = state.ctx.codex.take() {
                                    ctx = cx;
                                }
                                if outcome.stopped {
                                    blocked = true;
                                    report.stopped_by_budget = true;
                                    report.pending_bytes += c.size.saturating_sub(state.offset);
                                    report.pending_files += 1;
                                    pending.insert(Source::Codex);
                                } else {
                                    complete = true;
                                }
                            }
                            Err(_) => unreadable += 1,
                        }
                    }
                }
            }
        }
        bad += ctx.bad;
        cache_writes += ctx.cache_writes;
        decreases += ctx.decreases;
        let no_split = rollout.is_none()
            || ctx.bad > 0
            || (ctx.cum.total == 0 && now - t.updated_at_ms > CODEX_IDLE_MS);
        if complete && no_split && t.tokens_used > ctx.counted {
            let tokens = Tokens {
                unsplit: t.tokens_used - ctx.counted,
                ..Tokens::default()
            };
            ctx.counted = t.tokens_used;
            let e = codex::event(&ctx, t.updated_at_ms, tokens, Precision::TotalOnly);
            let attributed = attr.attribute(store, vec![e], counted_from)?;
            let h = Health {
                usage_lines: 1,
                ..Health::default()
            };
            let applied = store.apply_codex(&ctx, &attributed, h, now)?;
            report.health.add(&applied);
        }
        if !blocked {
            mark = mark.max(t.updated_at_ms);
        }
    }
    if Some(mark) != watermark {
        store.set_meta("codex_watermark_ms", &mark.to_string())?;
    }
    let total_bad = store.meta_i64("codex_bad_lines")?.unwrap_or(0) + bad;
    store.set_meta("codex_bad_lines", &total_bad.to_string())?;
    let mut reasons = Vec::new();
    if total_bad > 0 {
        reasons.push(format!(
            "{total_bad} token_count lines in an unknown format; their threads count as total_only"
        ));
    }
    if unreadable > 0 {
        reasons.push(format!("{unreadable} rollouts could not be read"));
    }
    if decreases > 0 {
        reasons.push(format!(
            "{decreases} running totals went down; counted as 0"
        ));
    }
    let degraded = !reasons.is_empty();
    if cache_writes > 0 {
        reasons.push(format!(
            "{cache_writes} lines have cache_write_input_tokens, counted as uncached input"
        ));
    }
    set_codex_status(
        store,
        if degraded { "degraded" } else { "ok" },
        &reasons.join("; "),
    )?;
    Ok(true)
}
