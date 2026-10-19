import test from 'node:test'
import assert from 'node:assert/strict'
import { request, ApiError } from '../src/api/http.js'
import { getBatch, listBatches, createBatch } from '../src/api/batch.js'
import { getPublicTrace } from '../src/api/trace.js'
import { requestAdvice, saveAdoptedValues, getProcessingHistory, getProcessingTrace } from '../src/api/processing.js'
import { importReadings, resolveAlert } from '../src/api/coldchain.js'
import { getFormalReport } from '../src/api/report.js'

async function withFetch(fn, action) {
  const previous = globalThis.fetch
  globalThis.fetch = fn
  try { await action() } finally { globalThis.fetch = previous }
}

test('GET batches uses /api proxy and preserves response array', () => withFetch(async (url, options) => {
  assert.equal(url, '/api/batches'); assert.equal(options.method, 'GET')
  return Response.json([{ batch_id: 7, batch_code: 'TEST-7' }])
}, async () => { assert.equal((await listBatches())[0].batch_id, 7) }))

test('invalid list response is an error, never fake empty success', () => withFetch(async () => Response.json({}), async () => { await assert.rejects(listBatches(), ApiError) }))
test('detail uses database ID rather than public code', () => withFetch(async url => { assert.equal(url, '/api/batches/7'); return Response.json({ batch_id: 7 }) }, async () => { assert.equal((await getBatch(7)).batch_id, 7) }))
test('invalid ID never reaches the server', () => withFetch(() => { throw new Error('should not fetch') }, async () => { await assert.rejects(getBatch('APPLE-2026-001'), ApiError) }))

test('processing trace scopes the record to its batch and forwards cancellation', () => {
  const controller = new AbortController()
  return withFetch(async (url, options) => {
    assert.equal(url, '/api/batches/7/processing-records/3/trace')
    controller.abort()
    assert.equal(options.signal.aborted, true)
    throw new DOMException('aborted', 'AbortError')
  }, async () => { await assert.rejects(getProcessingTrace(7, 3, { signal: controller.signal }), e => e.name === 'AbortError') })
})

test('processing trace rejects invalid batch and record IDs before fetching', () => withFetch(() => { throw new Error('should not fetch') }, async () => {
  await assert.rejects(getProcessingTrace('APPLE-2026-001', 3), ApiError)
  await assert.rejects(getProcessingTrace(7, '../3'), ApiError)
}))

test('public view only asks the public trace endpoint and preserves public summary', () => withFetch(async url => {
  assert.equal(url, '/api/trace/APPLE-2026-001')
  return Response.json({
    batch_code: 'APPLE-2026-001',
    public_summary: {
      inspection: { record_count: 1, state: 'recorded' },
    },
    events: [],
  })
}, async () => {
  const data = await getPublicTrace('APPLE-2026-001')
  assert.equal(data.events.length, 0)
  assert.equal(data.public_summary.inspection.record_count, 1)
}))

test('create uses POST with JSON and no client-generated ID', () => withFetch(async (url, options) => {
  assert.equal(url, '/api/batches'); assert.equal(options.method, 'POST')
  assert.deepEqual(JSON.parse(options.body), { batch_code: 'TEST-8', product: '苹果' })
  return Response.json({ batch_id: 8, batch_code: 'TEST-8' })
}, async () => { assert.equal((await createBatch({ batch_code: 'TEST-8', product: '苹果' })).batch_id, 8) }))

test('formal report uses numeric batch ID through /api proxy', () => withFetch(async (url, options) => {
  assert.equal(url, '/api/batches/7/report'); assert.equal(options.method, 'GET')
  return Response.json({ schema_version: 'zhijian.batch.report.v1', batch: { batch_id: 7 } })
}, async () => { assert.equal((await getFormalReport(7)).batch.batch_id, 7) }))

test('formal report rejects public batch code as internal ID', () => withFetch(() => { throw new Error('should not fetch') }, async () => { await assert.rejects(getFormalReport('APPLE-2026-001'), ApiError) }))

test('batch creation conflict retains its duplicate fallback without leaking raw response', () => withFetch(async () => new Response('sensitive details', { status: 409 }), async () => { await assert.rejects(createBatch({}), e => e.status === 409 && e.message.includes('已存在') && !e.message.includes('sensitive')) }))
for (const [code, message] of [['COLDCHAIN_REVIEW_REQUIRED', '已有冷链异常，请先核查。'], ['ASSESSMENT_STALE', '质检或等级已更新，请重新生成建议。'], ['ADOPTION_UPDATED', '采用参数已更新，请刷新核对。']]) {
  test(`processing preserves actionable ${code}`, () => withFetch(async () => Response.json({ code, message }, { status: 409 }), async () => {
    await assert.rejects(saveAdoptedValues(3, {}), e => e.code === code && e.message === message)
  }))
}
test('unknown processing conflict does not claim duplicate batch', () => withFetch(async () => new Response('sensitive details', { status: 409 }), async () => {
  await assert.rejects(saveAdoptedValues(3, {}), e => e.status === 409 && !e.message.includes('批次编号') && !e.message.includes('sensitive'))
}))
test('server errors do not expose message payloads', () => withFetch(async () => Response.json({ code: 'SERVER_ERROR', message: 'sensitive SQL' }, { status: 500 }), async () => {
  await assert.rejects(request('/batches'), e => !e.message.includes('sensitive'))
}))
test('500 remains an error rather than empty records', () => withFetch(async () => new Response('', { status: 500 }), async () => { await assert.rejects(request('/batches'), e => e.status === 500) }))
test('HTML fallback is reported as invalid JSON', () => withFetch(async () => new Response('<html></html>'), async () => { await assert.rejects(request('/batches'), /JSON/) }))
test('network failure has an actionable message', () => withFetch(async () => { throw new TypeError('Failed to fetch') }, async () => { await assert.rejects(request('/batches'), /后端/) }))
test('cancellation is propagated to fetch', () => withFetch(async (_url, options) => { assert.equal(options.signal.aborted, true); throw new DOMException('aborted', 'AbortError') }, async () => { const c = new AbortController(); c.abort(); await assert.rejects(request('/batches', { signal: c.signal }), e => e.name === 'AbortError') }))

test('business APIs use the Spring Boot proxy', () => withFetch(async (url, options) => {
  assert.equal(url, '/api/batches/7/processing-advice')
  assert.equal(options.method, 'POST')
  return Response.json({ record_id: 3, advice: { advice_type: 'rule' } })
}, async () => {
  const response = await requestAdvice(7, { grade: 'A', temperature: 5, humidity: 80 })
  assert.equal(response.record_id, 3)
}))

test('processing history uses batch ID and preserves saved adoption values', () => withFetch(async url => {
  assert.equal(url, '/api/batches/7/processing-records')
  return Response.json({ records: [{ record_id: 3, adopted_values: { pre_cooling_time: '7h' } }] })
}, async () => { assert.equal((await getProcessingHistory(7)).records[0].adopted_values.pre_cooling_time, '7h') }))

test('coldchain import and alert APIs use the Spring Boot proxy', () => withFetch(async (url, options) => {
  assert.ok(['/api/batches/7/sensor-readings', '/api/alerts/9/resolve'].includes(url))
  assert.equal(options.method, 'POST')
  return Response.json({ persisted: true })
}, async () => {
  assert.equal((await importReadings(7, { timestamp: '2026-10-12T10:00:00+08:00', temperature: 5, humidity: 80, source: 'simulation' })).persisted, true)
  assert.equal((await resolveAlert(9, { resolved_by: 'tester', resolution: 'done' })).persisted, true)
}))
