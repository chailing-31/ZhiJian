import { request, ApiError } from './http.js'
import { validId } from '../utils/batch.js'

export function getFormalReport(batchId, options) {
  const id = validId(batchId)
  if (!id) return Promise.reject(new ApiError('批次 ID 不正确。'))
  return request(`/batches/${id}/report`, options)
}
