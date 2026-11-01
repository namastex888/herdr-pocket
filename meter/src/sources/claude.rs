//! Claude Code transcripts: `~/.claude/projects/<project>/<session>.jsonl` and sub-agent transcripts
//! under `<session>/subagents/*.jsonl`.
//!
//! - Only `type: "assistant"` lines carry usage. `api-request`, `api-request-shape` and the rest
//!   are skipped before JSON parsing.
//! - A message is written once per content block, so the same `message.id` + `requestId` repeats;
//!   the store keeps the first and drops the rest.
//! - Top-level `usage` covers the executor model only. Advisor calls sit in
//!   `usage.iterations[]` with `type: "advisor_message"` and their own `model`, and are emitted as
//!   separate events under that model.

use memchr::memmem;
use serde::Deserialize;

use super::{FileCtx, Line, clip, from_line};
use crate::event::{Precision, Tokens, UsageEvent};
use crate::time::parse_rfc3339;

#[derive(Deserialize)]
struct Row {
    #[serde(rename = "type")]
    ty: Option<String>,
    message: Option<Message>,
    #[serde(rename = "requestId")]
    request_id: Option<String>,
    timestamp: Option<String>,
    #[serde(rename = "sessionId")]
    session_id: Option<String>,
    cwd: Option<String>,
    #[serde(rename = "gitBranch")]
    git_branch: Option<String>,
}

#[derive(Deserialize)]
struct Message {
    id: Option<String>,
    model: Option<String>,
    usage: Option<Usage>,
}

#[derive(Deserialize, Default)]
struct CacheCreation {
    #[serde(default, deserialize_with = "super::lenient_i64")]
    ephemeral_5m_input_tokens: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    ephemeral_1h_input_tokens: i64,
}

#[derive(Deserialize, Default)]
struct OutputDetails {
    #[serde(default, deserialize_with = "super::lenient_i64")]
    thinking_tokens: i64,
}

#[derive(Deserialize, Default)]
struct Usage {
    #[serde(default, deserialize_with = "super::lenient_i64")]
    input_tokens: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    output_tokens: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    cache_read_input_tokens: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    cache_creation_input_tokens: i64,
    cache_creation: Option<CacheCreation>,
    output_tokens_details: Option<OutputDetails>,
    #[serde(default)]
    iterations: Option<Vec<Iteration>>,
}

#[derive(Deserialize)]
struct Iteration {
    #[serde(rename = "type")]
    ty: Option<String>,
    model: Option<String>,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    input_tokens: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    output_tokens: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    cache_read_input_tokens: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    cache_creation_input_tokens: i64,
    cache_creation: Option<CacheCreation>,
}

/// Splits a cache-write total into its TTLs. Any part the log does not split is counted as a
/// 5-minute write, the API's default TTL.
fn cache_writes(total: i64, split: Option<&CacheCreation>) -> (i64, i64) {
    match split {
        Some(c) => {
            let w5 = c.ephemeral_5m_input_tokens.max(0);
            let w1h = c.ephemeral_1h_input_tokens.max(0);
            let rest = (total - w5 - w1h).max(0);
            (w5 + rest, w1h)
        }
        None => (total.max(0), 0),
    }
}

/// The TTL split of the executor's own calls. When a message runs several executor iterations
/// (around an advisor call), the top-level `cache_creation` covers only the first one while
/// `cache_creation_input_tokens` covers them all, so the split is summed from the iterations.
fn executor_split(u: &Usage) -> Option<CacheCreation> {
    let its: Vec<&Iteration> = u
        .iterations
        .iter()
        .flatten()
        .filter(|i| i.ty.as_deref() != Some("advisor_message"))
        .collect();
    if its.len() < 2 || its.iter().any(|i| i.cache_creation.is_none()) {
        return None;
    }
    let mut s = CacheCreation::default();
    for i in its {
        let c = i.cache_creation.as_ref()?;
        s.ephemeral_5m_input_tokens += c.ephemeral_5m_input_tokens.max(0);
        s.ephemeral_1h_input_tokens += c.ephemeral_1h_input_tokens.max(0);
    }
    Some(s)
}

fn tokens(
    input: i64,
    output: i64,
    read: i64,
    write_total: i64,
    split: Option<&CacheCreation>,
    reasoning: i64,
) -> Tokens {
    let (w5, w1h) = cache_writes(write_total, split);
    Tokens {
        input_uncached: input.max(0),
        cache_read: read.max(0),
        cache_write_5m: w5,
        cache_write_1h: w1h,
        output: output.max(0),
        reasoning: reasoning.clamp(0, output.max(0)),
        unsplit: 0,
    }
}

