import { request, ApiError } from './http.js'
import { validId } from '../utils/batch.js'

export function getColdchain(batchId, options = {}) {
  const id = validId(batchId)
  if (!id) return Promise.reject(new ApiError('批次 ID 不正确。'))
  return request(`/batches/${id}/coldchain`, { signal: options.signal })
}

// The browser parses CSV locally and sends one validated reading at a time.
export function importReadings(batchId, reading, options = {}) {
  const id = validId(batchId)
  if (!id) return Promise.reject(new ApiError('批次 ID 不正确。'))
  return request(`/batches/${id}/sensor-readings`, { method: 'POST', json: reading, signal: options.signal })
}

export function resolveAlert(alertId, resolution, options = {}) {
  const id = validId(alertId)
  if (!id) return Promise.reject(new ApiError('告警 ID 不正确。'))
  return request(`/alerts/${id}/resolve`, { method: 'POST', json: resolution, signal: options.signal })
}
