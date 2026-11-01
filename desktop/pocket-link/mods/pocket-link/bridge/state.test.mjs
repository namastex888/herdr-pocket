// node --test bridge/state.test.mjs: where the state lives and the move out of <mod>/.state.
// Temp dirs only: every test passes its own env and home, never process.env or the real home.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, statSync, readdirSync, openSync, writeSync, closeSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { stateDir, open, NAME } from './state.mjs'

const mode = (p) => statSync(p).mode & 0o777

/** A fake home with a mod folder; `old` fills <mod>/.state with these files (name: [content, mode]). */
function sandbox(old = null) {
  const root = mkdtempSync(join(tmpdir(), 'pocket-state-test-'))
  const home = join(root, 'home')
  const modRoot = join(home, '.config/herdr-app/mods/pocket-link')
  mkdirSync(modRoot, { recursive: true })
  if (old) {
    mkdirSync(join(modRoot, '.state'), { mode: 0o700 })
    for (const [name, [content, m]] of Object.entries(old)) writeFileSync(join(modRoot, '.state', name), content, { mode: m })
  }
  const lines = []
  return { root, home, modRoot, lines, log: (l) => lines.push(l), dir: join(home, '.local/state', NAME) }
}

const OLD = {
  'secret.key': ['old-key-bytes', 0o600],
  'devices.json': ['[{"id":"phone-1","name":"Pixel"}]\n', 0o600],
  'status.json': ['{"pid":999999}\n', 0o600],
  'meter.json': ['{}\n', 0o600],
}

test('fresh: the default dir is under ~/.local/state, created 0700, nothing to move', () => {
  const s = sandbox()
  const r = open({ env: {}, home: s.home, modRoot: s.modRoot, log: s.log })
  assert.equal(r.dir, s.dir)
  assert.equal(r.target, s.dir)
  assert.equal(r.migrated, null)
  assert.equal(mode(s.dir), 0o700)
  assert.deepEqual(readdirSync(s.dir), [])
  assert.deepEqual(s.lines, [])
})

test('fresh: an existing dir with looser permissions is tightened to 0700', () => {
  const s = sandbox()
  mkdirSync(s.dir, { recursive: true, mode: 0o755 })
  open({ env: {}, home: s.home, modRoot: s.modRoot })
  assert.equal(mode(s.dir), 0o700)
})

test('migration: every file moves from <mod>/.state, secret.key stays 0600, one log line', () => {
  const s = sandbox({ ...OLD, 'qr-1.svg': ['<svg/>', 0o644] })
  const r = open({ env: {}, home: s.home, modRoot: s.modRoot, log: s.log, alive: () => false })
  assert.equal(r.dir, s.dir)
  assert.equal(readFileSync(join(s.dir, 'secret.key'), 'utf8'), 'old-key-bytes')
  assert.equal(mode(join(s.dir, 'secret.key')), 0o600)
  assert.equal(readFileSync(join(s.dir, 'devices.json'), 'utf8'), OLD['devices.json'][0])
  assert.equal(mode(join(s.dir, 'devices.json')), 0o600)
  assert.deepEqual(readdirSync(s.dir).sort(), ['devices.json', 'meter.json', 'qr-1.svg', 'secret.key', 'status.json'])
  assert.deepEqual(readdirSync(join(s.modRoot, '.state')), [], 'the old folder is left empty')
  assert.equal(s.lines.length, 1)
  assert.match(s.lines[0], /^state: moved 5 file\(s\) from .*\.state to .*automagik-pocket-link$/)
  // A second start finds the key and does nothing.
  const again = open({ env: {}, home: s.home, modRoot: s.modRoot, log: s.log })
  assert.equal(again.migrated, null)
  assert.equal(s.lines.length, 1)
})

test('migration: secret.key comes out 0600 even if the old one was looser', () => {
  const s = sandbox({ 'secret.key': ['k', 0o644] })
  open({ env: {}, home: s.home, modRoot: s.modRoot, alive: () => false })
  assert.equal(mode(join(s.dir, 'secret.key')), 0o600)
})

test('migration: an open file (launchd\'s bridge.log) keeps writing into the moved file', () => {
  const s = sandbox({ 'secret.key': ['k', 0o600], 'bridge.log': ['before\n', 0o600] })
  const fd = openSync(join(s.modRoot, '.state', 'bridge.log'), 'a')
  open({ env: {}, home: s.home, modRoot: s.modRoot, alive: () => false })
  writeSync(fd, 'after\n'); closeSync(fd)
  assert.equal(readFileSync(join(s.dir, 'bridge.log'), 'utf8'), 'before\nafter\n')
})

