//! Finds harness logs and reads what is new in them.
//!
//! - Discovery never follows a symlink, so nothing outside the source roots is read.
//! - A file is read from its saved offset in chunks of `chunk_bytes`; only complete lines are
//!   parsed and the offset only moves past complete lines, so a line still being written waits for
//!   the next run.
//! - A file is recognised by device, inode and a hash of its first bytes. If any of them changed,
//!   or the file shrank below the saved offset, it is read again from the start; the dedupe set
//!   absorbs what was counted before.
//! - A line longer than `max_line` is skipped and counted, never buffered whole.

use std::fs::{self, File};
use std::io::{self, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::time::Instant;

use sha2::{Digest, Sha256};

use crate::event::{UsageEvent, hex};
use crate::sources::{FileCtx, Line, Source};
use crate::store::{FileState, Health};

pub const HEAD_BYTES: u64 = 4096;

#[derive(Debug, Clone)]
pub struct Candidate {
    pub path: PathBuf,
    pub source: Source,
    pub dev: u64,
    pub ino: u64,
    pub size: u64,
    pub mtime_ms: i64,
}

#[cfg(unix)]
fn ids(m: &fs::Metadata) -> (u64, u64) {
    use std::os::unix::fs::MetadataExt;
    (m.dev(), m.ino())
}

#[cfg(not(unix))]
fn ids(_: &fs::Metadata) -> (u64, u64) {
    (0, 0)
}

fn mtime_ms(m: &fs::Metadata) -> i64 {
    m.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map_or(0, |d| d.as_millis() as i64)
}

/// Every `*.jsonl` under `root` (depth-limited), skipping symlinks to files and directories.
pub fn discover(root: &Path, source: Source, out: &mut Vec<Candidate>) {
    fn walk(dir: &Path, depth: usize, source: Source, out: &mut Vec<Candidate>) {
        if depth > 8 {
            return;
        }
        let Ok(entries) = fs::read_dir(dir) else {
            return;
        };
        for e in entries.flatten() {
            // The entry's own type needs no extra syscall and never follows a symlink.
            let Ok(ft) = e.file_type() else { continue };
            if ft.is_symlink() {
                continue;
            }
            let path = e.path();
            if ft.is_dir() {
                walk(&path, depth + 1, source, out);
            } else if ft.is_file() && path.extension().is_some_and(|x| x == "jsonl") {
                let Ok(meta) = fs::symlink_metadata(&path) else {
                    continue;
                };
                if !meta.file_type().is_file() {
                    continue;
                }
                let (dev, ino) = ids(&meta);
                out.push(Candidate {
                    path,
                    source,
                    dev,
                    ino,
                    size: meta.len(),
                    mtime_ms: mtime_ms(&meta),
                });
            }
        }
    }
    // The root itself may be a symlink the user set up (a moved config dir); what is under it may not.
    let Ok(meta) = fs::metadata(root) else { return };
    if meta.is_dir() {
        walk(root, 0, source, out);
    }
}

/// `…/<session>/subagents/<agent>.jsonl` belongs to `<session>`.
pub fn parent_session(path: &Path) -> Option<String> {
    let dir = path.parent()?;
    if dir.file_name()? != "subagents" {
        return None;
    }
    let s = dir.parent()?.file_name()?.to_str()?;
    Some(s.chars().take(128).collect())
}

fn head_hash(f: &mut File, len: u64) -> io::Result<String> {
    let mut buf = vec![0u8; len as usize];
    f.seek(SeekFrom::Start(0))?;
    f.read_exact(&mut buf)?;
    Ok(hex(&Sha256::digest(&buf)[..16]))
}

fn open_nofollow(p: &Path) -> io::Result<File> {
    let mut o = fs::OpenOptions::new();
    o.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        const O_NOFOLLOW: i32 = if cfg!(target_os = "linux") {
            0o400000
        } else {
            0x0100
        };
        o.custom_flags(O_NOFOLLOW);
    }
    o.open(p)
}

/// Where to resume a candidate, given what the store remembers about its path.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Plan {
    /// Nothing new since the last read.
    Unchanged,
    /// Read from `state.offset`; `rewritten` when the old offset was discarded.
    Read {
        state: Box<FileState>,
        rewritten: bool,
    },
}

pub fn plan(c: &Candidate, prev: Option<FileState>) -> io::Result<Plan> {
    let path = c.path.to_string_lossy().into_owned();
    let fresh = |rewrites: i64| FileState {
        path: path.clone(),
        source: c.source.harness().to_string(),
        dev: c.dev,
        ino: c.ino,
        size: 0,
        mtime_ms: c.mtime_ms,
        offset: 0,
        head_len: 0,
        head_hash: String::new(),
        ctx: match parent_session(&c.path) {
            Some(parent) => FileCtx {
                session: c
                    .path
                    .file_stem()
                    .and_then(|s| s.to_str())
                    .map(|s| s.chars().take(128).collect()),
                parent_session: Some(parent),
                cwd: None,
            },
            None => FileCtx::default(),
        },
        rewrites,
    };
    let Some(p) = prev else {
        return Ok(Plan::Read {
            state: Box::new(fresh(0)),
            rewritten: false,
        });
    };
    let same_file = p.dev == c.dev && p.ino == c.ino;
    if same_file && p.size == c.size && p.mtime_ms == c.mtime_ms {
        return Ok(Plan::Unchanged);
    }
    let mut intact = same_file && c.size >= p.offset && c.size >= p.head_len;
    if intact && p.head_len > 0 {
        let mut f = open_nofollow(&c.path)?;
        intact = head_hash(&mut f, p.head_len)? == p.head_hash;
    }
    if !intact {
        return Ok(Plan::Read {
            state: Box::new(fresh(p.rewrites + 1)),
            rewritten: true,
        });
    }
    Ok(Plan::Read {
        state: Box::new(FileState {
            dev: c.dev,
            ino: c.ino,
            mtime_ms: c.mtime_ms,
            ..p
        }),
        rewritten: false,
    })
}

