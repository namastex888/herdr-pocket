#!/usr/bin/env node
// Pocket Link bridge: lets paired phones reach this machine's herdr-core over iroh
// (dial by key, direct when it can, an end-to-end encrypted relay when it can't).
//
//   node bridge.mjs serve            run the bridge (the mod starts it; `install` keeps it running at login)
//   node bridge.mjs pair [minutes]   make a one-time pairing link and QR, print {link, expires, rows}
//   node bridge.mjs status           print {running, id, ticket, devices, pairing, login, state}
//   node bridge.mjs start | stop     start the bridge detached; stop it (and its login service)
//   node bridge.mjs forget <id>      remove a paired phone
//   node bridge.mjs install|uninstall   start at login (LaunchAgent on macOS, systemd user unit on Linux)
//   node bridge.mjs meter [machine]  install/update and run the usage meter once, on every machine
//                                    (or on this computer and that machine)
//                                    (`serve` does it 10 minutes after each pass; see meter.mjs)
//
// State lives outside the mod folder (see state.mjs), so reinstalling the mod keeps the pairings.
import { Endpoint, EndpointTicket, SecretKey } from '@number0/iroh'
import { spawn, execFileSync } from 'node:child_process'
import { randomBytes, timingSafeEqual, createHash } from 'node:crypto'
import { promises as fs, existsSync, readFileSync, writeFileSync, mkdirSync, renameSync, chmodSync, unlinkSync, readdirSync, openSync } from 'node:fs'
import { homedir, hostname, platform } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import QRCode from 'qrcode'
import { startMeter, failing } from './meter.mjs'
import { open as openState } from './state.mjs'
import { presence, publicStatus, writePublic, HEARTBEAT } from './presence.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SESSION = [...Buffer.from('herdr-pocket/1')]
const PAIR = [...Buffer.from('herdr-pocket/pair/1')]
const MAX_LINE = 4096
const LABEL = 'dev.herdr.pocket-link'

// As a service, stderr already is the log file; in a terminal, write both.
let files = null
const log = (...args) => { const line = `${new Date().toISOString()} ${args.join(' ')}\n`; process.stderr.write(line); if (process.stderr.isTTY && files) fs.appendFile(files.log, line, { mode: 0o600 }).catch(() => {}) }
// Outside the mod folder (state.mjs); the first run moves an older <mod>/.state there.
// `dir` is the old one only while an older bridge still runs from it; `target` is where it belongs.
const { dir: STATE, target: STATE_HOME } = openState({ env: process.env, home: homedir(), modRoot: join(HERE, '..'), log })
files = {
  key: join(STATE, 'secret.key'), status: join(STATE, 'status.json'), devices: join(STATE, 'devices.json'),
  pairing: join(STATE, 'pairing.json'), log: join(STATE, 'bridge.log'), meter: join(STATE, 'meter.json'),
}
const readJson = (path, fallback) => { try { return JSON.parse(readFileSync(path, 'utf8')) } catch { return fallback } }
// Atomic, owner-only writes: a reader never sees half a file.
const writeJson = (path, value) => { const tmp = `${path}.${process.pid}.tmp`; writeFileSync(tmp, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 }); renameSync(tmp, path) }
const devices = () => readJson(files.devices, [])
// Older versions wrote QR files here; clean them up.
const clearQr = () => { for (const f of readdirSync(STATE)) if (/^qr-\d+\.svg$/.test(f)) try { unlinkSync(join(STATE, f)) } catch {} }
const alive = pid => { try { process.kill(pid, 0); return true } catch { return false } }

function secretKey() {
  if (!existsSync(files.key)) writeFileSync(files.key, Buffer.from(SecretKey.generate().toBytes()), { mode: 0o600 })
  chmodSync(files.key, 0o600)
  return [...readFileSync(files.key)]
}

/** hc as the desktop finds it: HC, PATH, then the usual install places (a login service has a short PATH). */
function hcPath() {
  const candidates = [process.env.HC, join(homedir(), '.local/bin/hc'), '/opt/homebrew/bin/hc', '/usr/local/bin/hc'].filter(Boolean)
  try { candidates.unshift(execFileSync('/bin/sh', ['-lc', 'command -v hc || command -v herdr-core'], { encoding: 'utf8' }).trim().split('\n')[0]) } catch {}
  return candidates.find(p => p && existsSync(p)) ?? 'hc'
}

