import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parseColdchainCsv } from '../src/utils/coldchain-csv.js'
const header = 'timestamp,temperature,humidity,source,door_open,equipment_current\n'
const point = '2026-10-12T10:00:00+08:00,12,80,simulation,false,\n'

test('Chinese template example maps to the API reading fields', async () => {
  const csv = await readFile(new URL('../public/templates/coldchain-template.csv', import.meta.url), 'utf8')
  const rows = parseColdchainCsv(csv)
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0], { timestamp: '2026-10-03T10:00:00+08:00', temperature: 4.2, humidity: 85, source: 'simulation', door_open: false, equipment_current: 2.1 })
})

test('Chinese aliases reject duplicate fields and preserve source time validation', () => {
  const chinese = '读数时间,温度（℃）,湿度（%）,数据来源,门开启状态\n'
  const row = '2026-10-03T10:00:00+08:00,4.2,85,传感器采集,开启\n'
  assert.equal(parseColdchainCsv(chinese + row)[0].door_open, true)
  assert.equal(parseColdchainCsv(chinese + row)[0].source, 'sensor')
  assert.throws(() => parseColdchainCsv(chinese.replace('门开启状态', 'temperature') + row), /重复/)
  assert.throws(() => parseColdchainCsv(chinese + row + row.replace('传感器采集', 'sensor')), /严格递增/)
  assert.throws(() => parseColdchainCsv(chinese + row.replace('开启', '未知')), /door_open/)
})
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
