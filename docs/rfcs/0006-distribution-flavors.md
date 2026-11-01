---
rfc: "0006"
title: "Distribution flavors: github (self-updating APK) and play (Google Play bundle)"
status: Draft
created: 2026-11-01
pr: ""
---
# RFC 0006 — Distribution flavors: github and play

## Summary

The Android app builds in two flavors of one `distribution` dimension. **github** is today's app:
the GitHub release APK that updates itself from `latest.json`. **play** is the Google Play app
bundle: Play updates it, so it has no updater code, no installer permission and no update check.
Both keep the app ID `ai.namastex.automagik` and the release key. Both target Android 16 (API 36).

## Motivation

- Google Play forbids an app from updating itself outside Play, and restricts
  `REQUEST_INSTALL_PACKAGES`. The app ships both today
  (`android/app/src/main/AndroidManifest.xml`, `Updater.kt`).
- Play takes new apps and updates only at `targetSdk` 36 or higher
  ([target API requirements](https://developer.android.com/google/play/requirements/target-sdk)).
- RFC 0002 open question 3 asked whether the self-updater stays in a Play build. The owner
  answered when asking for this work (see Decisions).

## Current state

Before this change (dev after #28, silent background updates):

- One variant per build type. `compileSdk`/`targetSdk` 35, `minSdk` 26.
- `Updater.kt` and `UpdatePolicy.kt` lived in `src/main`. Main code called `Updater` from
  `PocketApplication` (`init`: periodic checks), `PocketViewModel.kt` (`usage`, `opened`/`left`),
  `MainActivity.kt` (`touched`, the "ready · Install" notification's intent, Android's confirm
  prompt, the top-bar progress line) and `Screens.kt` (`UpdateRow`: Check for updates, Install
  updates automatically).
- The manifest declares `REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`,
  `ACCESS_NETWORK_STATE` (the updater's metered-network check, and iroh's network reads), the
  installer's result receiver `.UpdateReceiver` and `.UpdatedReceiver` (`MY_PACKAGE_REPLACED`).
- CI and `release.yml` ran `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleRelease`.

## Design

### Flavors

| | github (default) | play |
|---|---|---|
| Build | APK, `assembleGithubRelease` | App bundle, `bundlePlayRelease` |
| Updates | Itself, from `latest.json` (RFC 0002) | Google Play |
| `BuildConfig.SELF_UPDATE` | `true` | `false` |
| `BuildConfig.UPDATE_URL` | the `latest.json` URL (`pocket.updateUrl` overrides) | not defined |
| Updater code | `src/github/java/.../Updater.kt`, `UpdatePolicy.kt` | none compiled |
| Installer permissions | kept (`src/main`) | removed by `src/play/AndroidManifest.xml` |
| `.UpdateReceiver`, `.UpdatedReceiver` | declared in `src/github/AndroidManifest.xml` | not declared |
| Native libraries | compressed in the APK (smaller download), extracted at install | stored uncompressed and 16 KB-aligned in Play's APKs; every library's ELF is 16 KB-aligned since #36 |
| App ID, signing key, versionCode, versionName | same | same |

`isDefault = true` keeps github the variant Android Studio picks.

### The seam: hooks with two implementations

Main code never names `Updater`. It calls the declarations below, which exist twice, with identical
signatures, in `src/github/java/ai/namastex/automagik/SelfUpdate.kt` (real) and
`src/play/java/ai/namastex/automagik/SelfUpdate.kt` (no-ops):

| Hook | Called from | github | play |
|---|---|---|---|
| `SelfUpdate.init(app)` | `PocketApplication.onCreate` | `Updater.init`: "Updated to", periodic checks | nothing |
| `SelfUpdate.onLaunch(foreground, terminalOpen, watching)` | `PocketViewModel` init | `Updater.usage` | nothing |
| `SelfUpdate.foreground(shown)` | `PocketViewModel.setForeground` | `Updater.opened()` / `left()` | nothing |
| `SelfUpdate.touched()` | `MainActivity.onUserInteraction` | `Updater.touched()` | nothing |
| `SelfUpdate.isInstallIntent(intent)`, `installFromNotification(activity)` | `MainActivity.openUpdate` | the "ready · Install" tap | `false`, nothing |
| `SelfUpdateEffects()` | `PocketApp` | confirm prompt, resume after the permission screen | nothing |
| `selfUpdateProgress()` | top bar | progress of a download the person started, or `null` | `null` |
| `SelfUpdateRow()` | Settings → About | `UpdateRow`: Check for updates, Install updates automatically | nothing |

A new self-update entry point is a new hook in both files; its classes go in `src/github`.

### Manifest

- `src/main/AndroidManifest.xml` keeps `REQUEST_INSTALL_PACKAGES` and
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`; `src/play/AndroidManifest.xml` removes both with
  `tools:node="remove"`. A future updater-only permission needs a remove line there too.
- `ACCESS_NETWORK_STATE` stays in both flavors. The github updater uses it for its metered-network
  check, and iroh's native library (iroh-android 1.2.0) calls `ConnectivityManager` through JNI
  (active and all networks, capabilities, link properties, DNS servers) once `IrohLink.kt`
  installs the Android context. `verify<Variant>UpdaterManifest` fails a variant without it.
- Updater components are declared in `src/github/AndroidManifest.xml`, next to their classes.
  Declaring them in `src/main` would fail lint's `MissingClass` on play, where the class doesn't
  exist.

### Checks

- `verify<Variant>UpdaterManifest` (`android/app/build.gradle.kts`) reads each variant's merged
  manifest. On play it fails if any of `REQUEST_INSTALL_PACKAGES`,
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, `INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES` or a
  component named `*.Update…Receiver` is there. On github it fails if the first two or the receiver
  are missing. `assemble`, `bundle`, `lint` and `check` of the variant depend on it.
- `src/testPlay/.../PlayDistributionTest.kt`: `SELF_UPDATE` is false, `UPDATE_URL` does not exist,
  and `Updater`, `UpdateState`, `UpdateReceiver`, `Release` (and the silent-update classes) are
  not on the play classpath.
- `src/testGithub/.../GithubDistributionTest.kt` and `ReleaseTest.kt`: the github updater as before.

### CI and release

- `android.yml` builds, unit-tests and lints both flavors, and builds the play bundle
  (debug-signed on pull requests).
- `release.yml` publishes `automagik.apk` (github) and `latest.json` exactly as before; the
  version check reads the github APK's metadata. It also builds the play bundle with the same key
  and keeps it as a workflow artifact `automagik-play-<version>-<versionCode>.aab`. The bundle is
  never a release asset. Uploading it to Play is a manual step outside the workflow.
  A release that carries the previous app forward (android/ unchanged, #37) builds neither.

### Android 16 (API 36)

`compileSdk` and `targetSdk` go to 36. AGP 8.9.1, Gradle 8.11.1 (checksum-pinned) and Kotlin
2.1.20 stay: AGP 8.9.1 builds and lints at `compileSdk` 36 without a compatibility warning, so no
upgrade is needed. The audit of the target-36 behaviour changes is in
the pull request. Source changes: `android:enableOnBackInvokedCallback="true"` (predictive back,
on Android 13–15 too), and a `<queries>` entry for `android.speech.RecognitionService` so that
`SpeechRecognizer` can see the speech service under package visibility.

## Decisions

- Owner, 2026-11-01: prepare the app for Google Play with a `play` flavor that has no
  self-update, next to an unchanged `github` flavor, at API 36, signed with the existing release
  key (the request for this work). This answers RFC 0002 open question 3 for the Play build.

## Alternatives considered

- **A `BuildConfig.SELF_UPDATE` check at each call site, one source set.** Smaller diff, but the
  updater, its network code and its receiver class would still be compiled into the Play build.
- **A `play` stub of `Updater` with the same API.** The stub would have to follow every change to
  `Updater`'s API (the silent-update work adds about ten members). Four hooks are a smaller
  contract.
- **Moving the permissions into a `src/github` manifest.** Equivalent result, but it conflicts
  with in-flight edits to those lines in `src/main`. Removing them in play keeps those lines
  where they are.

## Open questions

1. Enrol Play App Signing with the existing key, so Play and GitHub installs update each other? —
   the owner.
2. Can one phone switch between a GitHub install and a Play install? Same app ID and key, so
   Android allows it, but Play may not offer updates to a copy it didn't install. — the owner.
3. Android's local network protection (opt-in on Android 16, enforced later) will need a runtime
   permission for SSH to LAN addresses and mDNS discovery. When to adopt it? — the owner.
4. ~~JNA 5.15.0's `libjnidispatch.so` (from iroh-android 1.2.0) isn't 16 KB-ready.~~ **Resolved
   by #36**, which pins JNA 5.17.0; every native library in the play bundle now passes the 16 KB
   check ([listing notes](../play/listing.md)).
5. Pocket Link uses n0's public relays and discovery server: iroh's default, unchanged by this
   RFC ([Data safety draft](../play/data-safety.md), "Pocket Link metadata"). n0 sees the
   phone's persistent endpoint ID, IP addresses, timing and byte counts. iroh's docs call the
   public relays "for development and hobby use only". Keep them for the Play release, or move
   first? Options for later, none built here:
   - Your own relays: `EndpointOptions.relayMode = RelayMode.customFromUrls(...)` in
     `IrohLink.kt`, and `Endpoint.bind(options, relayMode)` in `bridge.mjs`. A small change, but
     both ends need it, plus a relay to run.
   - No relays: `preset_n0_disable_relay`. This loses connections that can't go direct.
   - No public discovery: a custom `Preset` (iroh-ffi exports the trait for Kotlin) on
     `apply_minimal()` plus a relay mode. The pairing ticket already carries the desktop's relay
     and addresses, but a desktop whose addresses change would then need a fresh ticket. That
     needs a device test. — the owner.

## Related

- [RFC 0002 — Release and update channel](0002-release-and-update-channel.md)
- [Play listing notes](../play/listing.md), [Data Safety draft](../play/data-safety.md)
- [Privacy policy](../privacy.md)
