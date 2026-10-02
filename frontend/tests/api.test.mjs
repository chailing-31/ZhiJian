import test from 'node:test'
import assert from 'node:assert/strict'
import { request, ApiError } from '../src/api/http.js'
import { getBatch, listBatches, createBatch } from '../src/api/batch.js'
import { getPublicTrace } from '../src/api/trace.js'
import { requestAdvice, saveAdoptedValues } from '../src/api/processing.js'
import { importReadings, resolveAlert } from '../src/api/coldchain.js'
import { getFormalReport } from '../src/api/report.js'
import { FeatureNotReadyError } from '../src/api/pending.js'

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
test('public view only asks the public trace endpoint', () => withFetch(async url => { assert.equal(url, '/api/trace/APPLE-2026-001'); return Response.json({ batch_code: 'APPLE-2026-001', events: [] }) }, async () => { assert.equal((await getPublicTrace('APPLE-2026-001')).events.length, 0) }))
test('create uses POST with JSON and no client-generated ID', () => withFetch(async (url, options) => { assert.equal(url, '/api/batches'); assert.equal(options.method, 'POST'); assert.deepEqual(JSON.parse(options.body), { batch_code: 'TEST-8', product: '苹果' }); return Response.json({ batch_id: 8, batch_code: 'TEST-8' }) }, async () => { assert.equal((await createBatch({ batch_code: 'TEST-8', product: '苹果' })).batch_id, 8) }))

test('formal report uses numeric batch ID through /api proxy', () => withFetch(async (url, options) => { assert.equal(url, '/api/batches/7/report'); assert.equal(options.method, 'GET'); return Response.json({ schema_version: 'zhijian.batch.report.v1', batch: { batch_id: 7 } }) }, async () => { assert.equal((await getFormalReport(7)).batch.batch_id, 7) }))
test('formal report rejects public batch code as internal ID', () => withFetch(() => { throw new Error('should not fetch') }, async () => { await assert.rejects(getFormalReport('APPLE-2026-001'), ApiError) }))

test('409 gets a readable duplicate error without leaking server response', () => withFetch(async () => new Response('sensitive details', { status: 409 }), async () => { await assert.rejects(request('/batches'), e => e.status === 409 && e.message.includes('已存在') && !e.message.includes('sensitive')) }))
test('500 remains an error rather than empty records', () => withFetch(async () => new Response('', { status: 500 }), async () => { await assert.rejects(request('/batches'), e => e.status === 500) }))
test('HTML fallback is reported as invalid JSON', () => withFetch(async () => new Response('<html></html>'), async () => { await assert.rejects(request('/batches'), /JSON/) }))
test('network failure has an actionable message', () => withFetch(async () => { throw new TypeError('Failed to fetch') }, async () => { await assert.rejects(request('/batches'), /后端/) }))
test('cancellation is propagated to fetch', () => withFetch(async (_url, options) => { assert.equal(options.signal.aborted, true); throw new DOMException('aborted', 'AbortError') }, async () => { const c = new AbortController(); c.abort(); await assert.rejects(request('/batches', { signal: c.signal }), e => e.name === 'AbortError') }))

test('remaining pending capabilities fail explicitly without network calls', () => withFetch(() => { throw new Error('unconnected APIs must never fetch') }, async () => { for (const fn of [requestAdvice, saveAdoptedValues, importReadings, resolveAlert]) await assert.rejects(fn(), FeatureNotReadyError) }))