pub struct ReadLimits {
    pub chunk_bytes: usize,
    pub max_line: usize,
    pub deadline: Instant,
    /// Stop after this many chunks across the run, as if the deadline had passed (tests).
    pub chunks_left: Option<std::cell::Cell<usize>>,
}

impl ReadLimits {
    pub fn exhausted(&self) -> bool {
        Instant::now() >= self.deadline || self.chunks_left.as_ref().is_some_and(|c| c.get() == 0)
    }

    fn spend_chunk(&self) {
        if let Some(c) = &self.chunks_left {
            c.set(c.get().saturating_sub(1));
        }
    }
}

/// What a read did. `stopped` is true when the deadline cut it short.
#[derive(Debug, Default)]
pub struct ReadOutcome {
    pub bytes: u64,
    pub stopped: bool,
}

/// Reads `state.path` from `state.offset`. For every chunk of complete lines it calls `commit`
/// with the events, the parser health and the file state to save (offset already advanced).
/// `commit` must persist all three atomically.
pub fn read_file<F>(
    state: &mut FileState,
    source: Source,
    limits: &ReadLimits,
    mut commit: F,
) -> io::Result<ReadOutcome>
where
    F: FnMut(&FileState, Vec<UsageEvent>, Health) -> io::Result<()>,
{
    let mut f = open_nofollow(Path::new(&state.path))?;
    let size_now = f.metadata()?.len();
    if state.head_len < HEAD_BYTES.min(size_now) {
        state.head_len = HEAD_BYTES.min(size_now);
        state.head_hash = head_hash(&mut f, state.head_len)?;
    }
    f.seek(SeekFrom::Start(state.offset))?;
    let mut out = ReadOutcome::default();
    let mut buf: Vec<u8> = Vec::new();
    let mut file_pos = state.offset;
    let mut skipping = false;
    let mut eof = false;
    loop {
        if limits.exhausted() {
            out.stopped = true;
            break;
        }
        // Fill up to one chunk past what is already buffered (a partial line).
        // Never more than what is left (plus room for growth): most files have a few KB new.
        let left = size_now.saturating_sub(file_pos) as usize + 64 * 1024;
        let want = limits.chunk_bytes.min(left);
        let start = buf.len();
        buf.resize(start + want, 0);
        let mut filled = start;
        while filled < buf.len() {
            let n = f.read(&mut buf[filled..])?;
            if n == 0 {
                eof = true;
                break;
            }
            filled += n;
            file_pos += n as u64;
        }
        buf.truncate(filled);
        let mut health = Health::default();
        let mut events = Vec::new();
        let mut consumed = 0usize;
        if skipping {
            // The rest of an over-long line: drop bytes up to its newline.
            match memchr::memchr(b'\n', &buf) {
                Some(i) => {
                    consumed = i + 1;
                    skipping = false;
                }
                None => consumed = buf.len(),
            }
        }
        if !skipping {
            if let Some(i) = memchr::memrchr(b'\n', &buf[consumed..]) {
                let end = consumed + i;
                let mut ctx = std::mem::take(&mut state.ctx);
                for line in buf[consumed..end].split(|b| *b == b'\n') {
                    if line.len() > limits.max_line {
                        health.skipped_lines += 1;
                        continue;
                    }
                    match source.parse_line(line, &mut ctx, &mut events) {
                        Line::Ignored => {}
                        Line::Usage => health.usage_lines += 1,
                        Line::Bad => health.skipped_lines += 1,
                    }
                }
                state.ctx = ctx;
                consumed = end + 1;
            } else if buf.len() - consumed >= limits.max_line {
                // A line too long to hold: drop what is buffered and skip to its end.
                health.skipped_lines += 1;
                consumed = buf.len();
                skipping = true;
            }
        }
        if consumed > 0 {
            advance(state, &mut buf, consumed, &mut out);
            commit(state, events, health)?;
            limits.spend_chunk();
        }
        if eof {
            break;
        }
    }
    // Remember the size only when the read reached the end, so an unfinished file is read again.
    state.size = if out.stopped {
        state.offset
    } else {
        state.offset + buf.len() as u64
    };
    Ok(out)
}

fn advance(state: &mut FileState, buf: &mut Vec<u8>, consumed: usize, out: &mut ReadOutcome) {
    state.offset += consumed as u64;
    out.bytes += consumed as u64;
    buf.drain(..consumed);
}
