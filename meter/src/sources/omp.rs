//! omp sessions: `~/.omp/agent/sessions/<project>/<session>.jsonl`.
//!
//! - The first line (`type: "session"`) carries the session id and cwd; they are kept in the file
//!   context so a resumed read still knows them.
//! - `type: "message"` lines whose `message.role` is `assistant`, and `type: "model_usage"` lines
//!   (side calls such as titles or compaction), carry `usage` = {input, output, cacheRead,
//!   cacheWrite, totalTokens, reasoningTokens?, cost{total}}. `input` is already uncached.
//! - omp writes no TTL for cache writes; they are counted as 5-minute writes. Its `cost.total` is
//!   kept as `reported_usd` and never replaced by a derived price.
//! - omp records no git branch, so `branch` is null.

use memchr::memmem;
use serde::Deserialize;

use super::{FileCtx, Line, clip, from_line};
use crate::event::{Precision, Tokens, UsageEvent};
use crate::time::parse_rfc3339;

#[derive(Deserialize)]
struct Row {
    #[serde(rename = "type")]
    ty: Option<String>,
    id: Option<String>,
    timestamp: Option<String>,
    cwd: Option<String>,
    model: Option<String>,
    usage: Option<Usage>,
    message: Option<Message>,
}

#[derive(Deserialize)]
struct Message {
    role: Option<String>,
    model: Option<String>,
    usage: Option<Usage>,
}

#[derive(Deserialize)]
struct Cost {
    total: Option<f64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Usage {
    #[serde(default, deserialize_with = "super::lenient_i64")]
    input: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    output: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    cache_read: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    cache_write: i64,
    #[serde(default, deserialize_with = "super::lenient_i64")]
    reasoning_tokens: i64,
    cost: Option<Cost>,
}

pub fn parse_line(line: &[u8], ctx: &mut FileCtx, out: &mut Vec<UsageEvent>) -> Line {
    let is_session = memmem::find(line, b"\"type\":\"session\"").is_some();
    if !is_session && memmem::find(line, b"\"usage\"").is_none() {
        return Line::Ignored;
    }
    let Some(row) = from_line::<Row>(line) else {
        let usage_type = memmem::find(line, b"\"role\":\"assistant\"").is_some()
            || memmem::find(line, b"\"type\":\"model_usage\"").is_some();
        return if usage_type && !is_session {
            Line::Bad
        } else {
            Line::Ignored
        };
    };
    let (model, usage) = match row.ty.as_deref() {
        Some("session") => {
            if let Some(id) = row.id.as_deref() {
                ctx.session = Some(clip(id, 128));
            }
            if let Some(cwd) = row.cwd.as_deref() {
                ctx.cwd = Some(clip(cwd, 1024));
            }
            return Line::Ignored;
        }
        Some("message") => match row.message {
            Some(Message {
                role: Some(r),
                model,
                usage: Some(u),
            }) if r == "assistant" => (model, u),
            _ => return Line::Ignored,
        },
        Some("model_usage") => match row.usage {
            Some(u) => (row.model, u),
            None => return Line::Ignored,
        },
        _ => return Line::Ignored,
    };
    let Some(ts_ms) = row.timestamp.as_deref().and_then(parse_rfc3339) else {
        return Line::Bad;
    };
    let Some(entry) = row.id.as_deref() else {
        return Line::Bad;
    };
    let tokens = Tokens {
        input_uncached: usage.input.max(0),
        cache_read: usage.cache_read.max(0),
        cache_write_5m: usage.cache_write.max(0),
        cache_write_1h: 0,
        output: usage.output.max(0),
        reasoning: usage.reasoning_tokens.clamp(0, usage.output.max(0)),
    };
    let reported_usd = usage
        .cost
        .and_then(|c| c.total)
        .filter(|v| v.is_finite() && *v >= 0.0);
    if tokens.is_zero() && reported_usd.unwrap_or(0.0) == 0.0 {
        return Line::Usage;
    }
    let session = ctx.session.clone();
    out.push(UsageEvent {
        dedupe_key: format!(
            "omp|{}|{}",
            session.as_deref().unwrap_or("?"),
            clip(entry, 128)
        ),
        ts_ms,
        harness: "omp",
        model: clip(model.as_deref().unwrap_or("unknown"), 128),
        session,
        parent_session: ctx.parent_session.clone(),
        cwd: ctx.cwd.clone(),
        branch: None,
        tokens,
        precision: Precision::Exact,
        reported_usd,
    });
    Line::Usage
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_reported_usd_and_session_context() {
        let mut ctx = FileCtx::default();
        let mut out = Vec::new();
        let session = r#"{"type":"session","version":3,"id":"sess-1","timestamp":"2026-01-01T09:00:00Z","cwd":"/work/app"}"#;
        let msg = r#"{"type":"message","id":"e1","parentId":"e0","timestamp":"2026-01-01T09:01:00Z","message":{"role":"assistant","model":"model-b","content":[{"type":"text","text":"SECRET"}],"usage":{"input":10,"output":20,"cacheRead":300,"cacheWrite":40,"totalTokens":370,"reasoningTokens":5,"cost":{"input":0.1,"output":0.2,"cacheRead":0.01,"cacheWrite":0.02,"total":0.33}}}}"#;
        let side = r#"{"type":"model_usage","id":"e2","timestamp":"2026-01-01T09:02:00Z","purpose":"title","model":"model-c","usage":{"input":7,"output":3,"cacheRead":0,"cacheWrite":0,"totalTokens":10,"cost":{"total":0.001}}}"#;
        let user = r#"{"type":"message","id":"e3","timestamp":"2026-01-01T09:03:00Z","message":{"role":"user","content":"usage"}}"#;
        assert_eq!(
            parse_line(session.as_bytes(), &mut ctx, &mut out),
            Line::Ignored
        );
        assert_eq!(parse_line(msg.as_bytes(), &mut ctx, &mut out), Line::Usage);
        assert_eq!(parse_line(side.as_bytes(), &mut ctx, &mut out), Line::Usage);
        assert_eq!(
            parse_line(user.as_bytes(), &mut ctx, &mut out),
            Line::Ignored
        );
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].reported_usd, Some(0.33));
        assert_eq!(out[0].session.as_deref(), Some("sess-1"));
        assert_eq!(out[0].cwd.as_deref(), Some("/work/app"));
        assert_eq!(out[0].dedupe_key, "omp|sess-1|e1");
        assert_eq!(out[0].tokens.total(), 370);
        assert_eq!(out[0].tokens.reasoning, 5);
        assert_eq!(out[1].model, "model-c");
        assert_eq!(out[1].reported_usd, Some(0.001));
        assert!(!format!("{out:?}").contains("SECRET"));
    }
}
