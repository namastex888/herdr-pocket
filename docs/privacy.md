---
title: "Privacy policy"
type: policy
created: 2026-11-01
updated: 2026-11-01
tags: [privacy, policy, play]
---
# Automagik Pocket privacy policy

Effective 2026-11-01. Applies to the Android app **Automagik Pocket** (app ID
`ai.namastex.automagik`), published from
[automagik-dev/automagik-pocket](https://github.com/automagik-dev/automagik-pocket).

The app comes in two builds that handle data the same way, except for updates:

- **Google Play build:** installed and updated by Google Play. It makes **no update check** and
  contacts no GitHub server.
- **GitHub build** (`automagik.apk` from the GitHub releases): checks GitHub for updates and
  installs them itself, as described below.

## In short

- The app connects your phone to **your own computers**. We run no server for it and have no
  account system.
- **No analytics, no ads, no tracking.** The app contains no analytics or advertising library.
- The only other services it contacts: **n0's iroh relays and discovery server**, for Pocket
  Link only, and, in the GitHub build only, **GitHub**, to check for updates. n0 can't read your
  terminal traffic, but it does see your phone's Pocket Link ID, its IP address, and when and how
  much it talks to your desktop ([below](#pocket-link-and-n0)). If you only use SSH, the app never
  contacts n0.
- What it keeps stays on the phone, is not backed up, and is deleted when you uninstall.

## What the app handles, and where it goes

| Data | Where it goes | Why |
|---|---|---|
| Terminal screens, agent status, messages and replies you type or speak | Only between the phone and your machine | This is what the app is for |
| Machine details: name, hostname, SSH user, port, session name, host key fingerprint | Stays on the phone | To reconnect |
| SSH password | Sent to that machine when it asks; **never saved** | To log in when the key is refused |
| The phone's make and model (for example "Pixel 8") | Sent once to the desktop you pair with | So the desktop can name the paired phone |
| The phone's Pocket Link ID (the public half of its iroh key), its IP address, and when and how much it sends | n0's iroh discovery server and relays, while Pocket Link is in use ([below](#pocket-link-and-n0)) | So the phone and the desktop can find and reach each other |
| Folder git status, origin remote URL and Herdr desktop program icons | Read from your machine; the git answers stay in memory, icons cached on the phone | To draw the spaces tree like the desktop, and to group spaces into projects and branches into tasks |
| Usage meter summary: token counts and estimated $ per repository, branch, model and hour (the `herdr-meter` document, if you run the meter) | Read from your own machines and kept in memory; never written, never sent anywhere else | To show spend on Today, Projects and Tasks |
| Update check (app version in the request), GitHub build only | GitHub | To offer a newer release |

Nothing else is collected. We, the developers, receive none of this data. n0, the company that
runs iroh's public infrastructure, receives the Pocket Link metadata above.

## Network connections

1. **Your machines over SSH.** Direct to the host and port you enter. The host key fingerprint is
   checked and a changed key needs your decision again.
2. **Your machines over Pocket Link.** iroh connections, end-to-end encrypted, dialled by public
   key. The phone and the desktop find each other through n0's discovery server, and traffic goes
   through n0's relays until a direct path is found. See [Pocket Link and n0](#pocket-link-and-n0)
   for what n0 sees.
3. **GitHub (GitHub build only).** When the app opens (at most every 15 minutes), every 6 hours
   while it or **Watch in background** runs, and when you tap **Settings → About → Check for
   updates**, the app downloads `latest.json` from this repo's latest GitHub release. When a newer
   version is out it downloads the APK from GitHub too: on its own on Wi-Fi with **Install updates
   automatically** on, otherwise when you tap **Install**. GitHub sees your IP address and a
   User-Agent with the app version. The check itself can't be turned off in the GitHub build. The
   Google Play build has no update check: Google Play installs its updates.
4. **Your local network.** While the welcome screen or the Machines screen is open, the app looks
   for SSH servers advertised on the local network (mDNS, `_ssh._tcp`) to suggest them. It sends
   nothing to them until you connect.
5. **Web links.** A link you tap in a terminal opens in your browser.

## Pocket Link and n0

Pocket Link uses [iroh](https://www.iroh.computer), with the default settings of its library. The
phone app (`IrohLink.kt`) and the desktop bridge (`bridge.mjs`) both use them. These defaults
use infrastructure run by **n0** ([n0.computer](https://n0.computer)), a third party:

- **Discovery server `dns.iroh.link`.** While Pocket Link is in use, the phone publishes a small
  signed record there every 5 minutes. It holds the phone's Pocket Link ID and the relay the
  phone uses, but not its IP addresses. The phone also looks up the desktop's ID there. The
  desktop bridge publishes its own record the same way.
- **Relays `*.relay.n0.iroh.link`.** They carry traffic until a direct path between the phone
  and the desktop is found.

**What n0 can't see:** your terminal screens, messages, replies or anything else inside the
connection. Traffic is end-to-end encrypted (QUIC with TLS 1.3) between the phone and your
desktop.

**What n0 can see.** iroh's [Security & Privacy](https://docs.iroh.computer/concepts/security-privacy)
page describes what a relay sees: "The identities of the devices connected to it, which pairs are
exchanging traffic, when, and how much", and "source and destination IP addresses, connection
times, and the amount of relayed data". Here that means:

- **The phone's Pocket Link ID.** This is the public half of a key the phone generates once. It
  stays the same until you uninstall the app or clear its data, so it is a persistent
  pseudonymous identifier for this install.
- **The desktop's ID.**
- **The IP addresses of both devices.**
- **Connection timing, and the amount of data relayed.**

n0 says it monitors its public relays for abuse and may block IP addresses. Its docs don't give a
retention period. n0's relays are shared public infrastructure.

**When.** The app contacts n0 only when you pair with a desktop or open a paired one. It then
stays connected until Android ends the app's process. Machines you reach over SSH never involve
n0.

**Your own relay.** The iroh library can use your own relays instead of n0's, and the relay server
can be [self-hosted](https://docs.iroh.computer/iroh-services/relays/self-hosted). This version of
the app and bridge has no setting for that: both always use n0's relays and discovery.

## What is stored on the phone

- Your saved machines and settings.
- The phone's SSH key and its Pocket Link (iroh) key, in app storage that Android never backs up.
- Cached program icons, and (GitHub build) a downloaded update until it installs.
- The last crash report, if the app crashed (below).

App backup and device-to-device transfer are turned off for all of it. Uninstalling the app deletes
it all, including the keys. There is no way to export the private keys.

## Crash reports

If the app crashes, it saves one report on the phone: app version, Android version, phone make
and model, the time, and the error's stack trace. It holds no keys and no saved settings; an error message in the
stack trace can still name things such as a machine.

Nothing is sent automatically. On the next launch the app says it closed unexpectedly; **Report**
opens **Settings → About**, where you can **Copy report**, **Share** it (Android's share sheet: you
choose the app and the recipient), or **Clear** it.

## Microphone

Used only when you tap the microphone in the side-button assistant panel. The app uses Android's
`SpeechRecognizer`, which hands the audio to the phone's **default speech recognition service**
(chosen in Android's settings, often Google's). That service turns it into text, and the app puts
the text in the message box for you to send. The app itself never records or stores audio. Whether
the audio leaves the phone depends on that service and its own privacy policy.

## Camera

Used only while you scan a Pocket Link QR code. The bundled scanner (ZXing) reads the code on the
phone. No picture is stored or sent.

## Notifications and the lock screen

- Notifications show what an agent asks or finished, so their text comes from your machines.
- **Answer from the lock screen** is off by default. While it's off, notifications are marked
  private, so Android hides their text on a secure lock screen, and replying or using the side-button panel asks you to unlock first. If you
  turn it on, anyone holding your locked phone can read and answer agents.

## Permissions

| Permission | Why |
|---|---|
| Internet | Connect to your machines and Pocket Link relays, and (GitHub build) GitHub for updates |
| Notifications | Alert you when an agent needs you or finishes |
| Foreground service (special use) | **Watch in background**: keep connections open while you're away, with a "Watching" notification you can stop |
| Microphone | Speak a message in the side-button panel |
| Camera | Scan the pairing QR code (added by the ZXing scanner library) |
| Install packages, update without asking again | GitHub build only: install updates from GitHub releases; Android asks once. The Google Play build doesn't request them. |
| Network state | Pocket Link (iroh) reads the phone's networks and DNS servers to reach your machine; in the GitHub build, automatic updates also download only on Wi-Fi and other unmetered networks |

## Children

The app is a developer tool and is not directed at children.

## Changes

Changes to this policy are made in this file, and its history is public in the repo.

## Contact

Open an issue on [GitHub](https://github.com/automagik-dev/automagik-pocket/issues). For a
security problem, use private reporting as described in [SECURITY.md](../SECURITY.md).
