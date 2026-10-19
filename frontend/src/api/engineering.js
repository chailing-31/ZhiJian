import { inspectionRequest } from './inspection.js'
export const getWorkspaceStatus = options => inspectionRequest('/workspace/status', { ...options, timeout: 12000 })
export const getWorkspaceReport = (id, options) => inspectionRequest(`/batches/${encodeURIComponent(id)}/report`, { ...options, timeout: 15000 })
export async function getWorkspaceBatches(options) {
  const data = await inspectionRequest('/batches', { ...options, timeout: 12000 })
  if (!Array.isArray(data)) throw new Error('批次接口未返回列表。')
  return data
}
export const getBusinessStatus = options => inspectionRequest('/business-service/ready', { ...options, timeout: 12000 })
export const createReviewedAdvice = (batchId, body, options) => inspectionRequest(`/batches/${encodeURIComponent(batchId)}/reviewed-processing-advice`, { ...options, method: 'POST', body, timeout: 45000 })
export const getQualityFlow = (id,options) => inspectionRequest(`/batches/${encodeURIComponent(id)}/quality-flow`,{...options,timeout:20000})
export const publishQualityFlow = (id,body,options) => inspectionRequest(`/batches/${encodeURIComponent(id)}/quality-flow/publish`,{...options,method:'POST',body,timeout:20000})
export const getPublicQualityFlow = (code,options) => inspectionRequest(`/trace/${encodeURIComponent(code)}/quality-flow`,{...options,timeout:15000})

export const getDashboard = options => inspectionRequest('/workspace/dashboard', { ...options, timeout: 20000 })
