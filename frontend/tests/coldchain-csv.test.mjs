import test from 'node:test'
import assert from 'node:assert/strict'
import { parseColdchainCsv } from '../src/utils/coldchain-csv.js'
const header = 'timestamp,temperature,humidity,source,door_open,equipment_current\n'
const point = '2026-10-12T10:00:00+08:00,12,80,simulation,false,\n'
test('CSV accepts BOM, quotes, CRLF and optional empty current', () => {
  const rows = parseColdchainCsv('\uFEFF' + header.replace('\n','\r\n') + point.replace(',12,',',"12",').replace('\n','\r\n'))
  assert.equal(rows[0].temperature,12); assert.equal(rows[0].door_open,false); assert.equal('equipment_current' in rows[0],false)
})
test('CSV rejects empty required numbers and invalid doors instead of coercing them', () => {
  assert.throws(() => parseColdchainCsv(header + point.replace(',12,',',,')), /有限数值/)
  assert.throws(() => parseColdchainCsv(header + point.replace('false','maybe')), /door_open/)
})
test('CSV rejects duplicate times, malformed quoting, unknown columns and header-only input', () => {
  assert.throws(() => parseColdchainCsv(header + point + point), /严格递增/)
  assert.throws(() => parseColdchainCsv(header + '"unfinished'), /未闭合/)
  assert.throws(() => parseColdchainCsv(header.replace('source','unexpected') + point), /未知列/)
  assert.throws(() => parseColdchainCsv(header), /至少一条/)
})
