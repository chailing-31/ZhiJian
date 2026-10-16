import { validId } from './batch.js'

export const GRADE_TONES = { A: 'green', B: 'blue', C: 'amber', REJECT: 'red' }
export function gradeText(value) {
  if (value === 'REJECT') return '拒收'
  if (['A', 'B', 'C'].includes(value)) return value
  return value ? '待修正' : '未评级'
}
// Public snapshots deliberately contain a display string, not an internal assessment.
export function publishedGradeCode(value) {
  return typeof value === 'string' ? value.split('·', 1)[0].trim() : ''
}
export function gradeSource(assessment) {
  return assessment?.source === 'ai_initial' ? 'AI 初评' : assessment ? '人工修正' : '等待质检'
}
export function gradeStatusFor(w) {
  if (w?.assessment_current) return { label: gradeSource(w.assessment), title: w.grade_label, detail: '当前有效品控等级', message: w.assessment?.source === 'human_corrected' ? '本轮采用人工修正等级，后续品控读取这一版本。' : '本轮已完成 AI 初评，可直接进入后续品控；人工检查和修正可随时进行。' }
  if (!w?.evidence?.record_count) return { label: '等待初评', title: '上传照片，立即获取等级', detail: '开始本轮质检', message: '上传照片后，系统自动评级并带入后续品控。' }
  if (w.evidence.pending_count) return { label: '等级待检查', title: '先处理已记录的照片问题', detail: '当前等级暂不可用', message: '已保存复查问题或人工发现的缺陷，请到 AI 质检完成图像检查与等级修正。' }
  return { label: '等级待更新', title: '证据已变化，请重新确认等级', detail: '保留历史等级与依据', message: w?.assessment?.source === 'human_corrected' ? '上次人工批次修正仍保留。本轮证据变化后，请检查并重新确认，后续加工暂停使用旧等级。' : '请检查当前照片和等级来源后，更新本轮评定。' }
}
export function workbenchLocation(w, tab = 'overview') {
  const id = validId(w?.batch?.id ?? w?.batch_id)
  return id ? { path: `/batches/${id}`, query: { tab } } : { path: '/batches' }
}
// The backend owns business routing; these labels only explain saved state.
export function attentionFor(w) {
  if (w?.coldchain?.hold) return { label: '冷链异常待核查', tab: 'coldchain', icon: 'thermometer' }
  if (w?.evidence?.pending_count) return { label: `${w.evidence.pending_count} 条照片待复查`, tab: 'inspection', icon: 'scan' }
  if (w?.assessment && !w.assessment_current) return { label: '等级来源需要更新', tab: 'assessment', icon: 'refresh' }
  if (w?.assessment_current && ['C', 'REJECT'].includes(w.assessment?.final_grade) && !w.quality_disposition_recorded) return { label: w.assessment.final_grade === 'REJECT' ? '拒收初评待处置' : '用途待评估', tab: 'processing', icon: 'sliders' }
  if (w?.processing?.record_id && !w.processing.current) return { label: '加工建议需要更新', tab: 'processing', icon: 'sliders' }
  if (w?.publication && !w.publication.current) return { label: '公开摘要需要更新', tab: 'report', icon: 'trace' }
  return null
}
