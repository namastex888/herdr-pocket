// node --test bridge/presence.test.mjs: counting open sessions by phone, and public-status.json.
// Temp dirs only, never the real state dir; phone keys and names are made up.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { presence, publicStatus, writePublic, readPublic, FILE, SCHEMA, HEARTBEAT, STALE } from './presence.mjs'

const clock = (start = 1_000) => { let t = start; const now = () => t; now.tick = (ms) => { t += ms }; return now }

test('a session counts its phone from open to close, and closing twice changes nothing', () => {
  let changes = 0
  const now = clock()
  const p = presence({ changed: () => changes++, now })
  assert.deepEqual(p.phones(), [])
  const close = p.open('phone-key-a', 'Pixel 9')
  assert.deepEqual(p.phones(), [{ name: 'Pixel 9', since: 1_000 }])
  assert.equal(changes, 1)
  close()
  assert.deepEqual(p.phones(), [])
  assert.equal(changes, 2)
  close() // hc exit and the phone hanging up both close it
  assert.equal(changes, 2)
})

test('one phone with several sessions is one phone, until its last session closes', () => {
  const now = clock()
  const p = presence({ now })
  const first = p.open('phone-key-a', 'Pixel 9')
  now.tick(5_000)
  const second = p.open('phone-key-a', 'Pixel 9')
  now.tick(5_000)
  const other = p.open('phone-key-b', 'Galaxy S24')
  assert.deepEqual(p.phones(), [{ name: 'Pixel 9', since: 1_000 }, { name: 'Galaxy S24', since: 11_000 }])
  first()
  assert.deepEqual(p.phones(), [{ name: 'Pixel 9', since: 6_000 }, { name: 'Galaxy S24', since: 11_000 }], 'since is its oldest open session')
  second()
  assert.deepEqual(p.phones(), [{ name: 'Galaxy S24', since: 11_000 }])
  other()
  assert.deepEqual(p.phones(), [])
})

test('the public document has exactly the schema 1 fields, and nothing that reaches this computer', () => {
  const doc = publicStatus({ running: true, now: 42, phones: [{ name: 'Pixel 9', since: 7, phone: 'phone-key-a', ticket: 'endpointticket' }] })
  assert.deepEqual(doc, { schema: SCHEMA, running: true, updated: 42, phones_connected: 1, phones: [{ name: 'Pixel 9', since: 7 }] })
  assert.equal(SCHEMA, 1)
  assert.doesNotMatch(JSON.stringify(doc), /phone-key|ticket/)
})

test('stopped: running false, no phones', () => {
  assert.deepEqual(publicStatus({ running: false, now: 9, phones: [{ name: 'Pixel 9', since: 7 }] }),
    { schema: 1, running: false, updated: 9, phones_connected: 0, phones: [] })
})

test('writePublic: atomic, 0644 even under a strict umask, no temp file left', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pocket-presence-test-'))
  const before = process.umask(0o077)
  try {
    const path = writePublic(dir, publicStatus({ running: true, now: 5 }))
    assert.equal(path, join(dir, FILE))
    assert.equal(statSync(path).mode & 0o777, 0o644)
    assert.deepEqual(readdirSync(dir), [FILE])
    assert.equal(JSON.parse(readFileSync(path, 'utf8')).running, true)
    writePublic(dir, publicStatus({ running: false, now: 6 }))
    assert.deepEqual(JSON.parse(readFileSync(path, 'utf8')), { schema: 1, running: false, updated: 6, phones_connected: 0, phones: [] })
    assert.deepEqual(readdirSync(dir), [FILE])
  } finally { process.umask(before) }
})

test('readers: a file older than STALE, stopped, unknown or broken counts as no phone', () => {
  assert.ok(HEARTBEAT <= 30_000 && STALE >= 3 * HEARTBEAT, 'the heartbeat fits the staleness rule')
  const live = JSON.stringify(publicStatus({ running: true, now: 1_000, phones: [{ name: 'Pixel 9', since: 500 }] }))
  assert.equal(readPublic(live, 1_000 + STALE).phones_connected, 1)
  const stale = readPublic(live, 1_000 + STALE + 1)
  assert.equal(stale.running, false)
  assert.equal(stale.phones_connected, 0)
  assert.equal(readPublic(JSON.stringify(publicStatus({ running: false, now: 1_000 })), 1_000).running, false)
  assert.equal(readPublic(JSON.stringify({ schema: 2, running: true, updated: 1_000, phones_connected: 1 }), 1_000), null)
  assert.equal(readPublic('{"running": tr', 1_000), null)
  assert.equal(readPublic('', 1_000), null)
})
