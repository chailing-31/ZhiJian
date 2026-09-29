import { notConnected } from './pending.js'
// The current UI renders a record preview from GET /batches/{id}; this is not that endpoint.
export const getFormalReport = (_batchId) => notConnected('完整批次报告接口')