/** One line of JSON from a stream, at most MAX_LINE bytes; the rest of what was read comes back too. */
async function readLine(recv) {
  let buf = Buffer.alloc(0)
  while (!buf.includes(10)) {
    if (buf.length > MAX_LINE) throw new Error('header too long')
    const chunk = await recv.read(MAX_LINE)
    if (!chunk || chunk.length === 0) throw new Error('closed before its header')
    buf = Buffer.concat([buf, Buffer.from(chunk)])
  }
  const end = buf.indexOf(10)
  return { value: JSON.parse(buf.subarray(0, end).toString('utf8')), rest: buf.subarray(end + 1) }
}

const sameCode = (a, b) => { const x = createHash('sha256').update(String(a)).digest(), y = createHash('sha256').update(String(b)).digest(); return timingSafeEqual(x, y) }

async function pairPhone(conn, bi) {
  const phone = conn.remoteId().toString()
  const reply = value => bi.send.writeAll([...Buffer.from(JSON.stringify(value) + '\n')]).then(() => bi.send.finish())
  const { value } = await readLine(bi.recv)
  const pending = readJson(files.pairing, null)
  if (!pending || Date.now() > pending.expires) return reply({ ok: false, error: 'This pairing code was already used or has expired. Make a new one on the desktop.' })
  if (!sameCode(value.code, pending.code)) {
    // Five wrong tries end the code: guessing needs a new one each time.
    pending.failures = (pending.failures ?? 0) + 1
    if (pending.failures >= 5) unlinkSync(files.pairing); else writeJson(files.pairing, pending)
    log('pair: wrong code from', phone.slice(0, 10))
    return reply({ ok: false, error: 'That code is not the one on the desktop.' })
  }
  unlinkSync(files.pairing)
  const name = String(value.device ?? 'Phone').replace(/[\u0000-\u001f]/g, '').slice(0, 60) || 'Phone'
  writeJson(files.devices, [...devices().filter(d => d.id !== phone), { id: phone, name, paired: Date.now(), seen: Date.now() }])
  clearQr()
  log('pair: added', name, phone.slice(0, 10))
  return reply({ ok: true, name: pending.name })
}

// Sessions now open, so removing a phone also ends what it has open.
const live = new Set()
// The phones those sessions belong to, for public-status.json (presence.mjs), the file other mods read.
let serving = false
const publishPublic = () => { try { writePublic(STATE, publicStatus({ running: serving, phones: phones.phones() })) } catch (e) { log('public status:', e.message) } }
const phones = presence({ changed: () => { if (serving) publishPublic() } })
function revoke() {
  const ids = new Set(devices().map(d => d.id))
  for (const s of live) if (!ids.has(s.phone)) {
    live.delete(s); log('session: ended for a removed phone', s.phone.slice(0, 10))
    s.hc.kill(); try { s.conn.close(1n, [...Buffer.from('not paired')]) } catch {}
  }
}

async function session(conn, bi) {
  const phone = conn.remoteId().toString()
  const device = devices().find(d => d.id === phone)
  if (!device) { log('session: refused unpaired', phone.slice(0, 10)); conn.close(1n, [...Buffer.from('not paired')]); return }
  const { value, rest } = await readLine(bi.recv)
  const name = typeof value.session === 'string' && /^[A-Za-z0-9_.-]{1,64}$/.test(value.session) ? value.session : 'default'
  // Read again: a phone removed meanwhile must stay removed.
  writeJson(files.devices, devices().map(d => d.id === phone ? { ...d, seen: Date.now() } : d))
  log('session:', device.name, 'session', name)
  const hc = spawn(hcPath(), ['connect'], { stdio: ['pipe', 'pipe', 'ignore'], env: { ...process.env, HC_SESSION: name } })
  if (rest.length) hc.stdin.write(rest)
  const open = { phone, conn, hc }
  live.add(open)
  // Counted from here until the phone hangs up or hc exits (a removed phone's hc is killed), whichever comes first.
  const close = phones.open(phone, device.name)
  hc.on('exit', () => { live.delete(open); close() })
  let writing = Promise.resolve()
  hc.stdout.on('data', chunk => {
    hc.stdout.pause()
    writing = writing.then(() => bi.send.writeAll([...chunk])).then(() => hc.stdout.resume(), () => hc.kill())
  })
  hc.on('exit', () => { writing.then(() => bi.send.finish()).catch(() => {}) })
  hc.on('error', e => { log('session: hc failed:', e.message); conn.close(2n, [...Buffer.from('hc failed')]) })
  try {
    for (;;) {
      const data = await bi.recv.read(65536).catch(() => null)
      if (!data || data.length === 0) break
      if (!hc.stdin.write(Buffer.from(data))) await new Promise(r => hc.stdin.once('drain', r))
    }
    hc.stdin.end()
  } finally { close() }
}

