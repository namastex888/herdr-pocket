//! The per-machine SQLite store (WAL).
//!
//! - `usage`: `ae/1` `agent.usage` rows, one per rollup key and hour, upserted on their id. The
//!   `ae_usage` view renders each row as an `ae/1` envelope.
//! - `files`: how far each log has been read, plus what identifies it (device, inode, size, a hash
//!   of its first bytes) so a rewritten or truncated file is noticed.
//! - `seen`: a bounded set of dedupe-key hashes, oldest evicted first.
//! - `sessions`: which harness session a sub-agent transcript rolls up to.
//!
//! Every chunk of a log is applied in one transaction that adds its usage, records its keys and
//! advances the file's offset, so a killed run leaves either all of a chunk or none of it.

use std::collections::BTreeMap;
use std::path::Path;

use rusqlite::{Connection, OptionalExtension, Transaction, params};

use crate::event::{Precision, RollupKey, Tokens, key_hash};
use crate::sources::FileCtx;

pub const SCHEMA_VERSION: i64 = 1;
/// How many dedupe keys are kept. Claude repeats a message on adjacent lines, so the window only
/// needs to cover a few days of messages; this covers several weeks on a busy machine.
pub const SEEN_CAP: i64 = 600_000;

pub struct Store {
    pub conn: Connection,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FileState {
    pub path: String,
    pub source: String,
    pub dev: u64,
    pub ino: u64,
    pub size: u64,
    pub mtime_ms: i64,
    pub offset: u64,
    /// Bytes before the hashed head (omp rewrites its first line, the title, in place).
    pub head_skip: u64,
    pub head_len: u64,
    pub head_hash: String,
    pub ctx: FileCtx,
    pub rewrites: i64,
}

/// What one chunk adds to a source's parser health.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Health {
    pub usage_lines: i64,
    pub events: i64,
    pub duplicates: i64,
    pub skipped_lines: i64,
    pub rewrites: i64,
}

impl Health {
    pub fn add(&mut self, o: &Health) {
        self.usage_lines += o.usage_lines;
        self.events += o.events;
        self.duplicates += o.duplicates;
        self.skipped_lines += o.skipped_lines;
        self.rewrites += o.rewrites;
    }
}

