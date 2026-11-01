---
title: "Using Automagik Pocket"
type: guide
created: 2026-11-01
updated: 2026-11-01
tags: [guide, users]
---
# Using Automagik Pocket

Everything the [README](../../README.md) keeps short. Install and first connection are there.

## Machines

- Save several machines, edit them later, and connect with one tap from the start screen. Adding a
  machine saves it and connects straight away.
- Pocket Link and SSH machines sit in the same list.
- **Pocket Link:** the phone dials the desktop by its public key over iroh. The connection is
  direct on the same network or through hole punching, and otherwise through n0's public relays.
  It is end-to-end encrypted either way. No port is opened on the computer. Removing the phone in
  the desktop's Pocket Link dialog disconnects it at once. The QR scanner is bundled, so it works
  without Google Play services; the app asks for the camera the first time.
- **SSH:** the phone uses its own Ed25519 key. If the host refuses it, the app asks for the SSH
  password and doesn't save it. The host key fingerprint is checked before logging in, and a
  changed key needs your decision again.
- Over SSH the app runs `hc connect`. If `hc` isn't on the login PATH it tries `herdr-core connect`
  and then `~/.local/bin/hc`. Nothing is installed on the host: no relay, web server or plugin.
- While the start screen or **Machines** is open, the app lists computers on the same network that
  advertise SSH.

## Tabs

The bottom bar has four tabs. It hides while a terminal is open, and Back from a terminal returns
to the tab you opened it from. Back on any other tab goes to Today. **Settings** is the gear at the
top.

- **Today** lists the agents that need you first, then the ones that finished, across every
  connected machine. The counts at the top are needs you, working, machines connected
  and, once a usage meter reports, spent today.
- **Projects** has one card per git repository. Spaces in the same repository, like a squad's
  worktrees, share one card. A folder that isn't a repository is a project of its own. Tap a card
  for its tasks, spaces and agents.
- **Projects → Spaces** is Herdr desktop's sidebar: spaces, their agents, git branch tails and
  nested worktrees, with the desktop's own program icons.
- **Tasks** groups agents by branch: Needs you, Working, Done, then Idle. Work on `main`, `master`
  or `dev`, or with no branch, is the project's **Untasked** row.
