import { inspectionRequest } from './inspection.js'
import { validId } from '../utils/batch.js'
function id(v) { const n = validId(v); if (!n) throw new Error('批次或记录编号不正确。'); return n }
export async function getWorkbench(batch, options = {}) {
  const value = await inspectionRequest(`/batches/${id(batch)}/batch-workbench`, { ...options, timeout: 20000 })
  if (value.version !== 'ai-first-application-v5.1-20261014' || String(value.batch?.id) !== String(batch) || !Array.isArray(value.evidence?.records)) throw new Error('前后端版本或批次不匹配，请重启更新后的服务。')
  return value
}
export async function synchronizeInitial(batch, options = {}) {
  const result = await inspectionRequest(`/batches/${id(batch)}/auto-assessment`, { ...options, method: 'POST', timeout: 20000 })
  if (!result.persisted || result.workbench?.version !== 'ai-first-application-v5.1-20261014') throw new Error('自动初评未确认保存，请刷新核对。')
  return result.workbench
}
export const createRound = (b, body, options) => inspectionRequest(`/batches/${id(b)}/inspection-rounds`, { ...options, method: 'POST', body, timeout: 20000 })
export const bindImage = (b, r, body, options) => inspectionRequest(`/batches/${id(b)}/inspection-rounds/${id(r)}/images`, { ...options, method: 'POST', body, timeout: 20000 })
export const saveAssessment = (b, body, options) => inspectionRequest(`/batches/${id(b)}/batch-assessments`, { ...options, method: 'POST', body, timeout: 20000 })
export const assessmentAdvice = (b, body, options) => inspectionRequest(`/batches/${id(b)}/assessment-processing-advice`, { ...options, method: 'POST', body, timeout: 45000 })
export const recordOperation = (b, body, options) => inspectionRequest(`/batches/${id(b)}/batch-operations`, { ...options, method: 'POST', body, timeout: 20000 })
export const previewSummary = (b, body, options) => inspectionRequest(`/batches/${id(b)}/batch-summary/preview`, { ...options, method: 'POST', body, timeout: 20000 })
export const publishSummary = (b, body, options) => inspectionRequest(`/batches/${id(b)}/batch-summary/publish`, { ...options, method: 'POST', body, timeout: 20000 })
export const publicSummary = (code, options) => inspectionRequest(`/trace/${encodeURIComponent(code)}/batch-summary`, { ...options, timeout: 20000 })
