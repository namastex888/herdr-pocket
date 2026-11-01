// node --test bridge/meter.test.mjs: the install script, hc exec decoding and the loop, against
// a fake curl and a fake hc. Synthetic data only; nothing touches the network or the real home.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, readFileSync, existsSync, chmodSync, mkdirSync, statSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { INSTALL, SYNC, ASSETS, parseSums, failing, hcExec, meterOne, startMeter, SHOW_AFTER } from './meter.mjs'

const sha = (b) => createHash('sha256').update(b).digest('hex')

/** A home with a fake curl that "downloads" `payload` and logs its arguments. */
function sandbox(payload) {
  const dir = mkdtempSync(join(tmpdir(), 'meter-test-'))
  const home = join(dir, 'home'); mkdirSync(home)
  const bin = join(dir, 'bin'); mkdirSync(bin)
  writeFileSync(join(dir, 'payload'), payload)
  writeFileSync(join(bin, 'curl'), `#!/bin/sh\necho "$@" >> "${dir}/curl.log"\nwhile [ $# -gt 0 ]; do [ "$1" = -o ] && cp "${dir}/payload" "$2"; shift; done\n`)
  chmodSync(join(bin, 'curl'), 0o755)
  const env = { HOME: home, PATH: `${bin}:/usr/bin:/bin` }
  const run = (want) => {
    try {
      return { code: 0, out: execFileSync('/bin/sh', ['-c', INSTALL, 'sh', 'https://example.invalid/automagik-meter-x', want], { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim() }
    } catch (e) { return { code: e.status, out: String(e.stderr).trim() } }
  }
  return { dir, home, run, dest: join(home, '.local/bin/automagik-meter') }
}

test('install verifies the hash before the binary is used, and only then replaces it', () => {
  const good = Buffer.from('#!/bin/sh\necho meter 0.0.0\n')
  const s = sandbox(good)
  assert.deepEqual(s.run(sha(good)), { code: 0, out: 'installed' })
  assert.equal(readFileSync(s.dest, 'utf8'), good.toString())
  assert.equal(statSync(s.dest).mode & 0o777, 0o755)
  assert.deepEqual(s.run(sha(good)), { code: 0, out: 'current' }, 'no download when it matches')
  assert.equal(readFileSync(join(s.dir, 'curl.log'), 'utf8').trim().split('\n').length, 1)
  assert.match(readFileSync(join(s.dir, 'curl.log'), 'utf8'), /--proto =https/)

  // A tampered download is refused and the installed binary stays.
  writeFileSync(join(s.dir, 'payload'), 'not the release')
  const r = s.run(sha(Buffer.from('a newer release')))
  assert.equal(r.code, 3)
  assert.match(r.out, /checksum mismatch/)
  assert.equal(readFileSync(s.dest, 'utf8'), good.toString())
})

test('install on a machine without curl says so', () => {
  const s = sandbox('x')
  const env = { HOME: s.home, PATH: '/nonexistent' }
  let code = 0, err = ''
  try { execFileSync('/bin/sh', ['-c', INSTALL, 'sh', 'https://example.invalid/a', sha('y')], { env, stdio: ['ignore', 'pipe', 'pipe'] }) } catch (e) { code = e.status; err = String(e.stderr) }
  // sha256sum/shasum may be missing too on this PATH; either way nothing is installed.
  assert.notEqual(code, 0, err)
  assert.ok(!existsSync(s.dest))
})

test('sync refuses to run a missing binary', () => {
  const s = sandbox('x')
  let code = 0
  try { execFileSync('/bin/sh', ['-c', SYNC], { env: { HOME: s.home, PATH: '/usr/bin:/bin' }, stdio: 'ignore' }) } catch (e) { code = e.status }
  assert.equal(code, 5)
})

test('release sums parse only meter assets with full hashes', () => {
  const h = 'a'.repeat(64)
  const sums = parseSums(`${h}  automagik-meter-linux-x86_64\n${h} *automagik-meter-darwin-arm64\nbad  automagik-meter-x\n${h}  ../../etc/passwd\n`)
  assert.deepEqual(Object.keys(sums).sort(), ['automagik-meter-darwin-arm64', 'automagik-meter-linux-x86_64'])
  for (const a of Object.values(ASSETS)) assert.match(a, /^automagik-meter-(darwin|linux)-(arm64|x86_64|aarch64)$/)
})

/** A fake hc: answers `exec` from a script of (argv → result), logs every call. */
function fakeHc(dir, answers) {
  const p = join(dir, 'hc')
  writeFileSync(join(dir, 'answers.json'), JSON.stringify(answers))
  writeFileSync(p, `#!/usr/bin/env node
const fs = require('fs'); const a = process.argv.slice(2)
fs.appendFileSync(${JSON.stringify(join(dir, 'hc.log'))}, JSON.stringify(a) + '\\n')
if (a[0] === 'machine') { console.log(JSON.stringify({ machines: [{ name: 'box', state: 'connected' }, { name: 'off', state: 'disconnected' }] })); process.exit(0) }
const argv = a.slice(a.indexOf('--') + 1); const on = a.includes('--on') ? a[a.indexOf('--on') + 1] : 'here'
const answers = JSON.parse(fs.readFileSync(${JSON.stringify(join(dir, 'answers.json'))}, 'utf8'))
const key = on + ' ' + (argv[0] === 'sh' ? (argv[2].includes('curl') ? 'install' : 'sync') : argv.join(' '))
const r = answers[key] ?? { code: 0, stdout: '' }
const b = (s) => Buffer.from(s ?? '').toString('base64')
console.log(JSON.stringify({ code: r.code, stdout: b(r.stdout), stderr: b(r.stderr), timed_out: !!r.timed_out }))
process.exit(r.code === 0 ? 0 : 1)
`)
  chmodSync(p, 0o755)
  return p
}

test('hc exec output is decoded, a timeout is an error', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'meter-hc-'))
  const hc = fakeHc(dir, { 'box uname -sm': { code: 0, stdout: 'Linux x86_64\n' }, 'box sync': { code: 0, timed_out: true } })
  assert.deepEqual(await hcExec(hc, 'box', ['uname', '-sm'], 5), { code: 0, stdout: 'Linux x86_64\n', stderr: '' })
  assert.equal((await hcExec(hc, 'box', ['sh', '-c', SYNC], 5)).code, -1)
  assert.equal((await hcExec('/nonexistent/hc', null, ['true'], 5)).code, -1, 'a missing hc never throws')
})