pub fn parse_line(line: &[u8], ctx: &mut FileCtx, out: &mut Vec<UsageEvent>) -> Line {
    // Cheap prefilter: usage lines are assistant messages with a usage object.
    if memmem::find(line, b"\"usage\"").is_none() || memmem::find(line, b"\"assistant\"").is_none()
    {
        return Line::Ignored;
    }
    let Some(row) = from_line::<Row>(line) else {
        // Only an assistant line that cannot be read is a skipped usage line.
        return if memmem::find(line, b"\"type\":\"assistant\"").is_some() {
            Line::Bad
        } else {
            Line::Ignored
        };
    };
    if row.ty.as_deref() != Some("assistant") {
        return Line::Ignored;
    }
    let Some(msg) = row.message else {
        return Line::Ignored;
    };
    let Some(u) = msg.usage else {
        return Line::Ignored;
    };
    let Some(ts_ms) = row.timestamp.as_deref().and_then(parse_rfc3339) else {
        return Line::Bad;
    };
    let model = msg.model.as_deref().unwrap_or("unknown");
    if model == "<synthetic>" {
        // Locally generated messages (errors, interrupts): no API call happened.
        return Line::Ignored;
    }
    let message_id = msg.id.as_deref().unwrap_or("");
    let request_id = row.request_id.as_deref().unwrap_or("");
    if message_id.is_empty() && request_id.is_empty() {
        return Line::Bad;
    }
    if ctx.cwd.is_none() {
        ctx.cwd = row.cwd.as_deref().map(|c| clip(c, 1024));
    }
    // A sub-agent transcript repeats its parent's sessionId; its own id is the file name.
    let session = if ctx.parent_session.is_some() {
        ctx.session
            .clone()
            .or_else(|| row.session_id.as_deref().map(|s| clip(s, 128)))
    } else {
        row.session_id
            .as_deref()
            .map(|s| clip(s, 128))
            .or_else(|| ctx.session.clone())
    };
    let cwd = row
        .cwd
        .as_deref()
        .map(|c| clip(c, 1024))
        .or_else(|| ctx.cwd.clone());
    let branch = row
        .git_branch
        .as_deref()
        .filter(|b| !b.is_empty())
        .map(|b| clip(b, 256));
    let key = format!("claude|{}|{}", clip(message_id, 128), clip(request_id, 128));

    let exec = executor_split(&u).filter(|s| {
        s.ephemeral_5m_input_tokens + s.ephemeral_1h_input_tokens <= u.cache_creation_input_tokens
    });
    let split = exec.as_ref().or(u.cache_creation.as_ref());
    let base = UsageEvent {
        dedupe_key: key.clone(),
        ts_ms,
        harness: "claude-code",
        model: clip(model, 128),
        session,
        parent_session: ctx.parent_session.clone(),
        cwd,
        branch,
        repo: None,
        tokens: tokens(
            u.input_tokens,
            u.output_tokens,
            u.cache_read_input_tokens,
            u.cache_creation_input_tokens,
            split,
            u.output_tokens_details
                .as_ref()
                .map_or(0, |d| d.thinking_tokens),
        ),
        precision: Precision::Exact,
        reported_usd: None,
    };
    for (i, it) in u.iterations.iter().flatten().enumerate() {
        if it.ty.as_deref() != Some("advisor_message") {
            continue;
        }
        let t = tokens(
            it.input_tokens,
            it.output_tokens,
            it.cache_read_input_tokens,
            it.cache_creation_input_tokens,
            it.cache_creation.as_ref(),
            0,
        );
        if t.is_zero() {
            continue;
        }
        out.push(UsageEvent {
            dedupe_key: format!("{key}|advisor|{i}"),
            model: clip(it.model.as_deref().unwrap_or("unknown"), 128),
            tokens: t,
            ..base.clone()
        });
    }
    if !base.tokens.is_zero() {
        out.push(base);
    }
    Line::Usage
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(line: &str) -> (Line, Vec<UsageEvent>) {
        let mut out = Vec::new();
        let r = parse_line(line.as_bytes(), &mut FileCtx::default(), &mut out);
        (r, out)
    }

    const ASSISTANT: &str = r#"{"type":"assistant","requestId":"req_1","timestamp":"2026-01-01T10:15:00.000Z","sessionId":"s1","cwd":"/work/app","gitBranch":"feat/x","message":{"id":"msg_1","model":"model-a","role":"assistant","content":[{"type":"text","text":"SECRET-TEXT"}],"usage":{"input_tokens":3,"output_tokens":40,"cache_read_input_tokens":1000,"cache_creation_input_tokens":300,"cache_creation":{"ephemeral_5m_input_tokens":100,"ephemeral_1h_input_tokens":200},"output_tokens_details":{"thinking_tokens":10}}}}"#;

    #[test]
    fn parses_usage_and_keeps_ttls_apart() {
        let (r, ev) = parse(ASSISTANT);
        assert_eq!(r, Line::Usage);
        assert_eq!(ev.len(), 1);
        let e = &ev[0];
        assert_eq!(e.dedupe_key, "claude|msg_1|req_1");
        assert_eq!(e.model, "model-a");
        assert_eq!(e.branch.as_deref(), Some("feat/x"));
        assert_eq!(
            e.tokens,
            Tokens {
                input_uncached: 3,
                cache_read: 1000,
                cache_write_5m: 100,
                cache_write_1h: 200,
                output: 40,
                reasoning: 10,
                unsplit: 0,
            }
        );
        assert!(!format!("{e:?}").contains("SECRET-TEXT"));
    }

    #[test]
    fn advisor_iterations_are_separate_events_under_their_model() {
        let line = r#"{"type":"assistant","requestId":"req_2","timestamp":"2026-01-01T10:15:00Z","message":{"id":"msg_2","model":"model-exec","usage":{"input_tokens":4,"output_tokens":285,"cache_read_input_tokens":2000,"cache_creation_input_tokens":30,"iterations":[{"type":"message","input_tokens":4,"output_tokens":285,"cache_read_input_tokens":2000,"cache_creation_input_tokens":30},{"type":"advisor_message","model":"model-advisor","input_tokens":10000,"output_tokens":1200,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}]}}}"#;
        let (_, ev) = parse(line);
        assert_eq!(ev.len(), 2);
        let adv = ev.iter().find(|e| e.model == "model-advisor").unwrap();
        assert_eq!(adv.tokens.input_uncached, 10000);
        assert_eq!(adv.tokens.output, 1200);
        assert_eq!(adv.dedupe_key, "claude|msg_2|req_2|advisor|1");
        let exec = ev.iter().find(|e| e.model == "model-exec").unwrap();
        // Top-level usage already excludes the advisor: nothing is subtracted.
        assert_eq!(exec.tokens.input_uncached, 4);
        assert_eq!(exec.tokens.cache_write_5m, 30);
    }

    #[test]
    fn ttl_split_is_summed_over_executor_iterations() {
        // Top-level cache_creation covers only the first executor iteration; the total covers both.
        let line = r#"{"type":"assistant","requestId":"r","timestamp":"2026-01-01T10:00:00Z","message":{"id":"m","model":"model-exec","usage":{"input_tokens":2,"output_tokens":9,"cache_read_input_tokens":100,"cache_creation_input_tokens":2276,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":1271},"iterations":[{"type":"message","input_tokens":1,"output_tokens":4,"cache_creation_input_tokens":1271,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":1271}},{"type":"advisor_message","model":"model-adv","input_tokens":50,"output_tokens":5,"cache_creation_input_tokens":0,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":0}},{"type":"message","input_tokens":1,"output_tokens":5,"cache_creation_input_tokens":1005,"cache_creation":{"ephemeral_5m_input_tokens":1005,"ephemeral_1h_input_tokens":0}}]}}}"#;
        let (_, ev) = parse(line);
        let exec = ev.iter().find(|e| e.model == "model-exec").unwrap();
        assert_eq!(exec.tokens.cache_write_1h, 1271);
        assert_eq!(exec.tokens.cache_write_5m, 1005);
    }

    #[test]
    fn tolerates_nulls_and_lone_surrogates() {
        let line = r#"{"type":"assistant","requestId":"r","timestamp":"2026-01-01T10:00:00Z","message":{"id":"m","model":"model-a","content":[{"type":"text","text":"broken \ud83d emoji \udc00 and fine 😀 and \\ud83d literal"}],"usage":{"input_tokens":7,"output_tokens":null,"cache_read_input_tokens":3.0,"iterations":null}}}"#;
        let (r, ev) = parse(line);
        assert_eq!(r, Line::Usage);
        assert_eq!(ev[0].tokens.input_uncached, 7);
        assert_eq!(ev[0].tokens.cache_read, 3);
        assert_eq!(ev[0].tokens.output, 0);
        // A non-assistant line that cannot be read is not a skipped usage line.
        assert_eq!(
            parse(r#"{"type":"api-request-blob","message":{"role":"assistant","usage":"#).0,
            Line::Ignored
        );
    }

    #[test]
    fn non_usage_lines_are_ignored() {
        for l in [
            r#"{"type":"api-request","id":"x","params":{"usage":"assistant"}}"#,
            r#"{"type":"api-request-shape","shape":{"usage":1,"assistant":2}}"#,
            r#"{"type":"user","message":{"role":"user","content":"no usage here"}}"#,
            r#"{"type":"assistant","message":{"id":"m","model":"<synthetic>","usage":{"input_tokens":0}},"requestId":"r","timestamp":"2026-01-01T00:00:00Z"}"#,
        ] {
            let (r, ev) = parse(l);
            assert_ne!(r, Line::Usage, "{l}");
            assert!(ev.is_empty());
        }
        assert_eq!(parse(r#"{"type":"assistant","usage": {"#).0, Line::Bad);
    }
}
