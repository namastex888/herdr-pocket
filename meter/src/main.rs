use std::process::ExitCode;
use std::time::Duration;

use meter::store::Store;
use meter::sync::{self, Options, Outcome};
use meter::time::{format_rfc3339, now_ms};
use meter::{paths, summary};

const USAGE: &str = "\
Usage:
  meter sync [--force] [--backfill] [--days N] [--budget-secs N] [--no-publish] [--doc NAME]
  meter status
  meter --version

sync      Read new harness usage (omp, Claude Code, Codex) into the local store and publish
          the summary doc to herdr-core. Does nothing within 10 minutes of a complete sync.
  --force         Run even if the last sync is recent.
  --backfill      Re-import the full history (slow; resumes across runs).
  --days N        Import window in days (default 30). A wider window re-imports.
  --budget-secs N Stop after N seconds and resume next run (default 20).
  --no-publish    Do not write the summary doc.
  --doc NAME      Doc name to publish to (default herdr-meter).
status    Print the store's state as JSON.

The state lives in METER_STATE_DIR if set, else the platform state directory.";

fn fail(msg: &str) -> ExitCode {
    eprintln!("meter: {msg}");
    ExitCode::from(2)
}

fn human_bytes(b: u64) -> String {
    const U: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut v = b as f64;
    let mut i = 0;
    while v >= 1000.0 && i < U.len() - 1 {
        v /= 1000.0;
        i += 1;
    }
    if i == 0 {
        format!("{b} B")
    } else {
        format!("{v:.1} {}", U[i])
    }
}

fn state_dir() -> Result<std::path::PathBuf, String> {
    paths::default_state_dir()
        .ok_or_else(|| "HOME is not set; set METER_STATE_DIR to choose a state directory".into())
}

fn parse_num(flag: &str, v: Option<String>, min: u64, max: u64) -> Result<u64, String> {
    let v = v.ok_or_else(|| format!("{flag} needs a number"))?;
    let n: u64 = v
        .parse()
        .map_err(|_| format!("{flag} needs a whole number, got {v:?}"))?;
    if n < min || n > max {
        return Err(format!("{flag} must be between {min} and {max}"));
    }
    Ok(n)
}

fn cmd_sync(args: Vec<String>) -> Result<ExitCode, String> {
    let home = paths::home().ok_or("HOME is not set")?;
    let mut opts = Options::new(home, state_dir()?, now_ms());
    let mut it = args.into_iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--force" => opts.force = true,
            "--backfill" => opts.backfill = true,
            "--no-publish" => opts.publish = false,
            "--days" => opts.days = Some(parse_num("--days", it.next(), 1, 36_500)? as i64),
            "--budget-secs" => {
                opts.budget = Duration::from_secs(parse_num("--budget-secs", it.next(), 1, 3600)?)
            }
            "--doc" => {
                let name = it.next().ok_or("--doc needs a name")?;
                if name.is_empty()
                    || name.len() > 64
                    || !name
                        .chars()
                        .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
                {
                    return Err("--doc takes a name of letters, digits, - and _".into());
                }
                opts.doc_name = name;
            }
            "-h" | "--help" => {
                println!("{USAGE}");
                return Ok(ExitCode::SUCCESS);
            }
            other => {
                return Err(format!(
                    "unknown option {other:?} for sync; see meter --help"
                ));
            }
        }
    }
    match sync::run(&opts).map_err(|e| e.0)? {
        Outcome::Locked => println!("meter: another sync is running; nothing to do"),
        Outcome::Fresh { age_ms } => println!(
            "meter: last sync was {}s ago; nothing to do (use --force)",
            age_ms / 1000
        ),
        Outcome::Ran(r) => {
            let h = &r.health;
            println!(
                "meter: read {} from {} of {} files in {:.1}s; {} new usage events, {} duplicates dropped, {} skipped lines",
                human_bytes(r.bytes_read),
                r.files_read,
                r.files_seen,
                r.elapsed.as_secs_f64(),
                h.events,
                h.duplicates,
                h.skipped_lines
            );
            if r.reimported {
                println!(
                    "meter: window widened; re-imported from {}",
                    format_rfc3339(r.counted_from_ms)
                );
            }
            if r.pending_bytes > 0 {
                println!(
                    "meter: stopped at the time budget; {} in {} files left for the next run",
                    human_bytes(r.pending_bytes),
                    r.pending_files
                );
            }
            if h.rewrites > 0 {
                println!(
                    "meter: {} files were rewritten or truncated and read again",
                    h.rewrites
                );
            }
            if r.unreadable > 0 {
                println!("meter: {} files could not be read", r.unreadable);
            }
            match &r.published {
                None => println!(
                    "meter: summary ({}) not published (--no-publish)",
                    human_bytes(r.summary_bytes as u64)
                ),
                Some(Ok(v)) => println!(
                    "meter: published {} ({}) version {v}",
                    opts.doc_name,
                    human_bytes(r.summary_bytes as u64)
                ),
                Some(Err(e)) => println!("meter: summary not published: {e}"),
            }
        }
    }
    Ok(ExitCode::SUCCESS)
}

