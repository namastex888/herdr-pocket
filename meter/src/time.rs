//! UTC timestamps as milliseconds since the Unix epoch. The harness logs write RFC 3339 times, so a
//! small parser is enough and keeps the binary free of a date library.

use std::time::{SystemTime, UNIX_EPOCH};

pub const HOUR_MS: i64 = 3_600_000;
pub const DAY_MS: i64 = 24 * HOUR_MS;

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm).
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

fn num(b: &[u8]) -> Option<i64> {
    if b.is_empty() || !b.iter().all(u8::is_ascii_digit) {
        return None;
    }
    std::str::from_utf8(b).ok()?.parse().ok()
}

/// Parses `YYYY-MM-DDTHH:MM:SS[.fff…](Z|±HH:MM)`. Returns None for anything else.
pub fn parse_rfc3339(s: &str) -> Option<i64> {
    let b = s.as_bytes();
    if b.len() < 20 || b[4] != b'-' || b[7] != b'-' || !matches!(b[10], b'T' | b't' | b' ') {
        return None;
    }
    if b[13] != b':' || b[16] != b':' {
        return None;
    }
    let (y, mo, d) = (num(&b[0..4])?, num(&b[5..7])?, num(&b[8..10])?);
    let (h, mi, se) = (num(&b[11..13])?, num(&b[14..16])?, num(&b[17..19])?);
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 || se > 60 {
        return None;
    }
    let mut i = 19;
    let mut ms = 0i64;
    if b.get(i) == Some(&b'.') {
        i += 1;
        let start = i;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        let frac = &b[start..i];
        if frac.is_empty() {
            return None;
        }
        let mut digits = [b'0'; 3];
        for (k, c) in frac.iter().take(3).enumerate() {
            digits[k] = *c;
        }
        ms = num(&digits)?;
    }
    let offset_min = match b.get(i) {
        Some(b'Z' | b'z') if i + 1 == b.len() => 0,
        Some(sign @ (b'+' | b'-')) if i + 6 == b.len() && b[i + 3] == b':' => {
            let v = num(&b[i + 1..i + 3])? * 60 + num(&b[i + 4..i + 6])?;
            if *sign == b'+' { v } else { -v }
        }
        _ => return None,
    };
    let days = days_from_civil(y, mo, d);
    let secs = days * 86_400 + h * 3600 + mi * 60 + se - offset_min * 60;
    Some(secs * 1000 + ms)
}

pub fn hour_start(ms: i64) -> i64 {
    ms.div_euclid(HOUR_MS) * HOUR_MS
}

pub fn day_start(ms: i64) -> i64 {
    ms.div_euclid(DAY_MS) * DAY_MS
}

/// `2026-01-01T09:00:00Z` (seconds precision).
pub fn format_rfc3339(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let (y, m, d) = civil_from_days(secs.div_euclid(86_400));
    let rem = secs.rem_euclid(86_400);
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        (rem / 60) % 60,
        rem % 60
    )
}

/// `2026-01-01`.
pub fn format_day(ms: i64) -> String {
    let (y, m, d) = civil_from_days(ms.div_euclid(DAY_MS));
    format!("{y:04}-{m:02}-{d:02}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_and_formats_round_trip() {
        let t = parse_rfc3339("2026-01-02T03:04:05.678Z").unwrap();
        assert_eq!(format_rfc3339(t), "2026-01-02T03:04:05Z");
        assert_eq!(t % 1000, 678);
        assert_eq!(format_day(t), "2026-01-02");
        assert_eq!(format_rfc3339(hour_start(t)), "2026-01-02T03:00:00Z");
    }

    #[test]
    fn handles_offsets_and_rejects_garbage() {
        let z = parse_rfc3339("2026-03-01T00:30:00Z").unwrap();
        assert_eq!(parse_rfc3339("2026-03-01T02:30:00+02:00"), Some(z));
        assert_eq!(parse_rfc3339("2026-02-28T23:30:00-01:00"), Some(z));
        assert_eq!(parse_rfc3339("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(parse_rfc3339("2026-13-01T00:00:00Z"), None);
        assert_eq!(parse_rfc3339("not a date at all!!"), None);
        assert_eq!(parse_rfc3339("2026-01-01T00:00:00"), None);
    }
}