// The usage meter on this computer and every connected machine (meter.mjs). Its state is
// meter.json in the state dir; the dialog shows only machines that keep failing.
const meterLoop = (only = null) => startMeter({ hc: hcPath(), read: () => readJson(files.meter, {}), write: s => writeJson(files.meter, s), log, only })

async function serve() {
  const other = readJson(files.status, {})
  if (other.pid && other.pid !== process.pid && alive(other.pid)) { log('serve: already running as', other.pid); process.exit(0) }
  const ep = await Endpoint.bind({ secretKey: secretKey(), alpns: [SESSION, PAIR] })
  const publish = () => writeJson(files.status, { pid: process.pid, id: ep.id().toString(), ticket: EndpointTicket.fromAddr(ep.addr()).toString(),
    name: hostname().replace(/\.local$/, ''), started: Date.now() })
  publish()
  ep.online().then(() => { publish(); log('serve: online as', ep.id().fmtShort()) }, e => log('serve: no relay yet:', e.message))
  ep.watchAddr(() => publish())
  const meter = meterLoop()
  const stop = async () => { meter.stop(); serving = false; publishPublic(); try { const s = readJson(files.status, {}); if (s.pid === process.pid) unlinkSync(files.status) } catch {}; await ep.close().catch(() => {}); process.exit(0) }
  process.on('SIGTERM', stop); process.on('SIGINT', stop)
  // public-status.json: running from here (a stop now writes running: false), then `updated` every
  // HEARTBEAT and on each session open and close.
  serving = true; publishPublic()
  setInterval(publishPublic, HEARTBEAT).unref()
  setInterval(revoke, 2000).unref()
  for (;;) {
    const incoming = await ep.acceptNext()
    if (!incoming) break
    ;(async () => {
      const accepting = await incoming.accept()
      const alpn = Buffer.from(await accepting.alpn()).toString()
      const conn = await accepting.connect()
      const bi = await conn.acceptBi()
      if (alpn === 'herdr-pocket/pair/1') await pairPhone(conn, bi)
      else await session(conn, bi)
    })().catch(e => log('connection:', e.message))
  }
  serving = false; publishPublic()
}

async function pair(minutes = 10) {
  const status = readJson(files.status, null)
  if (!status?.ticket || !alive(status.pid)) throw new Error('The bridge is not running.')
  const code = randomBytes(16).toString('base64url')
  const expires = Date.now() + Math.max(1, Math.min(60, Number(minutes) || 10)) * 60_000
  writeJson(files.pairing, { code, expires, name: status.name })
  const link = `herdr-pocket://pair?v=1&n=${encodeURIComponent(status.name)}&c=${code}&t=${status.ticket}`
  clearQr()
  // The QR as rows of "1" (dark) and "0" (light) modules, for the mod to draw.
  const { modules } = QRCode.create(link, { errorCorrectionLevel: 'M' })
  const rows = Array.from({ length: modules.size }, (_, y) => Array.from({ length: modules.size }, (_, x) => modules.get(y, x) ? '1' : '0').join(''))
  return { link, expires, rows }
}

// Homebrew's versioned Cellar path disappears on the next upgrade; its opt link stays.
function nodePath() {
  const m = process.execPath.match(/^(.*)\/Cellar\/([^/]+)\/[^/]+\/bin\/node$/)
  const opt = m && `${m[1]}/opt/${m[2]}/bin/node`
  return opt && existsSync(opt) ? opt : process.execPath
}

