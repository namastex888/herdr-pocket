---
title: "Google Play Data safety draft"
type: reference
created: 2026-11-01
updated: 2026-11-01
tags: [play, privacy, data-safety]
---
# Google Play Data safety: draft answers

A draft for the **play** flavor (`bundlePlayRelease`), mapped to the sections of Play Console's
Data safety form. Every answer cites the code or doc it rests on. The owner reviews it and fills
in the Console; nothing here is submitted. Paths are relative to the repo root.

**Answers marked "Owner decides" are open.** They list the options and a recommended answer.

## Basis: what Play counts

From Play's [Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469):

- **Collect:** "transmitting data from your app off a user's device". This "includes data
  collected and handled through any third-party libraries or SDKs used in their apps".
- **Share:** "transferring user data collected from your app to a third party". Transfers to a
  "service provider", meaning "an entity that processes user data on behalf of the developer and
  based on the developer's instructions", are not sharing. Neither are transfers "based on a
  specific user-initiated action, where the user reasonably expects the data to be shared".
- **End-to-end encryption:** data "unreadable by you or anyone other than the sender and
  recipient" as a result of end-to-end encryption need not be disclosed.
- **Ephemeral:** data "only stored in memory and retained for no longer than necessary to service
  the specific request in real-time" is not disclosed. It is still entered in the form.
- **IP addresses:** disclose them "based on their particular usage and practices". For example,
  "where developers use IP addresses as a means to determine location, then that data type should
  be declared".

## What leaves the phone

The developer runs no server, account system, analytics or ads ([privacy.md](../privacy.md)).
App data leaves the phone in three ways. The third is the one the earlier draft missed.

