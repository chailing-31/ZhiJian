import test from 'node:test'
import assert from 'node:assert/strict'
import { validId, searchBatches, eventStages, statusLabel, sourceLabel, traceUrl, validateTraceOrigin, isLoopback, moduleLocation, formatTime } from '../src/utils/batch.js'
import { modules } from '../src/config/modules.js'

test('six module entries have unique keys and routes', () => {
  assert.equal(modules.length, 6)
  assert.equal(new Set(modules.map(x => x.path)).size, 6)
  assert.equal(new Set(modules.map(x => x.key)).size, 6)
})
test('business modules remain pending; inspection is explicitly connected', () => {
  assert.equal(modules.find(m => m.key === 'inspection').status, '已接入')
  for (const key of ['processing', 'coldchain']) assert.equal(modules.find(m => m.key === key).tone, 'pending')
})
test('validId accepts a positive, safe database ID', () => { assert.equal(validId(1), '1'); assert.equal(validId('26'), '26') })
test('validId rejects unsafe IDs, arrays, zero and batch codes', () => {
  for (const id of ['0', '01', -1, '', null, undefined, '9007199254740993', 'APPLE-2026-001', ['1']]) assert.equal(validId(id), '')
})
test('module navigation keeps batch context', () => { assert.deepEqual(moduleLocation('/inspection', 7), { path: '/inspection', query: { batch_id: '7' } }) })
test('invalid batch context is not placed in a route', () => { assert.deepEqual(moduleLocation('/coldchain', 'wrong'), { path: '/coldchain', query: {} }) })
test('search includes supplied product, code and origin without modifying input', () => {
  const rows = [{ batch_code: 'APPLE-2026-001', product: '苹果', origin: '山东烟台' }, { batch_code: 'X-2', product: '梨' }]
  assert.equal(searchBatches(rows, ' apple ').length, 1)
  assert.equal(searchBatches(rows, '烟台').length, 1)
  assert.equal(searchBatches(rows, '').length, 2)
  assert.equal(rows.length, 2)
})
test('no saved events does not mark any stage complete', () => { assert.ok(eventStages([]).every(s => !s.count && s.state === '暂无记录')) })
test('an entry event only marks entry as recorded', () => { const stages = eventStages([{ event_type: '入厂' }]); assert.equal(stages[0].count, 1); assert.ok(stages.slice(1).every(s => !s.count)) })
test('unknown events do not imply a completed pipeline', () => { assert.ok(eventStages([{ event_type: '自定义状态' }]).every(s => !s.count)) })
test('existing status is translated, unknown statuses preserved', () => { assert.equal(statusLabel('created'), '已登记'); assert.equal(statusLabel('future-state'), 'future-state') })
test('simulation and demonstration origins are distinguishable', () => { assert.equal(sourceLabel('simulation'), '模拟数据'); assert.equal(sourceLabel('demo'), '演示数据'); assert.equal(sourceLabel(''), '来源未标注') })
test('timestamps and missing timestamps are handled', () => { assert.equal(formatTime(null), '时间未录入'); assert.equal(formatTime('oops'), '时间格式待核对'); assert.ok(formatTime('2026-09-24T09:00:00+08:00').includes('2026')) })
test('trace URL uses the actual batch code and origin', () => { assert.equal(traceUrl('http://192.168.1.6:5173/', 'APPLE-2026-001'), 'http://192.168.1.6:5173/trace/APPLE-2026-001') })
test('reject script URLs, credentials, query parameters and page paths', () => {
  for (const s of ['javascript:alert(1)', 'https://user:pass@example.com', 'http://localhost:5173/trace', 'http://localhost/?x=1', 'http://localhost/#x', 'not-a-url']) assert.throws(() => validateTraceOrigin(s))
})
test('invalid codes cannot become share URLs', () => { for (const code of ['', '../admin', 'A?x=1', '<script>', 'a'.repeat(65)]) assert.throws(() => traceUrl('http://localhost:5173', code)) })
test('loopback warning applies to desktop-only destinations', () => { assert.equal(isLoopback('http://localhost:5173'), true); assert.equal(isLoopback('http://127.0.0.1:5173'), true); assert.equal(isLoopback('http://[::1]:5173'), true); assert.equal(isLoopback('http://192.168.1.6:5173'), false) })
