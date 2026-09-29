import { request, ApiError } from './http.js'
import { validId } from '../utils/batch.js'
export async function listBatches(options) {
  const data = await request('/batches', options)
  if (!Array.isArray(data)) throw new ApiError('批次列表返回结构异常，预期为数组。')
  return data
}
export function getBatch(id, options) {
  if (!validId(id)) return Promise.reject(new ApiError('批次 ID 不正确。'))
  return request(`/batches/${validId(id)}`, options)
}
export function createBatch(input) { return request('/batches', { method: 'POST', json: input }) }
