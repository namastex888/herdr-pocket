---
title: "Releasing"
type: guide
created: 2026-11-01
updated: 2026-11-01
tags: [guide, release, ci, maintainers]
---
# Releasing

**Merge a pull request with a shipping commit into `dev`. CI does the rest.** Each such merge cuts
the next `0.N.0`. The design and the `latest.json` contract are in
[RFC 0002](../rfcs/0002-release-and-update-channel.md).

## 1. What cuts a release

| Commit types new on `dev` since the last `v0.N.0` tag | Release? |
|---|---|
| any `feat`, `fix`, `refactor`, `perf`, `build`, `chore` (other than `chore(release)`), `style`, `revert` | yes |
| only `docs`, `ci`, `test`, `chore(release)` | no |

The rule is in the "Anything new since the last release" step of `.github/workflows/release.yml`.
Commit subjects are checked on every pull request (`.github/workflows/commitlint.yml`), so a
subject that doesn't parse never reaches `dev`.

## 2. What CI does

`.github/workflows/release.yml`, on every push to `dev` (or run by hand: **Actions → Release → Run
workflow**):

1. **Cut** (job "Cut v<version> on dev"):
   1. Finds the newest `v0.N.0` tag and checks for shipping commits since it.
   2. Runs unit tests and lint from `android/`.
   3. `scripts/version.sh next` gives `0.(N+1).0`; `scripts/version.sh set` writes
      `version.properties`.
   4. Commits `chore(release): v0.(N+1).0`, tags it, and pushes the commit and tag to `dev` in one
      atomic push. If `dev` moved meanwhile, it stops; the run for the newer merge releases both.
2. **Publish** (job "Publish v…"):
   1. Builds the release APK (github flavor, `assembleGithubRelease`) and the Play bundle (play
      flavor, `bundlePlayRelease`) from the tag, signed with the release key from the
      `POCKET_SIGNING_KEYSTORE` and `POCKET_SIGNING_PASSWORD` secrets. No key, no release.
   2. Checks that the APK's `versionName` matches the tag.
   3. Writes `latest.json` (`versionCode`, `versionName`, `url`, `sha256`, `notes`).
   4. Publishes the GitHub release as **Latest** with `automagik.apk` and `latest.json`. The notes
      list the commit subjects since the previous release and the APK's SHA-256.
   5. Keeps the Play bundle as a workflow artifact, `automagik-play-<version>-<versionCode>.aab`,
      for a maintainer to upload to Google Play. It is never a release asset, and the workflow
      doesn't upload it to Play ([RFC 0006](../rfcs/0006-distribution-flavors.md)).

Installed copies find the new `latest.json` at their next launch.

**Releases that don't change the app.** When nothing under `android/` changed since the previous
release (a Pocket Link or meter change), Publish builds no APK and no Play bundle. It copies the
previous release's `automagik.apk` and `latest.json` unchanged, after checking that the APK
matches the `sha256` in `latest.json`. Phones see the same version and don't update. The meter
binaries and `SHA256SUMS` still ship with the new tag, and the notes say the app is unchanged.

## 3. Check a release

1. `gh release view -R automagik-dev/automagik-pocket` shows the latest tag, its assets and notes.
2. Download `automagik.apk` and check its signer against the fingerprint in
   [SECURITY.md](../../SECURITY.md):

   ```sh
   apksigner verify --print-certs automagik.apk
   ```

3. On a phone with the previous release: open the app, or **Settings → About → Check for updates**.

## 4. Don'ts

- Don't edit `version.properties` by hand. The release commit owns it.
- Don't push to `dev` directly; merge a pull request.
- Don't rotate or regenerate the signing key. Every installed copy would have to uninstall.
- Don't rename `automagik.apk` or `latest.json`, or change their fields, without an RFC: installed
  copies and forks depend on them.

## 5. Forks

A fork publishes with its own key and sets `pocket.updateUrl` in `android/gradle.properties` to
its own `latest.json` URL. Its installs can't take updates from this repo, and the other way round.
