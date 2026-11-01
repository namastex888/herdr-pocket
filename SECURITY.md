# Security

## Report a vulnerability

1. Open **Security → Report a vulnerability** on this repository (GitHub private vulnerability reporting).
2. Describe what an attacker can do, the steps, and the app version (**Settings → About**).

Please don't open a public issue for a vulnerability.

## Check that an APK is ours

Every release is signed with one key. Its certificate's SHA-256 fingerprint:

```
59:2D:CE:D9:09:10:7A:B4:3E:7E:41:74:2C:92:7B:22:F6:8C:A7:83:25:CD:86:54:F3:80:D3:58:43:0F:91:83
```

Check a download before installing it:

```sh
apksigner verify --print-certs automagik.apk   # "Signer #1 certificate SHA-256 digest" must match
```

The app installs an update only when its SHA-256 matches the release's `latest.json`, and Android installs it only when it is signed with this same key.

## What the app can reach

- **Your machines:** over Pocket Link (iroh, end-to-end encrypted, no open port) or SSH (host key checked on first connect). The app stores its SSH key on the phone and never backs it up.
- **Typing into agents:** only what you send, from the app, a notification's Reply, or its Yes/No and option buttons. A button re-reads the agent's screen first and does nothing if the question changed.
- **Lock screen:** answering from the lock screen is off by default (**Settings → Alerts**). When on, anyone holding your locked phone can read what an agent asks and answer it.
- **Microphone:** only while you dictate in the side-button panel.
