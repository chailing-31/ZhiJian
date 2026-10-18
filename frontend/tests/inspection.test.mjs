import test from 'node:test'
import assert from 'node:assert/strict'
import { inspectionRequest, predictInspection, listInspections, getInspection, reviewInspection, getInspectionReady } from '../src/api/inspection.js'
const key = '12345678-1234-4234-8234-123456789012'
const sample = () => ({ inspection_id: 9, batch_id: 3, persisted: true, prediction: { detections: [] }, reviews: [], artifact_urls: { input: '/api/inspections/9/artifacts/input', result: '/api/inspections/9/artifacts/result' } })
async function fake(fn, action) { const previous = globalThis.fetch; globalThis.fetch = fn; try { await action() } finally { globalThis.fetch = previous } }
test('upload is multipart through Spring Boot with a retained idempotency key', () => fake(async (url, o) => {
  assert.equal(url, '/api/batches/3/inspections'); assert.equal(o.method, 'POST'); assert.equal(o.headers['Idempotency-Key'], key)
  assert.equal(o.headers['Content-Type'], undefined); assert(o.body instanceof FormData); assert.equal(o.body.get('image').name, 'a.png')
  return Response.json(sample())
}, async () => { await predictInspection(3, new File(['png'], 'a.png', { type: 'image/png' }), { requestId: key }) }))
test('invalid IDs do not call a server', () => fake(() => { throw new Error('unreachable') }, async () => { await assert.rejects(listInspections('APPLE-2026-001'), /数值/); await assert.rejects(getInspection(-1)) }))
test('invalid file format is refused', async () => { await assert.rejects(predictInspection(3, new File(['x'], 'a.txt', { type: 'text/plain' }), { requestId: key }), /图片/) })
test('empty upload is refused', async () => { await assert.rejects(predictInspection(3, new File([], 'a.png', { type: 'image/png' }), { requestId: key })) })
test('request ID is mandatory', async () => { await assert.rejects(predictInspection(3, new File(['x'], 'a.png', { type: 'image/png' })), /编号/) })
test('history response must be an array', () => fake(async () => Response.json({}), async () => { await assert.rejects(listInspections(3), /列表/) }))
test('different batch response is rejected', () => fake(async () => Response.json({ ...sample(), batch_id: 99 }), async () => { await assert.rejects(predictInspection(3, new File(['x'], 'a.png', { type: 'image/png' }), { requestId: key }), /批次/) }))
test('external artifact URL is rejected', () => fake(async () => Response.json({ ...sample(), artifact_urls: { input: 'http://example.com/', result: 'http://127.0.0.1:8001/x' } }), async () => { await assert.rejects(getInspection(9), /地址/) }))
test('detail ID mismatch is rejected', () => fake(async () => Response.json(sample()), async () => { await assert.rejects(getInspection(8), /结构/) }))
test('review uses PATCH and JSON', () => fake(async (url, o) => { assert.equal(url, '/api/inspections/9/review'); assert.equal(o.method, 'PATCH'); assert.equal(JSON.parse(o.body).expected_revision, 0); return Response.json(sample()) }, async () => { await reviewInspection(9, { expected_revision: 0 }) }))
test('known scoped backend error is retained', () => fake(async () => Response.json({ code: 'AI_NOT_READY', message: '模型尚未就绪' }, { status: 503 }), async () => { await assert.rejects(getInspection(9), /尚未就绪/) }))
test('arbitrary backend body is not rendered', () => fake(async () => Response.json({ message: 'private stack details' }, { status: 500 }), async () => { await assert.rejects(getInspection(9), e => !e.message.includes('private')) }))
test('HTML instead of JSON is not success', () => fake(async () => new Response('<html>'), async () => { await assert.rejects(getInspectionReady(), /JSON/) }))
test('abort propagates without a false success', () => fake(async (_, o) => { assert(o.signal.aborted); throw new DOMException('abort', 'AbortError') }, async () => { const c = new AbortController(); c.abort(); await assert.rejects(getInspection(9, { signal: c.signal }), e => e.name === 'AbortError') }))
test('timeout explains uncertain server completion', () => fake(async (_, o) => new Promise((resolve, reject) => o.signal.addEventListener('abort', () => reject(new DOMException('abort', 'AbortError')))), async () => { await assert.rejects(inspectionRequest('/x', { timeout: 5 }), /仍在处理/) }))
test('network error is explicit', () => fake(async () => { throw new TypeError('network') }, async () => { await assert.rejects(getInspection(9), /刷新历史/) }))
