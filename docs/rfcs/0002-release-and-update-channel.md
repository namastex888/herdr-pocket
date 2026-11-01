---
rfc: "0002"
title: "Release and update channel: v0.N.0 per merge to dev, latest.json"
status: Implemented
created: 2026-11-01
pr: "#6, #18"
---
# RFC 0002 — Release and update channel: v0.N.0 per merge to dev, latest.json

## Summary

Every merge to `dev` that brings a shipping commit cuts the next `v0.N.0`, built and signed by CI,
and published as the Latest GitHub release with `automagik.apk` and `latest.json`. Installed copies
read `latest.json`, download the APK, check its SHA-256, and hand it to Android, which installs it
only if it carries the same signing key. `latest.json` is a contract: installed apps and forks
depend on its URL and fields.

## Motivation

Recorded after the fact from #6 (self-update) and #18 (versioning). #6 gives the reason for
self-update: installed copies update "with no Play Store needed".

## Current state

- Version name: `version.properties` at the repo root (`version=0.3.0` when #18 merged), read by
  `android/app/build.gradle.kts:19`. `scripts/version.sh` prints `current`, computes `next` from
  the `v0.N.0` tags, and `set`s it.
- Version code: the commit time in minutes since 2025, `android/app/build.gradle.kts:16`, so each
  build is higher than the one before.
- Release job: `.github/workflows/release.yml`, on push to `dev`. Steps: "Anything new since the
  last release" (skips when only `chore(release)`, `ci`, `docs` or `test` commits are new), "The
  gates the release stands on" (unit tests and lint), "Version, commit and tag", "Build the release
  APK", "Publish".
- Commit subjects are checked on every pull request: `.github/workflows/commitlint.yml`.
- Update client: `android/app/src/main/java/ai/namastex/automagik/Updater.kt`. It checks at every
  launch (`PocketViewModel.kt:124`) and from **Settings → About → Check for updates**.
- v0.3.0 is the first release. It was published on 2026-11-01 before #18 merged, so
  `release.yml` did not cut it. From the next merge on, the workflow cuts releases.

## Design

### The `latest.json` contract

URL that installed copies read: `https://github.com/automagik-dev/automagik-pocket/releases/latest/download/latest.json`
(`android/app/build.gradle.kts:28`). A build sets another with the Gradle property `pocket.updateUrl`.

| Field | Rule in the app (`Updater.kt`) |
|---|---|
| `versionCode` | number above 0; offered only if higher than the installed one (`:37`, `:67`) |
| `versionName` | control characters dropped, at most 40 characters (`:38`) |
| `url` | must start with `https://` (`:37`); redirects are followed (`:114`) |
| `sha256` | 64 hex characters (`:31`); the download must match it or it is deleted (`:139-140`) |
| `notes` | optional, at most 600 characters (`:39`) |

The file is read up to 8 KB (`:109`). The APK may be at most 200 MB (`:60`).

### Install path

1. First update: Android's "Install unknown apps" screen, once (`Updater.kt:88-91`).
2. Android 12 and newer: installs without another prompt (`Updater.kt:150`). Older versions show
   "Update this app?".
3. Android rejects an APK signed with another key. The app then says to uninstall once and
   install the release (`Updater.kt:170-171`).

### Forks

A fork signs with its own key and points `pocket.updateUrl` at its own `latest.json`.

## Decisions

- Owner, 2026-11-01: installed copies update themselves from this repo's GitHub releases, checking
  the SHA-256 and relying on one release key (PR #6). Reason in the PR: no Play Store needed.
- Owner, 2026-11-01: adopt Automagik's release model: `0.N.0` per merge to `dev` with a shipping
  commit, `version.properties` plus `scripts/version.sh`, conventional commits enforced, default
  branch `dev` (PR #18). Reason in the PR for keeping `versionCode` as commit minutes: it always
  goes up, which Google Play needs.
- Owner, 2026-11-01: the per-push `build-*` releases on `main` from #6 are removed (PR #18).
  **Superseded** the #6 publishing step.

## Alternatives considered

None written down in the pull requests.

## Open questions

1. How long will installed copies rely on GitHub's redirect from the old repo URL to find
   `latest.json` (#16)? — the owner.
2. The `main` branch still exists on the remote. Keep, archive or delete it? — the owner.
3. If the app ships on Google Play, does the self-updater stay in the Play build? — the owner.

## Related

- automagik-dev/automagik-pocket #6, #18; #15 renamed the asset to `automagik.apk`.
- [Releasing guide](../guides/releasing.md)
- [RFC 0001 — Identity](0001-identity.md)
