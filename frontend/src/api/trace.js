import { request, ApiError } from './http.js'
export function getPublicTrace(batchCode, options) {
  if (typeof batchCode !== 'string' || !/^[A-Za-z0-9-]{1,64}$/.test(batchCode)) return Promise.reject(new ApiError('批次编号格式不正确。', 404))
  return request('/trace/' + encodeURIComponent(batchCode), options)
}