test('an existing key in the new dir is never overwritten, and nothing is moved', () => {
  const s = sandbox(OLD)
  mkdirSync(s.dir, { recursive: true })
  writeFileSync(join(s.dir, 'secret.key'), 'new-key-bytes', { mode: 0o600 })
  const r = open({ env: {}, home: s.home, modRoot: s.modRoot, log: s.log, alive: () => false })
  assert.equal(r.migrated, null)
  assert.equal(readFileSync(join(s.dir, 'secret.key'), 'utf8'), 'new-key-bytes')
  assert.equal(readFileSync(join(s.modRoot, '.state', 'secret.key'), 'utf8'), 'old-key-bytes', 'the old copy is left alone')
  assert.ok(!existsSync(join(s.dir, 'devices.json')))
})

test('a file already in the new dir is kept, the old one stays beside it', () => {
  const s = sandbox(OLD)
  mkdirSync(s.dir, { recursive: true })
  writeFileSync(join(s.dir, 'devices.json'), '[]\n', { mode: 0o600 })
  const r = open({ env: {}, home: s.home, modRoot: s.modRoot, log: s.log, alive: () => false })
  assert.deepEqual(r.migrated.kept, ['devices.json'])
  assert.equal(readFileSync(join(s.dir, 'devices.json'), 'utf8'), '[]\n')
  assert.equal(readFileSync(join(s.dir, 'secret.key'), 'utf8'), 'old-key-bytes')
  assert.ok(existsSync(join(s.modRoot, '.state', 'devices.json')))
  assert.match(s.lines[0], /kept devices\.json/)
})

test('while an older bridge still runs from <mod>/.state, this run uses it and moves nothing', () => {
  const s = sandbox(OLD)
  const r = open({ env: {}, home: s.home, modRoot: s.modRoot, log: s.log, alive: (pid) => pid === 999999 })
  assert.equal(r.legacy, true)
  assert.equal(r.dir, join(s.modRoot, '.state'))
  assert.equal(r.target, s.dir, 'the login service is still given the new dir')
  assert.ok(existsSync(join(s.modRoot, '.state', 'devices.json')))
  assert.ok(!existsSync(join(s.dir, 'secret.key')))
  // The old bridge is that very process (it is `serve` restarting): migrate.
  const self = open({ env: {}, home: s.home, modRoot: s.modRoot, log: s.log, pid: 999999, alive: () => true })
  assert.equal(self.dir, s.dir)
  assert.ok(existsSync(join(s.dir, 'secret.key')))
})

test('XDG_STATE_HOME moves the dir; a relative one is ignored', () => {
  const s = sandbox()
  const xdg = join(s.root, 'xdg')
  assert.equal(stateDir({ env: { XDG_STATE_HOME: xdg }, home: s.home }), join(xdg, NAME))
  assert.equal(stateDir({ env: { XDG_STATE_HOME: 'rel/state' }, home: s.home }), s.dir)
  const r = open({ env: { XDG_STATE_HOME: xdg }, home: s.home, modRoot: s.modRoot })
  assert.equal(r.dir, join(xdg, NAME))
  assert.equal(mode(r.dir), 0o700)
})

test('HERDR_APP_HOME: another home gets its own dir; the default home is the default dir', () => {
  const s = sandbox()
  const other = stateDir({ env: { HERDR_APP_HOME: join(s.root, 'test-home') }, home: s.home })
  assert.match(other, new RegExp(`/${NAME}-[0-9a-f]{8}$`))
  assert.notEqual(other, s.dir)
  assert.equal(other, stateDir({ env: { HERDR_APP_HOME: join(s.root, 'test-home/') }, home: s.home }), 'stable')
  assert.equal(stateDir({ env: { HERDR_APP_HOME: join(s.home, '.config/herdr-app/') }, home: s.home }), s.dir)
})

test('POCKET_LINK_STATE_DIR wins over the rest (the login service gets it)', () => {
  const s = sandbox(OLD)
  const forced = join(s.root, 'forced')
  const env = { POCKET_LINK_STATE_DIR: forced, XDG_STATE_HOME: join(s.root, 'xdg'), HERDR_APP_HOME: join(s.root, 'h') }
  assert.equal(stateDir({ env, home: s.home }), forced)
  const r = open({ env, home: s.home, modRoot: s.modRoot, alive: () => false })
  assert.equal(r.dir, forced)
  assert.equal(readFileSync(join(forced, 'secret.key'), 'utf8'), 'old-key-bytes')
  assert.ok(!existsSync(s.dir), 'the default dir is never made')
})