test('a machine is installed from the release, then synced; waits while there is no release', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'meter-one-'))
  const hc = fakeHc(dir, {
    'box uname -sm': { code: 0, stdout: 'Linux aarch64\n' },
    'box install': { code: 0, stdout: 'installed\n' },
    'here uname -sm': { code: 0, stdout: 'Darwin arm64\n' },
    'here sync': { code: 5, stderr: 'automagik-meter is not installed' },
  })
  const sums = { 'automagik-meter-linux-aarch64': 'b'.repeat(64) }
  assert.deepEqual(await meterOne(hc, 'box', sums), { ok: true, installed: 'installed', skipped: null })
  const log = readFileSync(join(dir, 'hc.log'), 'utf8')
  assert.ok(log.includes('https://github.com/automagik-dev/automagik-pocket/releases/latest/download/automagik-meter-linux-aarch64'))
  assert.ok(log.includes('b'.repeat(64)))
  // No darwin asset in this release: nothing to install, and not installed is not an error.
  assert.equal((await meterOne(hc, null, sums)).ok, true)
  // A platform with no build is an error worth showing.
  assert.match((await meterOne(hc, 'elsewhere', sums)).error, /no meter build/)
})

test('the loop runs one pass at a time and counts failures in a row', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'meter-loop-'))
  const hc = fakeHc(dir, { 'box uname -sm': { code: 1, stderr: 'machine unreachable' }, 'here uname -sm': { code: 0, stdout: 'Linux x86_64\n' } })
  let state = {}
  const m = startMeter({ hc, read: () => structuredClone(state), write: (s) => { state = structuredClone(s) }, log: () => {} })
  try {
    const [a, b] = await Promise.all([m.pass(), m.pass()])
    assert.ok(b.skipped, 'a pass while one runs is skipped')
    assert.equal(a.machines.box.failures, 1)
    assert.ok(!('off' in a.machines), 'disconnected machines are left alone')
    for (let i = 1; i < SHOW_AFTER; i++) await m.pass()
    assert.deepEqual(failing(state).map((f) => f.machine), ['box'])
    assert.match(failing(state)[0].error, /unreachable/)
    assert.equal(state.machines['this computer'].failures, 0)
  } finally { m.stop() }
})
