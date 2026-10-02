import { ApiError } from './http.js'
import { validId } from '../utils/batch.js'

// Separate from the 12-second batch API: real CPU inference may take longer.
export async function inspectionRequest(path, { method = 'GET', body, signal, requestId, timeout = 300000 } = {}) {
  const controller = new AbortController()
  const onAbort = () => controller.abort()
  if (signal?.aborted) controller.abort()
  signal?.addEventListener('abort', onAbort, { once: true })
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; controller.abort() }, timeout)
  const multipart = typeof FormData !== 'undefined' && body instanceof FormData
  try {
    const headers = { Accept: 'application/json' }
    if (body !== undefined && !multipart) headers['Content-Type'] = 'application/json'
    if (requestId) headers['Idempotency-Key'] = requestId
    const response = await fetch('/api' + path, {
      method, headers, signal: controller.signal,
      ...(body === undefined ? {} : { body: multipart ? body : JSON.stringify(body) }),
    })
    const payload = await response.json().catch(() => null)
    if (!response.ok) {
      const fallback = response.status === 404
        ? '接口或记录不存在。请确认后端使用 inspection profile 启动。'
        : '质检接口异常，请检查 8080 后端和 8001 模型服务。'
      // Only display the new scoped, safe backend error format, not arbitrary server traces.
      const message = typeof payload?.code === 'string' && typeof payload?.message === 'string'
        ? payload.message.slice(0, 600) : fallback
      throw new ApiError(message, response.status)
    }
    if (payload === null) throw new ApiError('接口未返回有效 JSON，请检查 /api 代理。')
    return payload
  } catch (error) {
    if (timedOut) throw new ApiError('请求超时，但后端可能仍在处理。请稍后刷新质检历史，避免重复创建。')
    if (error instanceof ApiError || signal?.aborted) throw error
    throw new ApiError('质检请求未完成。请检查网络，并先刷新历史记录，不要连续重复上传。')
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', onAbort)
  }
}
function id(value) { const v = validId(value); if (!v) throw new ApiError('请使用实际数值 ID。'); return v }
function detail(value, inspectionId) {
  if (!value || !validId(value.inspection_id) || !value.persisted || !Array.isArray(value.prediction?.detections)
      || (inspectionId && String(value.inspection_id) !== String(inspectionId)) || !Array.isArray(value.reviews)) {
    throw new ApiError('质检记录结构不符合约定。')
  }
  for (const variant of ['input', 'result']) {
    if (value.artifact_urls?.[variant] !== `/api/inspections/${value.inspection_id}/artifacts/${variant}`) {
      throw new ApiError('图片地址不符合后端代理约定。')
    }
  }
  return value
}
export const getInspectionReady = options => inspectionRequest('/inspection-service/ready', { ...options, timeout: 12000 })
export async function listInspections(batchId, options) {
  const value = await inspectionRequest(`/batches/${id(batchId)}/inspections`, options)
  if (!Array.isArray(value)) throw new ApiError('质检历史没有返回列表。')
  return value
}
export async function getInspection(inspectionId, options) {
  return detail(await inspectionRequest(`/inspections/${id(inspectionId)}`, options), inspectionId)
}
export async function predictInspection(batchId, file, { requestId, signal } = {}) {
  const batch = id(batchId)
  if (!file || !['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size <= 0 || file.size > 10 * 1024 * 1024) {
    throw new ApiError('请选择非空 JPG、PNG 或 WebP 图片，不超过 10 MiB。')
  }
  if (typeof requestId !== 'string' || !/^[0-9a-f-]{36}$/i.test(requestId)) throw new ApiError('缺少上传请求编号，请重新选择图片。')
  const form = new FormData(); form.append('image', file)
  const value = detail(await inspectionRequest(`/batches/${batch}/inspections`, { method: 'POST', body: form, signal, requestId }))
  if (String(value.batch_id) !== String(batch)) throw new ApiError('返回的记录不属于当前批次。')
  return value
}
export async function reviewInspection(inspectionId, input, options) {
  return detail(await inspectionRequest(`/inspections/${id(inspectionId)}/review`, { ...options, method: 'PATCH', body: input }), inspectionId)
}
