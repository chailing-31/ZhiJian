import { notConnected } from './pending.js'
// POST /batches/{id}/processing-advice is planned, not implemented by this package.
export const requestAdvice = (_batchId, _inputs) => notConnected('加工规则建议')
// Saving adopted values has no frozen contract yet. Do not invent a backend URL.
export const saveAdoptedValues = (_recordId, _values) => notConnected('采用记录保存')
