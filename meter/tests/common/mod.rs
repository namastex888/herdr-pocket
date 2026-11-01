//! Synthetic fixtures only: every id, path, model and number here is made up.
#![allow(dead_code)]

use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use meter::event::Tokens;
use meter::store::Store;
use meter::sync::{self, Options, Outcome, Report};

pub const NOW: &str = "2026-01-15T12:00:00Z";

pub fn now() -> i64 {
    meter::time::parse_rfc3339(NOW).unwrap()
}

pub struct Env {
    pub dir: tempfile::TempDir,
}

impl Env {
    pub fn new() -> Env {
        Env {
            dir: tempfile::tempdir().unwrap(),
        }
    }

    pub fn home(&self) -> PathBuf {
        self.dir.path().join("home")
    }

    pub fn state(&self) -> PathBuf {
        self.dir.path().join("state")
    }

    pub fn claude(&self, rel: &str) -> PathBuf {
        let p = self.home().join(".claude/projects").join(rel);
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        p
    }

    pub fn omp(&self, rel: &str) -> PathBuf {
        let p = self.home().join(".omp/agent/sessions").join(rel);
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        p
    }

    pub fn opts(&self) -> Options {
        let mut o = Options::new(self.home(), self.state(), now());
        o.claude_root = self.home().join(".claude/projects");
        o.codex_home = self.home().join(".codex");
        o.use_hc = false;
        o.resolve_repos = false;
        o.publish = false;
        o.force = true;
        o
    }

    pub fn run(&self, o: &Options) -> Report {
        match sync::run(o).unwrap() {
            Outcome::Ran(r) => *r,
            other => panic!("expected a run, got {other:?}"),
        }
    }

    pub fn store(&self) -> Store {
        Store::open(&self.state().join("meter.db")).unwrap()
    }

    /// Tokens per model across every row.
    pub fn totals(&self) -> BTreeMap<String, Tokens> {
        let mut m: BTreeMap<String, Tokens> = BTreeMap::new();
        for r in self.store().rows_since(i64::MIN).unwrap() {
            m.entry(r.key.model.clone()).or_default().add(&r.tokens);
        }
        m
    }
}

pub struct U {
    pub input: i64,
    pub output: i64,
    pub read: i64,
    pub w5: i64,
    pub w1h: i64,
}

pub const U1: U = U {
    input: 3,
    output: 40,
    read: 1000,
    w5: 100,
    w1h: 200,
};
pub const U2: U = U {
    input: 5,
    output: 70,
    read: 2000,
    w5: 0,
    w1h: 50,
};

impl U {
    pub fn tokens(&self) -> Tokens {
        Tokens {
            input_uncached: self.input,
            cache_read: self.read,
            cache_write_5m: self.w5,
            cache_write_1h: self.w1h,
            output: self.output,
            reasoning: 0,
            unsplit: 0,
        }
    }
}

/// One Claude Code assistant line. `pad` adds content text so tests control line sizes.
pub fn claude_line(msg: &str, req: &str, ts: &str, model: &str, u: &U, pad: usize) -> String {
    format!(
        concat!(
            r#"{{"parentUuid":"p","isSidechain":false,"type":"assistant","requestId":"{req}","timestamp":"{ts}","#,
            r#""sessionId":"sess-1","cwd":"/work/app","gitBranch":"feat/x","version":"1.0.0","#,
            r#""message":{{"id":"{msg}","model":"{model}","role":"assistant","#,
            r#""content":[{{"type":"text","text":"FAKE-PROMPT-TEXT {pad}"}}],"#,
            r#""usage":{{"input_tokens":{i},"output_tokens":{o},"cache_read_input_tokens":{r},"#,
            r#""cache_creation_input_tokens":{w},"cache_creation":{{"ephemeral_5m_input_tokens":{w5},"#,
            r#""ephemeral_1h_input_tokens":{w1h}}}}}}}}}"#
        ),
        req = req,
        ts = ts,
        msg = msg,
        model = model,
        pad = "x".repeat(pad),
        i = u.input,
        o = u.output,
        r = u.read,
        w = u.w5 + u.w1h,
        w5 = u.w5,
        w1h = u.w1h,
    )
}

pub fn user_line(text: &str) -> String {
    format!(
        r#"{{"type":"user","timestamp":"2026-01-15T10:00:00Z","sessionId":"sess-1","cwd":"/work/app","message":{{"role":"user","content":"{text}"}}}}"#
    )
}

pub fn write_lines(p: &Path, lines: &[String]) {
    let mut s = lines.join("\n");
    s.push('\n');
    fs::write(p, s).unwrap();
}

pub fn append(p: &Path, s: &str) {
    let mut f = fs::OpenOptions::new().append(true).open(p).unwrap();
    f.write_all(s.as_bytes()).unwrap();
}

pub fn sum(us: &[&U]) -> Tokens {
    let mut t = Tokens::default();
    for u in us {
        t.add(&u.tokens());
    }
    t
}

pub fn big_fixture(env: &Env, files: usize, lines: usize, ts: &str) -> Tokens {
    let mut want = Tokens::default();
    for f in 0..files {
        let p = env.claude(&format!("-work-app/sess-{f}.jsonl"));
        let mut v = Vec::new();
        for i in 0..lines {
            let u = if i % 3 == 0 { &U2 } else { &U1 };
            let line = claude_line(
                &format!("msg_{f}_{i}"),
                &format!("req_{f}_{i}"),
                ts,
                "model-a",
                u,
                i % 50,
            );
            v.push(line.clone());
            if i % 4 == 0 {
                v.push(line); // a repeat on the next line
            }
            want.add(&u.tokens());
        }
        write_lines(&p, &v);
    }
    want
}
