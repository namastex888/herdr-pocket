//! A dated price table, applied only when the summary is written. Every $ figure it yields is an
//! API-equivalent estimate: what the tokens would cost at list price, not what anyone paid.
//!
//! An override file (`prices.json` in the state directory) adds or replaces models:
//! `{"date": "2026-11-01", "models": {"model-id": {"input": 1.0, "output": 2.0, "cache_read": 0.1,
//! "cache_write_5m": 1.25, "cache_write_1h": 2.0}}}`, all in USD per million tokens.

use std::collections::BTreeMap;
use std::path::Path;

use serde::Deserialize;

use crate::event::Tokens;

#[derive(Debug, Clone, Copy, PartialEq, Deserialize)]
pub struct Price {
    pub input: f64,
    pub output: f64,
    pub cache_read: f64,
    pub cache_write_5m: f64,
    pub cache_write_1h: f64,
}

impl Price {
    pub fn cost(&self, t: &Tokens) -> f64 {
        (t.input_uncached as f64 * self.input
            + t.output as f64 * self.output
            + t.cache_read as f64 * self.cache_read
            + t.cache_write_5m as f64 * self.cache_write_5m
            + t.cache_write_1h as f64 * self.cache_write_1h)
            / 1_000_000.0
    }
}

/// Anthropic first-party list prices, USD per million tokens. Cache writes are 1.25x (5 minutes)
/// and 2x (1 hour) the input price.
pub const BUNDLED_DATE: &str = "2026-10-06";

const fn p(input: f64, output: f64, cache_read: f64) -> Price {
    Price {
        input,
        output,
        cache_read,
        cache_write_5m: input * 1.25,
        cache_write_1h: input * 2.0,
    }
}

const BUNDLED: &[(&str, Price)] = &[
    ("claude-fable-5-1", p(10.0, 50.0, 0.25)),
    ("claude-mythos-5-1", p(10.0, 50.0, 0.25)),
    ("claude-fable-5", p(10.0, 50.0, 1.0)),
    ("claude-mythos-5", p(10.0, 50.0, 1.0)),
    ("claude-opus-5-5", p(4.0, 20.0, 0.20)),
    ("claude-opus-5", p(5.0, 25.0, 0.50)),
    ("claude-opus-4-8", p(5.0, 25.0, 0.50)),
    ("claude-opus-4-7", p(5.0, 25.0, 0.50)),
    ("claude-opus-4-6", p(5.0, 25.0, 0.50)),
    ("claude-sonnet-5-5", p(2.0, 10.0, 0.20)),
    ("claude-sonnet-5", p(2.0, 10.0, 0.20)),
    ("claude-sonnet-4-6", p(3.0, 15.0, 0.30)),
    ("claude-haiku-5-5", p(0.10, 0.50, 0.01)),
    ("claude-haiku-4-5", p(1.0, 5.0, 0.10)),
];

#[derive(Debug, Clone)]
pub struct PriceTable {
    pub date: String,
    pub source: &'static str,
    models: BTreeMap<String, Price>,
}

#[derive(Deserialize)]
struct OverrideFile {
    date: Option<String>,
    #[serde(default)]
    models: BTreeMap<String, Price>,
}

/// `claude-opus-5-5-20260101`, `anthropic/claude-opus-5-5`, `claude-opus-5-5[1m]` → `claude-opus-5-5`.
pub fn normalize_model(model: &str) -> String {
    let mut m = model.trim().to_ascii_lowercase();
    if let Some(i) = m.find('[') {
        m.truncate(i);
    }
    for prefix in ["anthropic/", "anthropic.", "us.anthropic.", "eu.anthropic."] {
        if let Some(rest) = m.strip_prefix(prefix) {
            m = rest.to_string();
        }
    }
    if let Some((head, tail)) = m.rsplit_once('-')
        && tail.len() == 8
        && tail.chars().all(|c| c.is_ascii_digit())
    {
        m = head.to_string();
    }
    m
}

impl PriceTable {
    pub fn bundled() -> PriceTable {
        PriceTable {
            date: BUNDLED_DATE.to_string(),
            source: "bundled",
            models: BUNDLED.iter().map(|(k, v)| (k.to_string(), *v)).collect(),
        }
    }

    /// The bundled table plus the override file, if it exists. A broken override is an error so a
    /// typo never silently falls back to stale prices.
    pub fn load(override_path: &Path) -> Result<PriceTable, String> {
        let mut t = PriceTable::bundled();
        let text = match std::fs::read_to_string(override_path) {
            Ok(s) => s,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(t),
            Err(e) => {
                return Err(format!(
                    "cannot read the price override {}: {e}",
                    override_path.display()
                ));
            }
        };
        let o: OverrideFile = serde_json::from_str(&text).map_err(|e| {
            format!(
                "price override {} is not valid: {e}",
                override_path.display()
            )
        })?;
        for (k, v) in o.models {
            t.models.insert(normalize_model(&k), v);
        }
        t.source = "bundled+override";
        if let Some(d) = o.date {
            t.date = d.chars().take(32).collect();
        }
        Ok(t)
    }

    pub fn get(&self, model: &str) -> Option<&Price> {
        self.models.get(&normalize_model(model))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_ids() {
        assert_eq!(
            normalize_model("claude-opus-5-5-20260101"),
            "claude-opus-5-5"
        );
        assert_eq!(
            normalize_model("Anthropic/claude-opus-5-5[1m]"),
            "claude-opus-5-5"
        );
        assert_eq!(normalize_model("gpt-x-2"), "gpt-x-2");
    }

    #[test]
    fn unknown_models_have_no_price() {
        let t = PriceTable::bundled();
        assert!(t.get("model-nobody-knows").is_none());
        assert!(t.get("claude-opus-5-5").is_some());
    }

    #[test]
    fn override_adds_models_and_date() {
        let dir = tempfile::tempdir().unwrap();
        let f = dir.path().join("prices.json");
        std::fs::write(&f, r#"{"date":"2026-12-01","models":{"model-x":{"input":1,"output":2,"cache_read":0.1,"cache_write_5m":1.25,"cache_write_1h":2}}}"#).unwrap();
        let t = PriceTable::load(&f).unwrap();
        assert_eq!(t.date, "2026-12-01");
        let c = t.get("model-x").unwrap().cost(&Tokens {
            input_uncached: 1_000_000,
            output: 1_000_000,
            ..Default::default()
        });
        assert!((c - 3.0).abs() < 1e-9);
        std::fs::write(&f, "{ not json").unwrap();
        assert!(PriceTable::load(&f).is_err());
    }
}
