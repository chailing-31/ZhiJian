import { request, ApiError } from './http.js'
import { validId } from '../utils/batch.js'

export function getProcessingHistory(batchId, options = {}) {
  const id = validId(batchId)
  if (!id) return Promise.reject(new ApiError('批次 ID 不正确。'))
  return request(`/batches/${id}/processing-records`, { signal: options.signal })
}

export function getProcessingTrace(batchId, recordId, options = {}) {
  const batch = validId(batchId), record = validId(recordId)
  if (!batch || !record) return Promise.reject(new ApiError('批次或加工记录 ID 不正确。'))
  return request(`/batches/${batch}/processing-records/${record}/trace`, { signal: options.signal })
}

export function requestAdvice(batchId, inputs, options = {}) {
  const id = validId(batchId)
  if (!id) return Promise.reject(new ApiError('批次 ID 不正确。'))
  return request(`/batches/${id}/processing-advice`, { method: 'POST', json: inputs, signal: options.signal })
}

export function saveAdoptedValues(recordId, values, options = {}) {
  const id = validId(recordId)
  if (!id) return Promise.reject(new ApiError('加工记录 ID 不正确。'))
  return request(`/processing-records/${id}/adopt`, { method: 'POST', json: values, signal: options.signal })
}
