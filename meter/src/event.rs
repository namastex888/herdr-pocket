//! What a parser hands to the store: one API call's usage, before it is folded into an hourly row.

use sha2::{Digest, Sha256};

/// Token counts in `ae/1` `agent.usage` order. Every field is disjoint from the others except
/// `reasoning`, which is the part of `output` spent thinking (never added to totals).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Tokens {
    pub input_uncached: i64,
    pub cache_read: i64,
    pub cache_write_5m: i64,
    pub cache_write_1h: i64,
    pub output: i64,
    pub reasoning: i64,
    /// Tokens known only as a total, with no split (`precision: total_only`). Never priced.
    pub unsplit: i64,
}

impl Tokens {
    pub fn add(&mut self, o: &Tokens) {
        self.input_uncached += o.input_uncached;
        self.cache_read += o.cache_read;
        self.cache_write_5m += o.cache_write_5m;
        self.cache_write_1h += o.cache_write_1h;
        self.output += o.output;
        self.reasoning += o.reasoning;
        self.unsplit += o.unsplit;
    }

    /// All tokens: everything except `reasoning`, which `output` already contains.
    pub fn total(&self) -> i64 {
        self.split_total() + self.unsplit
    }

    /// The tokens whose type is known (everything but `unsplit`).
    pub fn split_total(&self) -> i64 {
        self.input_uncached
            + self.cache_read
            + self.cache_write_5m
            + self.cache_write_1h
            + self.output
    }

    pub fn is_zero(&self) -> bool {
        self.total() == 0 && self.reasoning == 0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Precision {
    /// The full split (uncached input, cache reads, cache writes, output) is known.
    Exact,
    /// Only a total is known; no $ is ever derived from it.
    TotalOnly,
}

impl Precision {
    pub fn as_str(self) -> &'static str {
        match self {
            Precision::Exact => "exact",
            Precision::TotalOnly => "total_only",
        }
    }
}

/// One deduplicated API call.
#[derive(Debug, Clone, PartialEq)]
pub struct UsageEvent {
    /// Stable per source; the store keeps a bounded set of these to drop repeats. Empty for a
    /// source that cannot repeat an event (Codex: deltas of a per-thread cumulative).
    pub dedupe_key: String,
    pub ts_ms: i64,
    pub harness: &'static str,
    pub model: String,
    pub session: Option<String>,
    /// Set on sub-agent transcripts: the usage belongs to this session.
    pub parent_session: Option<String>,
    pub cwd: Option<String>,
    pub branch: Option<String>,
    /// `host/owner/repo` when the harness recorded the remote itself (Codex); else from `cwd`.
    pub repo: Option<String>,
    pub tokens: Tokens,
    pub precision: Precision,
    /// $ the harness reported itself (omp's `cost.total`), never derived.
    pub reported_usd: Option<f64>,
}

/// The attribution part of the rollup key, resolved after parsing.
#[derive(Debug, Clone, Default, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Actor {
    pub bot: Option<String>,
    pub role: Option<String>,
    pub squad: Option<String>,
}

/// (bot, role, harness, model, machine, repo, branch, hour): one `agent.usage` row.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct RollupKey {
    pub bot: Option<String>,
    pub role: Option<String>,
    pub harness: String,
    pub model: String,
    pub machine: String,
    pub repo: Option<String>,
    pub branch: Option<String>,
    pub hour_ms: i64,
}

impl RollupKey {
    /// `idempotency_key` = hash of the rollup key + hour + precision (`ae/1` revision 3). The same
    /// hour maps to at most one exact row and one total_only row.
    pub fn id(&self, precision: Precision) -> String {
        let mut h = Sha256::new();
        for part in [
            self.bot.as_deref(),
            self.role.as_deref(),
            Some(self.harness.as_str()),
            Some(self.model.as_str()),
            Some(self.machine.as_str()),
            self.repo.as_deref(),
            self.branch.as_deref(),
        ] {
            match part {
                Some(s) => {
                    h.update([1u8]);
                    h.update(s.as_bytes());
                }
                None => h.update([0u8]),
            }
            h.update([0x1f]);
        }
        h.update(self.hour_ms.to_be_bytes());
        h.update([0x1f]);
        h.update(precision.as_str().as_bytes());
        hex(&h.finalize()[..16])
    }
}

pub fn hex(bytes: &[u8]) -> String {
    const D: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(D[(b >> 4) as usize] as char);
        s.push(D[(b & 15) as usize] as char);
    }
    s
}

/// 63-bit hash of a dedupe key, stored as an SQLite integer key.
pub fn key_hash(key: &str) -> i64 {
    let d = Sha256::digest(key.as_bytes());
    let mut b = [0u8; 8];
    b.copy_from_slice(&d[..8]);
    (u64::from_be_bytes(b) >> 1) as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(branch: Option<&str>, hour_ms: i64) -> RollupKey {
        RollupKey {
            bot: None,
            role: None,
            harness: "claude-code".into(),
            model: "model-a".into(),
            machine: "m1".into(),
            repo: Some("example.com/acme/app".into()),
            branch: branch.map(Into::into),
            hour_ms,
        }
    }

    #[test]
    fn row_id_is_stable_and_distinguishes_null_from_empty() {
        let id = |k: RollupKey| k.id(Precision::Exact);
        assert_eq!(id(key(Some("main"), 0)), id(key(Some("main"), 0)));
        assert_ne!(id(key(Some("main"), 0)), id(key(Some("main"), 3_600_000)));
        assert_ne!(id(key(None, 0)), id(key(Some(""), 0)));
        assert_ne!(id(key(None, 0)), key(None, 0).id(Precision::TotalOnly));
    }

    #[test]
    fn total_excludes_reasoning() {
        let t = Tokens {
            input_uncached: 1,
            cache_read: 2,
            cache_write_5m: 3,
            cache_write_1h: 4,
            output: 5,
            reasoning: 5,
            unsplit: 6,
        };
        assert_eq!(t.total(), 21);
        assert_eq!(t.split_total(), 15);
    }
}
