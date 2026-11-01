//! herdr-core over `hc connect`: JSON-RPC 2.0, one message per line on stdin/stdout. The meter
//! uses it for the core id (`server.status`), live terminal labels (`terminal.list`) and the
//! summary doc (`doc.get` / `doc.set`). It is local only; a missing `hc` is not an error.

use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::{Receiver, RecvTimeoutError, channel};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

pub struct Hc {
    child: Child,
    stdin: Option<ChildStdin>,
    lines: Receiver<String>,
    next_id: u64,
    timeout: Duration,
}

#[derive(Debug, Clone, PartialEq)]
pub enum RpcError {
    /// The core answered with an error; `kind` is its `data.kind` (`conflict`, `unavailable`, …).
    Remote { kind: String, message: String },
    /// No answer in time, or the connection closed.
    Transport(String),
}

impl std::fmt::Display for RpcError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RpcError::Remote { kind, message } => write!(f, "herdr-core said {kind}: {message}"),
            RpcError::Transport(m) => write!(f, "herdr-core connection: {m}"),
        }
    }
}

/// The `hc` binary: `METER_HC` if set (for tests), else `hc` on PATH.
fn hc_program() -> String {
    std::env::var("METER_HC")
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "hc".into())
}

impl Hc {
    /// Starts `hc connect`. None when `hc` is not installed or cannot start.
    pub fn connect(timeout: Duration) -> Option<Hc> {
        let mut child = Command::new(hc_program())
            .arg("connect")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .ok()?;
        let stdout = child.stdout.take()?;
        let stdin = child.stdin.take();
        let (tx, rx) = channel();
        std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines() {
                let Ok(line) = line else { break };
                if tx.send(line).is_err() {
                    break;
                }
            }
        });
        Some(Hc {
            child,
            stdin,
            lines: rx,
            next_id: 0,
            timeout,
        })
    }

    pub fn call(&mut self, method: &str, params: Value) -> Result<Value, RpcError> {
        self.next_id += 1;
        let id = self.next_id;
        let msg = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        let stdin = self
            .stdin
            .as_mut()
            .ok_or_else(|| RpcError::Transport("closed".into()))?;
        writeln!(stdin, "{msg}")
            .and_then(|_| stdin.flush())
            .map_err(|e| RpcError::Transport(e.to_string()))?;
        let deadline = Instant::now() + self.timeout;
        loop {
            let left = deadline.saturating_duration_since(Instant::now());
            let line = match self.lines.recv_timeout(left) {
                Ok(l) => l,
                Err(RecvTimeoutError::Timeout) => {
                    return Err(RpcError::Transport(format!("{method} timed out")));
                }
                Err(RecvTimeoutError::Disconnected) => {
                    return Err(RpcError::Transport("closed".into()));
                }
            };
            let Ok(v) = serde_json::from_str::<Value>(&line) else {
                continue;
            };
            if v.get("id").and_then(Value::as_u64) != Some(id) {
                continue; // a notification or an older reply
            }
            if let Some(err) = v.get("error") {
                let kind = err
                    .pointer("/data/kind")
                    .and_then(Value::as_str)
                    .or_else(|| err.get("message").and_then(Value::as_str))
                    .unwrap_or("error");
                let message = err.get("message").and_then(Value::as_str).unwrap_or("");
                return Err(RpcError::Remote {
                    kind: kind.chars().take(64).collect(),
                    message: message.chars().take(200).collect(),
                });
            }
            return Ok(v.get("result").cloned().unwrap_or(Value::Null));
        }
    }

    /// herdr-core's permanent core id.
    pub fn core_id(&mut self) -> Option<String> {
        let r = self.call("server.status", json!({})).ok()?;
        let id = r.pointer("/core/id")?.as_str()?;
        (!id.is_empty() && id.len() <= 128).then(|| id.to_string())
    }

    /// Live terminals that run a known harness, with their automagik-herdr labels.
    pub fn terminals(&mut self) -> Vec<LiveTerminal> {
        let Ok(r) = self.call("terminal.list", json!({})) else {
            return Vec::new();
        };
        let Some(list) = r.get("terminals").and_then(Value::as_array) else {
            return Vec::new();
        };
        list.iter().filter_map(LiveTerminal::from_json).collect()
    }

    pub fn doc_get(&mut self, name: &str) -> Result<(Value, u64), RpcError> {
        let r = self.call("doc.get", json!({"name": name}))?;
        let version = r.get("version").and_then(Value::as_u64).unwrap_or(0);
        Ok((r.get("value").cloned().unwrap_or(Value::Null), version))
    }

    pub fn doc_set(&mut self, name: &str, value: &Value, version: u64) -> Result<u64, RpcError> {
        let r = self.call(
            "doc.set",
            json!({"name": name, "value": value, "version": version}),
        )?;
        Ok(r.get("version").and_then(Value::as_u64).unwrap_or(0))
    }

    /// Compare-and-set: read the version, write against it, and retry when another writer won.
    pub fn doc_put(&mut self, name: &str, value: &Value) -> Result<u64, RpcError> {
        let mut last = RpcError::Transport("no attempt".into());
        for _ in 0..5 {
            let (_, version) = self.doc_get(name)?;
            match self.doc_set(name, value, version) {
                Ok(v) => return Ok(v),
                Err(RpcError::Remote { kind, message }) if kind == "conflict" => {
                    last = RpcError::Remote { kind, message };
                }
                Err(e) => return Err(e),
            }
        }
        Err(last)
    }
}

