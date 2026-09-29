import { notConnected } from './pending.js'
// Planned public business endpoints: see docs/API.md. Python AI endpoints stay server-side.
export const predictInspection = (_batchId, _file) => notConnected('图片上传与模型推理')
export const reviewInspection = (_inspectionId, _input) => notConnected('人工复核')
