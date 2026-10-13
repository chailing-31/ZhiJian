import { inspectionRequest } from './inspection.js'
import { validId } from '../utils/batch.js'
function id(v) { const n = validId(v); if (!n) throw new Error('批次或记录编号不正确。'); return n }
export async function getWorkbench(batch, options = {}) {
  const value = await inspectionRequest(`/batches/${id(batch)}/batch-workbench`, { ...options, timeout: 20000 })
  if (value.version !== 'batch-workbench-v4-20261013' || String(value.batch?.id) !== String(batch) || !Array.isArray(value.evidence?.records)) throw new Error('工作台版本或批次不匹配，请检查后端是否已重启。')
  return value
}
export const createRound = (b, body, options) => inspectionRequest(`/batches/${id(b)}/inspection-rounds`, { ...options, method: 'POST', body, timeout: 20000 })
export const bindImage = (b, r, body, options) => inspectionRequest(`/batches/${id(b)}/inspection-rounds/${id(r)}/images`, { ...options, method: 'POST', body, timeout: 20000 })
export const saveAssessment = (b, body, options) => inspectionRequest(`/batches/${id(b)}/batch-assessments`, { ...options, method: 'POST', body, timeout: 20000 })
export const assessmentAdvice = (b, body, options) => inspectionRequest(`/batches/${id(b)}/assessment-processing-advice`, { ...options, method: 'POST', body, timeout: 45000 })
export const recordOperation = (b, body, options) => inspectionRequest(`/batches/${id(b)}/batch-operations`, { ...options, method: 'POST', body, timeout: 20000 })
export const previewSummary = (b, body, options) => inspectionRequest(`/batches/${id(b)}/batch-summary/preview`, { ...options, method: 'POST', body, timeout: 20000 })
export const publishSummary = (b, body, options) => inspectionRequest(`/batches/${id(b)}/batch-summary/publish`, { ...options, method: 'POST', body, timeout: 20000 })
export const publicSummary = (code, options) => inspectionRequest(`/trace/${encodeURIComponent(code)}/batch-summary`, { ...options, timeout: 20000 })