1. **SSH to the user's own machine** (`Ssh.kt`). Encrypted between the phone and that machine.
2. **Pocket Link terminal content**, over iroh (QUIC with TLS 1.3), end to end between the phone
   and the user's desktop. Relays forward it and cannot read it: iroh's
   [Security & Privacy](https://docs.iroh.computer/concepts/security-privacy) page says "A relay
   forwards encrypted bytes and holds no key that can decrypt them". This is covered by the
   end-to-end exemption.
3. **Pocket Link metadata, to infrastructure run by n0** (the company behind iroh). It is not
   covered by the end-to-end exemption.

### Pocket Link metadata in detail

**The phone uses n0's defaults.**

- `IrohLink.kt:60-66` binds the endpoint with only `secretKey` and `alpns`: no `preset`, no
  `relayMode`.
- iroh-android 1.2.0 is built from [iroh-ffi v1.2.0](https://github.com/n0-computer/iroh-ffi/tree/v1.2.0).
  There, a missing preset becomes the n0 preset (`src/endpoint.rs:337`,
  `options.preset.unwrap_or_else(preset_n0)`; `src/endpoint.rs:83-85` applies `presets::N0`).
- Its `Cargo.lock` resolves iroh **1.4.1** (`Cargo.lock:1512-1513`), although `Cargo.toml` asks
  for 1.0.0. The shipped `libiroh_ffi.so` agrees: it contains the strings `iroh-1.4.1`,
  `iroh-relay-1.4.1` and `iroh-dns-1.4.1`, and the n0 host names.
- In [iroh 1.4.1](https://github.com/n0-computer/iroh/tree/v1.4.1), `presets::N0`
  (`iroh/src/endpoint/presets.rs:116-139`) adds these:
  - `PkarrPublisher::n0_dns()`, which publishes to `https://dns.iroh.link/pkarr`
    (`iroh/src/address_lookup/pkarr.rs:127`)
  - `PkarrResolver::n0_dns()` and `DnsAddressLookup::n0_dns()`, which look peers up there
  - `default_relay_mode()`, which uses n0's public relays `use1-1`, `usw1-1`, `euc1-1` and
    `aps1-1.relay.n0.iroh.link` (`iroh/src/defaults.rs:27-33`)

**The desktop bridge uses them too.**

- `desktop/pocket-link/mods/pocket-link/bridge/bridge.mjs:146` binds with no relay mode.
- The iroh-js binding (`@number0/iroh` 1.2.0, iroh-ffi `iroh-js/src/endpoint.rs:324`) always
  calls `apply_n0()`.
- The bridge's own output is local only. Its `status.json` holds the id and ticket in the user's
  state directory (`bridge.mjs:40`, `bridge.mjs:147`).

**The phone's identifier is persistent.**

- The phone generates an Ed25519 key once and keeps it in `noBackupFilesDir/iroh.key`
  (`IrohLink.kt:63-64`). The bridge does the same in `secret.key`, mode 0600 (`bridge.mjs:52-53`).
- The **endpoint ID is the public half of that key.** It stays the same for the life of the
  install, so it is a pseudonymous per-install device identifier. Uninstalling or clearing app
  data makes a new one.

**What n0 receives.**

| Who at n0 | What they get | When | Source |
|---|---|---|---|
| DNS / pkarr server `dns.iroh.link` | A signed record: the endpoint ID and the URL of the relay the endpoint uses. IP addresses are filtered out by default. The phone's IP address is seen on the HTTPS request. | At bind, then republished every 5 minutes while the endpoint is open (TTL 30 s) | `pkarr.rs:143-146`, `pkarr.rs:171` (`AddrFilter::relay_only()`), `pkarr.rs:215-216`; iroh docs: "Internal IP addresses are not published by default" |
| The same server, on lookups | The endpoint ID being looked up (the desktop's), from the phone's IP address. The DNS form of the lookup goes to the DNS servers of the phone's network. | When dialling a desktop | `presets.rs:127-134` |
| n0's public relays | Per iroh's docs: "The identities of the devices connected to it, which pairs are exchanging traffic, when, and how much", and "source and destination IP addresses, connection times, and the amount of relayed data" | While the endpoint is open; traffic stops going through the relay once a direct path is found | [iroh Security & Privacy](https://docs.iroh.computer/concepts/security-privacy) |

The endpoint is opened only to pair with a desktop, or to connect to a paired one
(`PocketViewModel.kt:203`, `PocketViewModel.kt:276`). It then stays open until the app process
ends (`IrohLink.kt:60-72`). **A user who only uses SSH never contacts n0.**

On retention, iroh's docs say "We monitor the public relays for abuse and malicious activity" and
that n0 may "block offending IP addresses". The pages read for this draft give no retention
period.

**No metrics or diagnostics leave the device.**

- `iroh-metrics` is compiled in, but `Endpoint::metrics()` is an in-process counter set
  (iroh `iroh/src/endpoint.rs:1527-1530`).
- The only code that sends metrics anywhere is the iroh-services client (iroh-ffi
  `src/services.rs:1`: "push metrics to services.iroh.computer").
- Neither `IrohLink.kt` nor `bridge.mjs` creates it, or `ServicesPresetOptions`. The app
  contains no iroh "doctor" call.

### Other facts the answers rest on

- **No update check in play.** The play flavor compiles no updater code
  (`android/app/src/play/java/ai/namastex/automagik/SelfUpdate.kt`,
  `android/app/src/testPlay/java/ai/namastex/automagik/PlayDistributionTest.kt`). The github
  flavor's check to GitHub is outside this form.
- **The terminal page is local.** It loads from the app's own assets, not the network
  (`TerminalScreen.kt:436`; `appassets.androidplatform.net` is served on the phone by the WebView).

## Section 1: Data collection and security

| Question | Draft answer | Source |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes**: the Pocket Link endpoint ID (Device or other IDs), sent to n0 by the iroh library. Recommended; see Section 2. | "Pocket Link metadata" above |
| Is all of the user data collected by your app encrypted in transit? | **Yes.** Relay connections and pkarr publishing use TLS (HTTPS / QUIC). **Owner decides:** one caveat applies. The DNS form of a peer lookup (`DnsAddressLookup`) goes to the network's DNS servers and may be plain DNS, and it names the desktop's endpoint ID. Recommended answer: Yes, because the collected item (the phone's own ID) is only sent over TLS. | `presets.rs:127-134`, `pkarr.rs:127` |
| Do you provide a way for users to request that their data is deleted? | **Owner decides.** On the phone, uninstalling or clearing app data deletes the key, and the next endpoint ID is new. The developer holds nothing to delete. n0's copy is a record that is republished every 5 minutes and stops when the phone stops publishing it. Recommended: "No" to the in-app request mechanism, and explain this in the privacy policy. | `IrohLink.kt:63-64`, `pkarr.rs:146` |

## Section 2: Data types, one by one

### Declared (recommended)

**Device or other IDs: the Pocket Link endpoint ID.**

- **Why it counts:** Play defines this type as "Identifiers that relate to an individual device,
  browser or app". The endpoint ID is one, and the iroh library in the app transmits it off the
  phone.
- **Collected:** Yes.
- **Shared, Owner decides:**
  - **(a) Shared with a third party (n0). Recommended.** n0's public relays are open
    infrastructure with no agreement with the developer. iroh's docs say they are "for
    development and hobby use only". So the service-provider exemption ("on behalf of the
    developer and based on the developer's instructions") is hard to claim.
  - (b) Not shared, if the owner treats n0 as a service provider.
  - (c) Not shared, under the user-initiated exemption: the user starts the pairing. This is
    weak, because users don't "reasonably expect" a third party to be involved.
- **Ephemeral:** No. The pkarr record is stored by `dns.iroh.link` and republished. The relays'
  retention is not documented.
- **Required or optional, Owner decides:** recommended **optional**. Pocket Link is opt-in: SSH
  works without it, and nothing is sent until the user pairs.
- **Purpose:** App functionality (reaching the user's desktop).

### IP address

Play has no separate IP address type. Its FAQ ties disclosure to how IP addresses are used, and
names location: "where developers use IP addresses as a means to determine location, then that
data type should be declared".

- The app never infers location. n0 sees the phone's IP address as any server it connects to
  would, and iroh's docs describe its use only for abuse monitoring and blocking.
- **Reading, Owner decides:** no "Approximate location" entry. Mention the IP address under the
  Device or other IDs entry, and in the privacy policy.
- Alternative: also declare "Approximate location: shared with n0", if the owner wants to cover
  the case where n0 geolocates IP addresses. Its docs don't say it does.

### Not collected

Each type below is **not collected and not shared**. The table says what the app does with it, so
the owner can check the answer.

| Play data type | What the app does | Leaves the phone? | Source |
|---|---|---|---|
| Personal info (name, email, user IDs, address, phone) | Not asked for. No account. | No | [privacy.md](../privacy.md) |
| Financial info | None | No | — |
| Health and fitness | None | No | — |
| Messages (emails, SMS, other in-app messages) | Messages and replies the user types or speaks to an agent | Only to the user's own machine, end-to-end encrypted (SSH or iroh); relays can't read them | `PocketViewModel.kt` (`reply`, `sendMessage`), `Notify.kt` (Reply action) |
| Photos and videos | None. The QR scanner reads frames on the phone and keeps no picture. | No | `MainActivity.kt` (`ScanContract`), [privacy.md](../privacy.md) "Camera" |
| Audio (voice or sound recordings) | The microphone in the assistant panel. Android's `SpeechRecognizer` hands the audio to the phone's default speech service and returns text; the app never records or stores audio. | The app sends none. The speech service (chosen in Android settings) may, under its own policy. | `AssistActivity.kt:141-160`, [privacy.md](../privacy.md) "Microphone" |
| Files and docs | Program icons read from the user's own desktop, cached on the phone | No (they come from the user's machine) | `ProgramIcons.kt:31` |
| Calendar, contacts | None | No | — |
| App activity (interactions, search history, installed apps) | None collected. Relays see connection timing and byte counts as a side effect of forwarding; that is covered by the endpoint ID entry and the privacy policy, not by app activity. | No | iroh Security & Privacy |
| Web browsing | A tapped terminal link opens in the user's browser | No (the browser handles it) | [privacy.md](../privacy.md) "Web links" |
| App info and performance: **crash logs** | One crash report kept on the phone: app version, Android version, phone make and model, time, stack trace | **Only if the user taps Copy or Share** in Settings → About; the user picks the recipient | `CrashLog.kt:26`, `CrashLog.kt:49-52` (`report`), `Screens.kt` (`CrashRow`) |
| App info and performance: diagnostics | None. iroh's metrics stay in memory; nothing pushes them. | No | "No metrics or diagnostics leave the device" above |
| Location | None. mDNS discovery of SSH servers on the local network sends nothing until the user connects. See also "IP address" above. | No | `Discovery.kt`, [privacy.md](../privacy.md) "Your local network" |

The phone's make and model go once to the desktop the user pairs with, end-to-end encrypted, to
name the phone (`IrohLink.kt:83`). The SSH key's public half goes only to the user's own machines.

### Notes the owner should check

- **Crash log.** When the user shares it with a recipient they choose, that is not "collection" by
  the developer under Play's definitions. To be conservative, declare "Crash logs: collected,
  optional, not shared, for app functionality / analytics", processed only when the user sends it.
- **Speech service.** The audio goes to a system service the user chose, not to the developer or
  to an SDK in the app. Play's form covers the app's own collection.
- **n0's own guidance.** iroh's docs say public relays are for "development and hobby use only".
  They recommend "against using public relays for sensitive or confidential data". Terminal
  content stays end-to-end encrypted either way; the metadata is the exposure. RFC 0006 lists
  the options.

## Section 3: Security practices

| Practice | Draft answer | Source |
|---|---|---|
| Data encrypted in transit | Yes: SSH, and iroh (QUIC / TLS 1.3) | `Ssh.kt`, `IrohLink.kt` |
| Cleartext traffic | Off for the whole app | `AndroidManifest.xml` (`usesCleartextTraffic="false"`) |
| Users can request deletion | See Section 1. Everything the developer could delete is on the phone. | `data_extraction_rules.xml` |
| Independent security review | No | — |
| Committed to Play Families policy | Not applicable: not directed at children | [privacy.md](../privacy.md) "Children" |

## Privacy policy URL

`https://github.com/automagik-dev/automagik-pocket/blob/dev/docs/privacy.md`. It says what
differs between the GitHub and Play builds, and names n0 as a third party for Pocket Link.

## Related

- [Play listing notes](listing.md)
- [RFC 0006 — Distribution flavors](../rfcs/0006-distribution-flavors.md)
- [Privacy policy](../privacy.md)
- iroh: [Security & Privacy](https://docs.iroh.computer/concepts/security-privacy),
  [Public relays](https://docs.iroh.computer/iroh-services/relays/public),
  [Self-hosted relays](https://docs.iroh.computer/iroh-services/relays/self-hosted)
