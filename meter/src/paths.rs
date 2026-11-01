//! Where the meter keeps its state, and the single-flight lock.
//!
//! - macOS: `~/Library/Application Support/automagik/`
//! - Linux and others: `$XDG_STATE_HOME/automagik/`, else `~/.local/state/automagik/`
//! - `METER_STATE_DIR` overrides both (tests, trials).
//!
//! The directory is created 0700 and the database 0600.

use std::fs::{self, File, OpenOptions};
use std::io;
use std::path::{Path, PathBuf};

pub fn home() -> Option<PathBuf> {
    std::env::var_os("HOME")
        .filter(|h| !h.is_empty())
        .map(PathBuf::from)
}

pub fn default_state_dir() -> Option<PathBuf> {
    if let Some(d) = std::env::var_os("METER_STATE_DIR").filter(|d| !d.is_empty()) {
        return Some(PathBuf::from(d));
    }
    let home = home()?;
    if cfg!(target_os = "macos") {
        return Some(home.join("Library/Application Support/automagik"));
    }
    match std::env::var_os("XDG_STATE_HOME").filter(|d| !d.is_empty()) {
        Some(x) if Path::new(&x).is_absolute() => Some(PathBuf::from(x).join("automagik")),
        _ => Some(home.join(".local/state/automagik")),
    }
}

#[cfg(unix)]
fn set_mode(p: &Path, mode: u32) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(p, fs::Permissions::from_mode(mode))
}

#[cfg(not(unix))]
fn set_mode(_: &Path, _: u32) -> io::Result<()> {
    Ok(())
}

/// Creates the state directory (0700) and the database file (0600) if missing, and tightens the
/// modes if they are looser.
pub fn prepare(state_dir: &Path) -> io::Result<PathBuf> {
    fs::create_dir_all(state_dir)?;
    set_mode(state_dir, 0o700)?;
    let db = state_dir.join("meter.db");
    let mut o = OpenOptions::new();
    o.create(true).append(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        o.mode(0o600);
    }
    drop(o.open(&db)?);
    set_mode(&db, 0o600)?;
    Ok(db)
}

/// A non-blocking exclusive lock on `meter.lock`. None means another run holds it.
pub fn try_lock(state_dir: &Path) -> io::Result<Option<File>> {
    let mut o = OpenOptions::new();
    o.create(true).truncate(false).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        o.mode(0o600);
    }
    let f = o.open(state_dir.join("meter.lock"))?;
    match f.try_lock() {
        Ok(()) => Ok(Some(f)),
        Err(fs::TryLockError::WouldBlock) => Ok(None),
        Err(fs::TryLockError::Error(e)) => Err(e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prepares_private_state_and_single_flight_lock() {
        let dir = tempfile::tempdir().unwrap();
        let state = dir.path().join("s");
        let db = prepare(&state).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&state).unwrap().permissions().mode() & 0o777,
                0o700
            );
            assert_eq!(
                fs::metadata(&db).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
        let first = try_lock(&state).unwrap();
        assert!(first.is_some());
        assert!(
            try_lock(&state).unwrap().is_none(),
            "second lock must not be granted"
        );
        drop(first);
        assert!(try_lock(&state).unwrap().is_some());
    }
}