/// One event after attribution, ready to be folded into its hourly row.
#[derive(Debug, Clone)]
pub struct Attributed {
    pub dedupe_key: String,
    pub key: RollupKey,
    pub squad: Option<String>,
    pub tokens: Tokens,
    pub precision: Precision,
    pub reported_usd: Option<f64>,
    pub session: Option<String>,
    pub parent_session: Option<String>,
    pub ts_ms: i64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct UsageRow {
    pub key: RollupKey,
    pub squad: Option<String>,
    pub tokens: Tokens,
    pub precision: String,
    pub reported_usd: Option<f64>,
    pub events: i64,
}

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS usage (
  id TEXT PRIMARY KEY,
  hour_ms INTEGER NOT NULL,
  bot TEXT, role TEXT, squad TEXT,
  harness TEXT NOT NULL, model TEXT NOT NULL, machine TEXT NOT NULL,
  repo TEXT, branch TEXT,
  input_uncached INTEGER NOT NULL DEFAULT 0,
  cache_read INTEGER NOT NULL DEFAULT 0,
  cache_write_5m INTEGER NOT NULL DEFAULT 0,
  cache_write_1h INTEGER NOT NULL DEFAULT 0,
  output INTEGER NOT NULL DEFAULT 0,
  reasoning INTEGER NOT NULL DEFAULT 0,
  precision TEXT NOT NULL,
  reported_usd REAL,
  events INTEGER NOT NULL DEFAULT 0,
  updated_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS usage_hour ON usage(hour_ms);
CREATE TABLE IF NOT EXISTS files (
  path TEXT PRIMARY KEY, source TEXT NOT NULL,
  dev INTEGER NOT NULL, ino INTEGER NOT NULL, size INTEGER NOT NULL, mtime_ms INTEGER NOT NULL,
  offset INTEGER NOT NULL, head_skip INTEGER NOT NULL DEFAULT 0,
  head_len INTEGER NOT NULL, head_hash TEXT NOT NULL,
  ctx TEXT NOT NULL DEFAULT '{}', rewrites INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS seen (k INTEGER PRIMARY KEY, seq INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS seen_seq ON seen(seq);
CREATE TABLE IF NOT EXISTS sessions (
  harness TEXT NOT NULL, session TEXT NOT NULL, parent_session TEXT,
  first_ms INTEGER NOT NULL, last_ms INTEGER NOT NULL,
  PRIMARY KEY (harness, session)
);
CREATE TABLE IF NOT EXISTS cwd_repo (cwd TEXT PRIMARY KEY, repo TEXT, checked_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS health (
  source TEXT PRIMARY KEY,
  last_success_ms INTEGER,
  usage_lines INTEGER NOT NULL DEFAULT 0, events INTEGER NOT NULL DEFAULT 0,
  duplicates INTEGER NOT NULL DEFAULT 0, skipped_lines INTEGER NOT NULL DEFAULT 0,
  rewrites INTEGER NOT NULL DEFAULT 0
);
CREATE VIEW IF NOT EXISTS ae_usage AS SELECT id, json_object(
  'schema', 'ae/1', 'id', id, 'type', 'agent.usage',
  'ts', strftime('%Y-%m-%dT%H:%M:%SZ', hour_ms / 1000, 'unixepoch'),
  'actor', json_object('bot', bot, 'role', role, 'squad', squad, 'harness', harness,
                       'model', model, 'machine', machine),
  'work', json_object('repo', repo, 'branch', branch),
  'trust', 'agent',
  'data', json_object('input_uncached', input_uncached, 'cache_read', cache_read,
                      'cache_write_5m', cache_write_5m, 'cache_write_1h', cache_write_1h,
                      'output', output, 'reasoning', reasoning, 'precision', precision,
                      'reported_usd', reported_usd),
  'idempotency_key', id) AS envelope FROM usage;
";

impl Store {
    pub fn open(path: &Path) -> rusqlite::Result<Store> {
        let conn = Connection::open(path)?;
        Store::init(conn)
    }

    pub fn open_in_memory() -> rusqlite::Result<Store> {
        Store::init(Connection::open_in_memory()?)
    }

    fn init(conn: Connection) -> rusqlite::Result<Store> {
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.execute_batch(SCHEMA)?;
        // Stores made before `head_skip` existed.
        let has_skip: bool = conn.query_row(
            "SELECT count(*) > 0 FROM pragma_table_info('files') WHERE name = 'head_skip'",
            [],
            |r| r.get(0),
        )?;
        if !has_skip {
            conn.execute_batch(
                "ALTER TABLE files ADD COLUMN head_skip INTEGER NOT NULL DEFAULT 0",
            )?;
        }
        let s = Store { conn };
        if s.meta("schema_version")?.is_none() {
            s.set_meta("schema_version", &SCHEMA_VERSION.to_string())?;
        }
        Ok(s)
    }

    pub fn meta(&self, k: &str) -> rusqlite::Result<Option<String>> {
        self.conn
            .query_row("SELECT v FROM meta WHERE k = ?1", [k], |r| r.get(0))
            .optional()
    }

    pub fn meta_i64(&self, k: &str) -> rusqlite::Result<Option<i64>> {
        Ok(self.meta(k)?.and_then(|v| v.parse().ok()))
    }

    pub fn set_meta(&self, k: &str, v: &str) -> rusqlite::Result<()> {
        self.conn.execute(
            "INSERT INTO meta(k, v) VALUES(?1, ?2) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
            [k, v],
        )?;
        Ok(())
    }

    /// Drops every row and offset so the next read imports again from `counted_from`.
    pub fn reset(&mut self, counted_from_ms: i64) -> rusqlite::Result<()> {
        let tx = self.conn.transaction()?;
        tx.execute_batch("DELETE FROM usage; DELETE FROM files; DELETE FROM seen; DELETE FROM sessions; DELETE FROM health;")?;
        tx.execute(
            "INSERT INTO meta(k, v) VALUES('counted_from_ms', ?1) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
            [counted_from_ms.to_string()],
        )?;
        tx.execute("DELETE FROM meta WHERE k IN ('last_success_ms', 'last_pending_bytes', 'last_pending_files')", [])?;
        tx.commit()
    }

    pub fn file(&self, path: &str) -> rusqlite::Result<Option<FileState>> {
        self.conn
            .query_row(
                "SELECT path, source, dev, ino, size, mtime_ms, offset, head_len, head_hash, ctx, rewrites, head_skip
                 FROM files WHERE path = ?1",
                [path],
                |r| {
                    let ctx: String = r.get(9)?;
                    Ok(FileState {
                        path: r.get(0)?,
                        source: r.get(1)?,
                        dev: r.get::<_, i64>(2)? as u64,
                        ino: r.get::<_, i64>(3)? as u64,
                        size: r.get::<_, i64>(4)? as u64,
                        mtime_ms: r.get(5)?,
                        offset: r.get::<_, i64>(6)? as u64,
                        head_len: r.get::<_, i64>(7)? as u64,
                        head_hash: r.get(8)?,
                        ctx: serde_json::from_str(&ctx).unwrap_or_default(),
                        rewrites: r.get(10)?,
                        head_skip: r.get::<_, i64>(11)? as u64,
                    })
                },
            )
            .optional()
    }

    pub fn cached_repo(&self, cwd: &str) -> rusqlite::Result<Option<(Option<String>, i64)>> {
        self.conn
            .query_row(
                "SELECT repo, checked_ms FROM cwd_repo WHERE cwd = ?1",
                [cwd],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()
    }

    pub fn cache_repo(&self, cwd: &str, repo: Option<&str>, now_ms: i64) -> rusqlite::Result<()> {
        self.conn.execute(
            "INSERT INTO cwd_repo(cwd, repo, checked_ms) VALUES(?1, ?2, ?3)
             ON CONFLICT(cwd) DO UPDATE SET repo = excluded.repo, checked_ms = excluded.checked_ms",
            params![cwd, repo, now_ms],
        )?;
        Ok(())
    }

    /// Applies one chunk atomically: new usage, its dedupe keys, the file's new offset and the
    /// health counters. Returns how many events were new (not seen before).
    pub fn apply_chunk(
        &mut self,
        file: &FileState,
        events: &[Attributed],
        mut health: Health,
        now_ms: i64,
    ) -> rusqlite::Result<Health> {
        let tx = self.conn.transaction()?;
        let mut seq: i64 =
            tx.query_row("SELECT coalesce(max(seq), 0) FROM seen", [], |r| r.get(0))?;
        let mut rows: BTreeMap<RollupKey, Pending> = BTreeMap::new();
        {
            let mut ins = tx.prepare_cached("INSERT OR IGNORE INTO seen(k, seq) VALUES(?1, ?2)")?;
            let mut sess = tx.prepare_cached(
                "INSERT INTO sessions(harness, session, parent_session, first_ms, last_ms) VALUES(?1, ?2, ?3, ?4, ?4)
                 ON CONFLICT(harness, session) DO UPDATE SET
                   parent_session = coalesce(excluded.parent_session, parent_session),
                   first_ms = min(first_ms, excluded.first_ms), last_ms = max(last_ms, excluded.last_ms)",
            )?;
            for e in events {
                seq += 1;
                if ins.execute(params![key_hash(&e.dedupe_key), seq])? == 0 {
                    health.duplicates += 1;
                    continue;
                }
                health.events += 1;
                let entry = rows.entry(e.key.clone()).or_insert((
                    None,
                    Tokens::default(),
                    Precision::Exact,
                    None,
                    0,
                ));
                if e.squad.is_some() {
                    entry.0.clone_from(&e.squad);
                }
                entry.1.add(&e.tokens);
                if e.precision == Precision::TotalOnly {
                    entry.2 = Precision::TotalOnly;
                }
                if let Some(usd) = e.reported_usd {
                    entry.3 = Some(entry.3.unwrap_or(0.0) + usd);
                }
                entry.4 += 1;
                if let Some(s) = &e.session {
                    sess.execute(params![e.key.harness, s, e.parent_session, e.ts_ms])?;
                }
            }
        }
        upsert_rows(&tx, &rows, now_ms)?;
        put_file(&tx, file)?;
        tx.execute(
            "INSERT INTO health(source, usage_lines, events, duplicates, skipped_lines, rewrites)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(source) DO UPDATE SET usage_lines = usage_lines + excluded.usage_lines,
               events = events + excluded.events, duplicates = duplicates + excluded.duplicates,
               skipped_lines = skipped_lines + excluded.skipped_lines, rewrites = rewrites + excluded.rewrites",
            params![file.source, health.usage_lines, health.events, health.duplicates, health.skipped_lines, health.rewrites],
        )?;
        tx.commit()?;
        Ok(health)
    }

    /// Records a file's identity without reading it (for example, a file that did not change).
    pub fn put_file(&mut self, file: &FileState) -> rusqlite::Result<()> {
        let tx = self.conn.transaction()?;
        put_file(&tx, file)?;
        tx.commit()
    }

    /// Evicts the oldest dedupe keys beyond `cap`.
    pub fn trim_seen(&self, cap: i64) -> rusqlite::Result<usize> {
        self.conn.execute(
            "DELETE FROM seen WHERE seq <= (SELECT max(seq) FROM seen) - ?1",
            [cap],
        )
    }

    pub fn mark_success(&self, source: &str, now_ms: i64) -> rusqlite::Result<()> {
        self.conn.execute(
            "INSERT INTO health(source, last_success_ms) VALUES(?1, ?2)
             ON CONFLICT(source) DO UPDATE SET last_success_ms = excluded.last_success_ms",
            params![source, now_ms],
        )?;
        Ok(())
    }

    pub fn health(&self) -> rusqlite::Result<Vec<(String, Option<i64>, Health)>> {
        let mut st = self.conn.prepare(
            "SELECT source, last_success_ms, usage_lines, events, duplicates, skipped_lines, rewrites FROM health ORDER BY source",
        )?;
        let rows = st.query_map([], |r| {
            Ok((
                r.get(0)?,
                r.get(1)?,
                Health {
                    usage_lines: r.get(2)?,
                    events: r.get(3)?,
                    duplicates: r.get(4)?,
                    skipped_lines: r.get(5)?,
                    rewrites: r.get(6)?,
                },
            ))
        })?;
        rows.collect()
    }

    pub fn rows_since(&self, from_ms: i64) -> rusqlite::Result<Vec<UsageRow>> {
        let mut st = self.conn.prepare(
            "SELECT bot, role, harness, model, machine, repo, branch, hour_ms, squad,
                    input_uncached, cache_read, cache_write_5m, cache_write_1h, output, reasoning,
                    precision, reported_usd, events
             FROM usage WHERE hour_ms >= ?1 ORDER BY hour_ms, harness, model, repo, branch, bot, role",
        )?;
        let rows = st.query_map([from_ms], |r| {
            Ok(UsageRow {
                key: RollupKey {
                    bot: r.get(0)?,
                    role: r.get(1)?,
                    harness: r.get(2)?,
                    model: r.get(3)?,
                    machine: r.get(4)?,
                    repo: r.get(5)?,
                    branch: r.get(6)?,
                    hour_ms: r.get(7)?,
                },
                squad: r.get(8)?,
                tokens: Tokens {
                    input_uncached: r.get(9)?,
                    cache_read: r.get(10)?,
                    cache_write_5m: r.get(11)?,
                    cache_write_1h: r.get(12)?,
                    output: r.get(13)?,
                    reasoning: r.get(14)?,
                },
                precision: r.get(15)?,
                reported_usd: r.get(16)?,
                events: r.get(17)?,
            })
        })?;
        rows.collect()
    }

    pub fn count(&self, table: &str) -> rusqlite::Result<i64> {
        let sql = match table {
            "usage" => "SELECT count(*) FROM usage",
            "files" => "SELECT count(*) FROM files",
            "seen" => "SELECT count(*) FROM seen",
            "sessions" => "SELECT count(*) FROM sessions",
            _ => return Ok(0),
        };
        self.conn.query_row(sql, [], |r| r.get(0))
    }
}

fn put_file(tx: &Transaction<'_>, f: &FileState) -> rusqlite::Result<()> {
    let ctx = serde_json::to_string(&f.ctx).unwrap_or_else(|_| "{}".into());
    tx.prepare_cached(
        "INSERT INTO files(path, source, dev, ino, size, mtime_ms, offset, head_len, head_hash, ctx, rewrites, head_skip)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
         ON CONFLICT(path) DO UPDATE SET source = excluded.source, dev = excluded.dev, ino = excluded.ino,
           size = excluded.size, mtime_ms = excluded.mtime_ms, offset = excluded.offset,
           head_len = excluded.head_len, head_hash = excluded.head_hash, head_skip = excluded.head_skip, ctx = excluded.ctx,
           rewrites = excluded.rewrites",
    )?
    .execute(params![
        f.path,
        f.source,
        f.dev as i64,
        f.ino as i64,
        f.size as i64,
        f.mtime_ms,
        f.offset as i64,
        f.head_len as i64,
        f.head_hash,
        ctx,
        f.rewrites,
        f.head_skip as i64
    ])?;
    Ok(())
}

type Pending = (Option<String>, Tokens, Precision, Option<f64>, i64);

fn upsert_rows(
    tx: &Transaction<'_>,
    rows: &BTreeMap<RollupKey, Pending>,
    now_ms: i64,
) -> rusqlite::Result<()> {
    let mut st = tx.prepare_cached(
        "INSERT INTO usage(id, hour_ms, bot, role, squad, harness, model, machine, repo, branch,
           input_uncached, cache_read, cache_write_5m, cache_write_1h, output, reasoning,
           precision, reported_usd, events, updated_ms)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20)
         ON CONFLICT(id) DO UPDATE SET
           squad = coalesce(excluded.squad, squad),
           input_uncached = input_uncached + excluded.input_uncached,
           cache_read = cache_read + excluded.cache_read,
           cache_write_5m = cache_write_5m + excluded.cache_write_5m,
           cache_write_1h = cache_write_1h + excluded.cache_write_1h,
           output = output + excluded.output,
           reasoning = reasoning + excluded.reasoning,
           precision = CASE WHEN excluded.precision = 'total_only' THEN 'total_only' ELSE precision END,
           reported_usd = CASE WHEN excluded.reported_usd IS NULL THEN reported_usd
                               ELSE coalesce(reported_usd, 0) + excluded.reported_usd END,
           events = events + excluded.events,
           updated_ms = excluded.updated_ms",
    )?;
    for (k, (squad, t, precision, usd, n)) in rows {
        st.execute(params![
            k.id(),
            k.hour_ms,
            k.bot,
            k.role,
            squad,
            k.harness,
            k.model,
            k.machine,
            k.repo,
            k.branch,
            t.input_uncached,
            t.cache_read,
            t.cache_write_5m,
            t.cache_write_1h,
            t.output,
            t.reasoning,
            precision.as_str(),
            usd,
            n,
            now_ms
        ])?;
    }
    Ok(())
}