function loginService() {
  if (platform() === 'darwin') {
    const plist = join(homedir(), 'Library/LaunchAgents', `${LABEL}.plist`)
    return { path: plist, body: `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key><array><string>${nodePath()}</string><string>${join(HERE, 'bridge.mjs')}</string><string>serve</string></array>
  <key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
  <key>EnvironmentVariables</key><dict><key>POCKET_LINK_STATE_DIR</key><string>${STATE_HOME}</string></dict>
  <key>StandardErrorPath</key><string>${join(STATE_HOME, 'bridge.log')}</string>
</dict></plist>
`, on: () => {
      // A bootout finishes in the background: bootstrap again until launchd lets go of the old one.
      try { execFileSync('launchctl', ['bootout', `gui/${process.getuid()}/${LABEL}`], { stdio: 'ignore' }) } catch {}
      for (let i = 0; ; i++) {
        try { execFileSync('launchctl', ['bootstrap', `gui/${process.getuid()}`, plist], { stdio: 'pipe' }); return } catch (e) {
          if (i >= 15) throw e
          Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 300)
        }
      }
    },
      off: () => { try { execFileSync('launchctl', ['bootout', `gui/${process.getuid()}/${LABEL}`], { stdio: 'ignore' }) } catch {} } }
  }
  const unit = join(homedir(), '.config/systemd/user', 'herdr-pocket-link.service')
  return { path: unit, body: `[Unit]
Description=Herdr Pocket Link bridge

[Service]
ExecStart=${nodePath()} ${join(HERE, 'bridge.mjs')} serve
Environment="POCKET_LINK_STATE_DIR=${STATE_HOME}"
Restart=on-failure

[Install]
WantedBy=default.target
`, on: () => { execFileSync('systemctl', ['--user', 'daemon-reload']); execFileSync('systemctl', ['--user', 'enable', '--now', 'herdr-pocket-link.service']) },
    off: () => { try { execFileSync('systemctl', ['--user', 'disable', '--now', 'herdr-pocket-link.service'], { stdio: 'ignore' }) } catch {} } }
}

const [command = 'status', arg] = process.argv.slice(2)
const out = value => process.stdout.write(JSON.stringify(value) + '\n')
try {
  if (command === 'serve') await serve()
  else if (command === 'pair') out(await pair(arg))
  else if (command === 'status') {
    const s = readJson(files.status, {})
    const p = readJson(files.pairing, null)
    out({ running: Boolean(s.pid && alive(s.pid)), id: s.id ?? null, name: s.name ?? null, ticket: s.ticket ?? null,
      devices: devices().map(({ id, name, paired, seen }) => ({ id, name, paired, seen })),
      pairing: p && Date.now() < p.expires ? { expires: p.expires } : null, login: existsSync(loginService().path),
      meter: { failing: failing(readJson(files.meter, {})) }, state: STATE })
  }
  else if (command === 'meter') { const m = meterLoop(arg ?? null); const s = await m.pass(); m.stop(); out(s) }
  else if (command === 'start') {
    // Without the login service: a detached bridge that lives until stopped or logout.
    const s = readJson(files.status, {})
    if (!(s.pid && alive(s.pid))) {
      const err = openSync(files.log, 'a', 0o600)
      spawn(process.execPath, [join(HERE, 'bridge.mjs'), 'serve'], { detached: true, stdio: ['ignore', 'ignore', err] }).unref()
    }
    out({ ok: true })
  }
  else if (command === 'stop') {
    const s = loginService(); s.off(); try { unlinkSync(s.path) } catch {}
    const st = readJson(files.status, {})
    if (st.pid && alive(st.pid)) try { process.kill(st.pid) } catch {}
    try { unlinkSync(files.pairing) } catch {}; clearQr()
    out({ ok: true })
  }
  else if (command === 'forget') { writeJson(files.devices, devices().filter(d => d.id !== arg)); out({ ok: true }) }
  else if (command === 'install') { const s = loginService(); mkdirSync(dirname(s.path), { recursive: true }); writeFileSync(s.path, s.body); s.on(); out({ ok: true, path: s.path }) }
  else if (command === 'uninstall') { const s = loginService(); s.off(); try { unlinkSync(s.path) } catch {}; out({ ok: true }) }
  else { out({ ok: false, error: `unknown command ${command}` }); process.exit(2) }
} catch (e) { out({ ok: false, error: e.message }); process.exit(1) }