impl Drop for Hc {
    fn drop(&mut self) {
        drop(self.stdin.take());
        let deadline = Instant::now() + Duration::from_millis(500);
        while Instant::now() < deadline {
            if let Ok(Some(_)) = self.child.try_wait() {
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// A live hc terminal, reduced to what attribution needs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LiveTerminal {
    pub cwd: String,
    pub harness: &'static str,
    pub created_ms: i64,
    pub bot: Option<String>,
    pub role: Option<String>,
    pub squad: Option<String>,
}

/// `claude` → claude-code, `omp` → omp, from the program's basename.
pub fn harness_of(program: &str) -> Option<&'static str> {
    let base = program.rsplit('/').next().unwrap_or(program);
    match base {
        "claude" | "claude-code" => Some("claude-code"),
        "omp" => Some("omp"),
        "codex" => Some("codex"),
        _ => None,
    }
}

impl LiveTerminal {
    pub fn from_json(t: &Value) -> Option<LiveTerminal> {
        if t.get("finished_ms").is_some_and(|v| !v.is_null()) {
            return None;
        }
        let fg = t.get("foreground").filter(|v| !v.is_null());
        let program = |v: Option<&Value>| -> Option<&'static str> {
            let v = v?;
            // `claude …`, or an interpreter running it (`bun /…/omp`, `node /…/claude`).
            let argv = v.get("argv").and_then(Value::as_array);
            argv.into_iter()
                .flatten()
                .take(2)
                .filter_map(Value::as_str)
                .find_map(harness_of)
                .or_else(|| v.get("name").and_then(Value::as_str).and_then(harness_of))
        };
        let harness = program(fg).or_else(|| program(Some(t)))?;
        let cwd = fg
            .and_then(|f| f.get("cwd"))
            .and_then(Value::as_str)
            .or_else(|| t.get("cwd").and_then(Value::as_str))?;
        let label = |k: &str| {
            t.pointer(&format!("/labels/{k}"))
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
                .map(|s| s.chars().take(64).collect::<String>())
        };
        Some(LiveTerminal {
            cwd: cwd.to_string(),
            harness,
            created_ms: t.get("created_ms").and_then(Value::as_i64).unwrap_or(0),
            bot: label("bot"),
            role: label("role"),
            squad: label("squad"),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_labels_from_live_harness_terminals() {
        let t = json!({"address": "t1", "cwd": "/w", "created_ms": 5, "argv": ["zsh"],
            "foreground": {"pid": 1, "name": "node", "argv": ["/usr/local/bin/claude", "--x"], "cwd": "/w/app"},
            "labels": {"bot": "b-test000001", "role": "dev", "squad": "core", "where": "x"}});
        let l = LiveTerminal::from_json(&t).unwrap();
        assert_eq!(l.harness, "claude-code");
        assert_eq!(l.cwd, "/w/app");
        assert_eq!(l.bot.as_deref(), Some("b-test000001"));
        assert_eq!(l.role.as_deref(), Some("dev"));
        let shell = json!({"cwd": "/w", "argv": ["zsh"],
            "foreground": {"pid": 1, "name": "zsh", "argv": ["zsh"], "cwd": "/w"}});
        assert!(LiveTerminal::from_json(&shell).is_none());
        let done = json!({"cwd": "/w", "argv": ["omp"], "finished_ms": 9});
        assert!(LiveTerminal::from_json(&done).is_none());
    }
}
