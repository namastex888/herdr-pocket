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

## In short

- The app connects your phone to **your own computers**. We run no server for it and have no
  account system.
- **No analytics, no ads, no tracking.** The app contains no analytics or advertising library.
- The only other services it contacts: **GitHub**, to check for updates, and **n0's iroh relays**,
  which carry Pocket Link traffic they cannot read.
- What it keeps stays on the phone, is not backed up, and is deleted when you uninstall.

## What the app handles, and where it goes

| Data | Where it goes | Why |
|---|---|---|
| Terminal screens, agent status, messages and replies you type or speak | Only between the phone and your machine | This is what the app is for |
| Machine details: name, hostname, SSH user, port, session name, host key fingerprint | Stays on the phone | To reconnect |
| SSH password | Sent to that machine when it asks; **never saved** | To log in when the key is refused |
| The phone's make and model (for example "Pixel 8") | Sent once to the desktop you pair with | So the desktop can name the paired phone |
| Folder git status, origin remote URL and Herdr desktop program icons | Read from your machine; the git answers stay in memory, icons cached on the phone | To draw the spaces tree like the desktop, and to group spaces into projects and branches into tasks |
| Update check (app version in the request) | GitHub | To offer a newer release |

Nothing else is collected. We, the developers, receive none of this data.

## Network connections

1. **Your machines over SSH.** Direct to the host and port you enter. The host key fingerprint is
   checked and a changed key needs your decision again.
2. **Your machines over Pocket Link.** iroh connections, end-to-end encrypted, dialled by public
   key. When no direct path exists, the encrypted traffic passes through relay servers run by n0
   (the iroh project). Relays forward the encrypted bytes; they can't read them, though they do
   see the network addresses involved.
3. **GitHub.** At every launch, and when you tap **Settings → About → Check for updates**, the app
   downloads `latest.json` from this repo's latest GitHub release. If you install an update it
   downloads the APK from GitHub too. GitHub sees your IP address and a User-Agent with the app
   version. This check can't be turned off in the current version.
4. **Your local network.** While the welcome screen or the Machines screen is open, the app looks
   for SSH servers advertised on the local network (mDNS, `_ssh._tcp`) to suggest them. It sends
   nothing to them until you connect.
5. **Web links.** A link you tap in a terminal opens in your browser.

## What is stored on the phone

- Your saved machines and settings.
- The phone's SSH key and its Pocket Link (iroh) key, in app storage that Android never backs up.
- Cached program icons, and a downloaded update until it installs.
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
| Internet | Connect to your machines, Pocket Link relays, and GitHub for updates |
| Notifications | Alert you when an agent needs you or finishes |
| Foreground service (special use) | **Watch in background**: keep connections open while you're away, with a "Watching" notification you can stop |
| Microphone | Speak a message in the side-button panel |
| Camera | Scan the pairing QR code (added by the ZXing scanner library) |
| Install packages, update without asking again | Install updates from GitHub releases; Android asks once |

## Children

The app is a developer tool and is not directed at children.

## Changes

Changes to this policy are made in this file, and its history is public in the repo.

## Contact

Open an issue on [GitHub](https://github.com/automagik-dev/automagik-pocket/issues). For a
security problem, use private reporting as described in [SECURITY.md](../SECURITY.md).