fn cmd_status() -> Result<ExitCode, String> {
    let dir = state_dir()?;
    let db = dir.join("meter.db");
    if !db.exists() {
        println!("{}", serde_json::json!({"state_dir": dir, "synced": false}));
        return Ok(ExitCode::SUCCESS);
    }
    let store = Store::open(&db).map_err(|e| format!("cannot open {}: {e}", db.display()))?;
    let get = |k: &str| store.meta_i64(k).ok().flatten();
    let sources = sync::source_health(&store).map_err(|e| e.to_string())?;
    let v = serde_json::json!({
        "state_dir": dir,
        "version": env!("CARGO_PKG_VERSION"),
        "machine": store.meta("machine").ok().flatten().or_else(|| store.meta("machine_fallback").ok().flatten()),
        "counted_from": get("counted_from_ms").map(format_rfc3339),
        "last_success": get("last_success_ms").map(format_rfc3339),
        "last_run": get("last_run_ms").map(format_rfc3339),
        "pending_bytes": get("last_pending_bytes").unwrap_or(0),
        "pending_files": get("last_pending_files").unwrap_or(0),
        "rows": store.count("usage").unwrap_or(0),
        "files": store.count("files").unwrap_or(0),
        "dedupe_keys": store.count("seen").unwrap_or(0),
        "sessions": store.count("sessions").unwrap_or(0),
        "sources": sources.iter().map(|s| serde_json::json!({
            "harness": s.harness, "status": s.status, "reason": s.reason,
            "last_success": s.last_success_ms.map(format_rfc3339),
            "usage_lines": s.usage_lines, "events": s.events, "duplicates": s.duplicates,
            "skipped_lines": s.skipped_lines, "rewrites": s.rewrites,
            "threads": s.threads.map(|(e, t)| serde_json::json!({"exact": e, "total_only": t})),
        })).collect::<Vec<_>>(),
        "summary_schema_version": summary::SUMMARY_SCHEMA_VERSION,
    });
    println!("{}", serde_json::to_string_pretty(&v).unwrap_or_default());
    Ok(ExitCode::SUCCESS)
}

fn main() -> ExitCode {
    let mut args = std::env::args().skip(1);
    let cmd = args.next();
    let rest: Vec<String> = args.collect();
    let r = match cmd.as_deref() {
        Some("sync") => cmd_sync(rest),
        Some("status") if rest.is_empty() => cmd_status(),
        Some("status") => Err("status takes no options".into()),
        Some("--version" | "-V" | "version") => {
            println!("meter {}", env!("CARGO_PKG_VERSION"));
            Ok(ExitCode::SUCCESS)
        }
        Some("-h" | "--help" | "help") | None => {
            println!("{USAGE}");
            Ok(ExitCode::SUCCESS)
        }
        Some(other) => Err(format!("unknown command {other:?}; see meter --help")),
    };
    r.unwrap_or_else(|e| fail(&e))
}