- **Machines** lists your machines, with **Pair with Herdr desktop** and **Add machine**.
- Spend appears once the [usage meter](#spend) runs on a machine.
- Space names, tab order and splits come from the desktop's `herdr-app` document, including
  machines that a core forwards.
- Pull down on the list to refresh or retry. A dropped connection shows **Reconnect**.
- Tap **+** (**New terminal**) in the top bar to start a shell or a command. From a space, it opens
  as a new tab that the desktop shows too.
- Leaving a terminal or closing the app never stops the program on the computer.
- **Settings → About → Explore the interface with sample data** shows a labelled demo without a
  machine.

## Spend

The Automagik meter ([`meter/`](../../meter/README.md)) counts the tokens your coding agents use
on each machine and publishes a small `herdr-meter` summary there. The app reads that summary from
each machine you're connected to and sums it. It never writes it. With no meter on any machine,
**Projects** shows one hint and no numbers.

- **Today:** **spent today**, from local midnight, across every reporting machine.
- **Projects:** each repository's $ over the last 24 hours, plus its average $ per task, task count
  and active hours per task over 30 days. A chart shows today's $ by hour for all projects together,
  because the meter's hourly series isn't split by project.
- **A project:** pick **24h**, **7d** or **30d** to see its tasks, cost per task (untasked work
  excluded), $ spent with tokens and the share read from cache, active hours per task, and its
  tasks by spend, finished ones included.
- **Tasks:** each branch's $ and active hours over 30 days.

What the numbers mean:

- Every $ is an **API-equivalent estimate at list price**, not an amount billed. The price
  table's date is in the footnote. A harness that reports its own $ (omp) counts as reported.
- **$ —** means the meter has tokens but no price for that model. Tokens still count.
- **≥ $x** is a lower bound. Either some tokens have no price, or some data is missing: the meter
  started counting inside the window, or it cut detail to keep its summary small.
- **—** means no reporting machine has usage for it.
- A task is a repository and branch. Work on a trunk branch, or with no branch, is untasked.
  **Active hours** count the hours with any usage, not wall-clock time.
- 24h, 7d and 30d end at each machine's last update.
- The line above the numbers names the machines that aren't counted: "2 of 3 machines reporting
  · build-box data 3 h old · no meter on laptop". A machine whose summary is from a newer meter
  than the app understands says **update the app or the meter**. Totals never drop a machine
  silently.

## Terminals

- An agent opens full screen. Tap **…** at the top right for navigation, tabs, the keyboard and the
  message composer. Android Back returns to the list.
- Scroll back to see history; new output doesn't move you. Tap **↓ Latest** to follow live output
  again. In full-screen programs, swipes scroll the program itself.
- **Keyboard button:** raw terminal input. **Chat button:** the composer. A composed message is
  kept as a draft until the core confirms the send, and it respects the core's protection for an
  agent that is waiting on a prompt. Answer interactive prompts with the keyboard and key row.
- **Key row:** Esc, sticky **Ctrl** and **Alt** (for the next key), Tab, Shift+Tab, arrows,
  Enter, Ctrl+C, Home/End, Page Up/Down, common symbols and Paste. Arrows and paging repeat while
  held. Cursor keys and paste follow the program's modes.
- Pinch with two fingers to change the text size, or set it in **Settings**. It's remembered.
- **Full color** (default) draws the terminal with a bundled, offline xterm.js. **Native text**, in
  Settings, draws the core's screen as plain text: no colours, up to 500 lines of shell history. The
  app switches to it on its own if the colour view fails.
- Web links in a terminal open in your browser.

## Alerts

- **Needs you** and **Done** are Android notifications, one per agent, in the app too. The agent
  on screen only sounds when it needs you.
- Tap one to open the agent. It goes away once the agent is answered elsewhere, starts working
  again, or you open it.
- **Reply** types into the agent; the result shows in the notification.
- When an agent asks for permission, the notification has **Yes** and **No**, never "always allow"
  or a mode change. When it asks a question, up to three of its answers. A button re-reads the
  agent first and presses keys only if the same question is still on screen; otherwise it says so
  and sends nothing.
- **Settings → Alerts:** sounds, Needs you and Done on or off, and **Answer from the lock screen**
  (off by default; see [privacy](../privacy.md)).

## Watch in background

- On by default. While a machine is connected, a "Watching N machines" notification keeps the
  connections open. **Stop** there disconnects.
- A dropped Pocket Link or key-based SSH connection comes back on its own. A password connection
  waits for you.
- In the background the app checks for changes less often and skips git checks.

## Side-button assistant

1. **Settings → Alerts → Side button assistant → Set up**, and make Automagik the phone's digital
   assistant.
2. Hold the side button, or use the assistant gesture: a panel opens over any screen, the lock
   screen too, with the agents that need you, then finished ones, and a message box.
3. Type, or tap the microphone to speak.

With **Answer from the lock screen** off, the panel asks you to unlock first. If the side button
stops opening it, for example after reinstalling, set the assistant again.

## Updates

- The app looks for a new version when it opens (at most every 15 minutes) and every 6 hours while
  it or **Watch in background** runs. **Settings → About → Check for updates** looks now.
- **Install updates automatically** (Settings → About, on by default): on Android 12 and newer the
  new version downloads on Wi-Fi or another unmetered network, its SHA-256 is checked against the
  release, and Android installs it without asking.
- It never installs under you. While a terminal is open on screen it waits; on any other screen it
  waits until the app has been left untouched for 10 minutes. Leave the app or turn the screen off
  and it installs at once.
- Android closes the app to update it. A quiet notification in the **Updates** channel then says
  **Updated to X** with the release's first note, and Settings → About shows the same line.
- If **Watch in background** was holding connections, the new version reconnects those machines
  on its own, whether the update came from GitHub or Google Play. A password connection waits for
  you.
- When Android won't install silently, a notification says **Automagik X is ready**, once per
  version. Tap **Install** to open Settings and Android's confirm prompt. This happens on Android
  11 and older, on a phone plan, with the switch off, until Automagik may install apps (the first
  tap asks), and when Android asks to confirm (for example when another app store owns the app's
  updates).

## Limits in detail

- The Pocket Link bridge runs as a LaunchAgent on macOS or a systemd user service on Linux.
- Connections live in the app; with Watch in background they stay open in a foreground service.
  After an update they come back on their own. After Android stops the service or the phone
  restarts, open the app and connect again.
- The SSH command assumes a Unix shell, so Windows hosts don't work.
- The app reads the desktop's layout. It doesn't edit, rename or delete it, and doesn't run
  desktop mods.
- Kitty images and Windows win32-input-mode aren't supported.
- herdr-core gives the last active viewer control of a terminal's size, so opening it on the phone
  can resize it on the desktop.
- The phone's keys are in app storage that is never backed up. There's no key backup and no
  hardware-backed SSH signing.
