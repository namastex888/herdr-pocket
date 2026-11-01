//! `work.repo` = host/owner/repo from the git remote of a session's cwd.
//!
//! The remote URL can embed credentials (`https://user:token@host/…`), so userinfo, port, query
//! and fragment are dropped before anything is stored. Worktrees and subdirectories resolve to
//! their repository because git answers for the enclosing repository.

use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// Normalizes a git remote URL to `host/owner/repo`. Local paths and `file://` remotes give None:
/// they name a directory on this machine, not a project.
pub fn normalize_remote(url: &str) -> Option<String> {
    let url = url.trim();
    if url.is_empty() {
        return None;
    }
    let (host, path) = if let Some(pos) = url.find("://") {
        let scheme = url[..pos].to_ascii_lowercase();
        if scheme == "file" {
            return None;
        }
        let rest = &url[pos + 3..];
        let rest = rest.split(['?', '#']).next().unwrap_or("");
        let (authority, path) = rest.split_once('/').unwrap_or((rest, ""));
        let host = authority.rsplit_once('@').map_or(authority, |(_, h)| h);
        (strip_port(host), path)
    } else {
        // scp-like: [user@]host:owner/repo. A colon after a slash is a local path.
        if url.starts_with('/') || url.starts_with('.') {
            return None;
        }
        let head_end = url.find('/').unwrap_or(url.len());
        let rest = match url[..head_end].rfind('@') {
            Some(at) => &url[at + 1..],
            None => url,
        };
        let colon = rest.find(':')?;
        if rest[..colon].contains('/') {
            return None;
        }
        let path = rest[colon + 1..].split(['?', '#']).next().unwrap_or("");
        (&rest[..colon], path)
    };
    let host = host
        .trim_start_matches('[')
        .trim_end_matches(']')
        .to_ascii_lowercase();
    if host.is_empty() || host.contains(char::is_whitespace) {
        return None;
    }
    let mut path = path.trim_matches('/');
    if let Some(p) = path.strip_suffix(".git") {
        path = p;
    }
    let segments: Vec<&str> = path
        .split('/')
        .filter(|s| !s.is_empty() && *s != "." && *s != "..")
        .collect();
    if segments.is_empty() || segments.iter().any(|s| s.contains(char::is_whitespace)) {
        return None;
    }
    let out = format!("{host}/{}", segments.join("/"));
    (out.len() <= 512).then_some(out)
}

fn strip_port(host: &str) -> &str {
    if host.starts_with('[') {
        return host.split_once(']').map_or(host, |(h, _)| &h[1..]);
    }
    match host.rsplit_once(':') {
        Some((h, port)) if port.chars().all(|c| c.is_ascii_digit()) => h,
        _ => host,
    }
}

/// The nearest existing directory at or above `cwd`: a deleted worktree resolves through the
/// directory that held it.
fn existing_ancestor(cwd: &Path) -> Option<PathBuf> {
    let mut p = cwd.to_path_buf();
    loop {
        if p.is_dir() {
            return Some(p);
        }
        if !p.pop() {
            return None;
        }
    }
}

/// Asks git for the cwd's remotes (origin first). Read-only: no prompts, no optional locks.
pub fn resolve(cwd: &str) -> Option<String> {
    let path = Path::new(cwd);
    if !path.is_absolute() {
        return None;
    }
    let dir = existing_ancestor(path)?;
    let out = Command::new("git")
        .arg("-C")
        .arg(&dir)
        .args(["config", "--get-regexp", r"^remote\..*\.url$"])
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_OPTIONAL_LOCKS", "0")
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    let mut first = None;
    for line in text.lines() {
        let Some((key, url)) = line.split_once(' ') else {
            continue;
        };
        if key == "remote.origin.url" {
            return normalize_remote(url);
        }
        if first.is_none() {
            first = normalize_remote(url);
        }
    }
    first
}

#[cfg(test)]
mod tests {
    use super::normalize_remote as n;

    #[test]
    fn strips_userinfo_query_fragment_and_port() {
        assert_eq!(
            n("https://someone:ghp_FAKESECRET123@github.com/Acme/App.git?token=FAKE#frag")
                .as_deref(),
            Some("github.com/Acme/App")
        );
        assert_eq!(
            n("https://oauth2:FAKE@gitlab.example.com:8443/group/sub/app").as_deref(),
            Some("gitlab.example.com/group/sub/app")
        );
        assert_eq!(
            n("ssh://git@Example.COM:2222/acme/app.git").as_deref(),
            Some("example.com/acme/app")
        );
        assert_eq!(
            n("git@github.com:acme/app.git").as_deref(),
            Some("github.com/acme/app")
        );
        assert_eq!(
            n("deploy:FAKE@host.example:acme/app").as_deref(),
            Some("host.example/acme/app")
        );
        for secret in ["ghp_FAKESECRET123", "FAKE", "someone", "oauth2", "token"] {
            for url in [
                "https://someone:ghp_FAKESECRET123@github.com/a/b?token=FAKE",
                "https://oauth2:FAKE@h.example/a/b",
            ] {
                assert!(!n(url).unwrap().contains(secret), "{url} leaked {secret}");
            }
        }
    }

    #[test]
    fn local_remotes_have_no_repo_key() {
        assert_eq!(n("/srv/git/app.git"), None);
        assert_eq!(n("../app"), None);
        assert_eq!(n("file:///srv/git/app.git"), None);
        assert_eq!(n(""), None);
        assert_eq!(n("https://host.example/"), None);
    }
}
