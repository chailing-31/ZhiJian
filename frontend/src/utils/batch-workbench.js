export const GRADE_OPTIONS = [
  ['A', '优选外观'], ['B', '一般外观，需分选'], ['C', '用途待评估'], ['REJECT', '拒收'],
]
export const USE_OPTIONS = [
  ['premium_sorting', '优选商品分选'], ['general_sorting', '普通商品分选'],
  ['processing_evaluation', '加工适用性评估'], ['isolate_return', '隔离／退货处置'], ['undetermined', '用途待定'],
]
export function assessmentProblem(grade, use, evidence) {
  if (!GRADE_OPTIONS.some(([g]) => g === grade)) return '请选择 A/B/C/REJECT 等级。'
  if (!USE_OPTIONS.some(([u]) => u === use)) return '请选择拟定用途。'
  if (!evidence?.record_count) return '请先将图片纳入本轮抽检。'
  if (evidence.pending_count) return '请先处理人员标记的待复查问题。'
  if (grade === 'A' && evidence.confirmed_target_records > 0) return '本轮确认的缺陷与 A 定义冲突，请另建分选后抽检轮次或重新核定。'
  if ((grade === 'REJECT') !== (use === 'isolate_return')) return '暂停或拒收等级必须对应隔离／退货处置。'
  if (use === 'premium_sorting' && grade !== 'A') return '优选商品分选需要 A 评定。'
  if (grade === 'C' && !['processing_evaluation', 'undetermined'].includes(use)) return 'C 级先做用途评估，不直接批准商品用途。'
  return ''
}
export function evidenceDraft(w) {
  return `${w.evidence_summary} 抽检范围：${w.round?.sampling_note || '待填写'}。本次结论仅依据上述证据；请补充人工分选依据和用途限制。`
}
export function steps(w) {
  if (!w) return []
  return [
    { id: 'inspection', title: 'AI 质检', text: !w.evidence?.record_count ? '上传照片获取等级' : w.evidence.pending_count ? `${w.evidence.pending_count} 条已标记待复查` : `${w.evidence.unique_photo_count} 张照片已检测` },
    { id: 'assessment', title: '当前等级', text: !w.assessment ? '等待 AI 初评' : w.assessment_current ? `${w.assessment.final_grade} · ${w.assessment.source === 'ai_initial' ? 'AI 初评' : '人工修正'}` : '证据更新，待更新初评' },
    { id: 'processing', title: '加工品控', text: w.quality_disposition_recorded ? '品质处置已登记' : w.processing_executed ? '已登记执行' : w.processing?.current && w.processing.adopted ? '已采用，待执行记录' : w.processing?.current ? '建议已保存' : '待处理' },
    { id: 'coldchain', title: '冷链与处置', text: !w.coldchain?.reading_count ? '尚无观测记录' : w.coldchain.hold ? '存在待核查事项' : '已有观测记录' },
    { id: 'report', title: '报告与溯源', text: !w.publication ? '尚未批准公开摘要' : w.publication.current ? '公开快照一致' : '公开摘要待更新' },
  ]
}
export function defaultUse(grade) { return ({ A: 'premium_sorting', B: 'general_sorting', C: 'processing_evaluation', REJECT: 'isolate_return' })[grade] || 'undetermined' }
export function processNumber(value, label, required = true) {
  if (value === '' || value == null) { if (required) throw new Error(`请填写${label}，不使用假定值。`); return null }
  const n = Number(value); if (!Number.isFinite(n)) throw new Error(`${label}必须为有限数值。`)
  return n
}
export function localTimeInput(date = new Date()) {
  const pad = n => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
export function toZonedTime(value) {
  const d = new Date(value)
  if (!value || Number.isNaN(d.getTime())) throw new Error('请填写有效的实际执行时间。')
  return d.toISOString().replace('.000Z', 'Z')
}
// Keep the same idempotency key when retrying unchanged inputs after a timeout.
export function writeIntent(uuid = () => globalThis.crypto.randomUUID()) {
  let previous = '', key = ''
  return body => { const sig = JSON.stringify(body); if (sig !== previous) { key = uuid(); previous = sig } return { ...body, request_id: key } }
}
export function canAdopt(w) {
  return !!(w?.assessment_current && ['A', 'B'].includes(w.assessment?.final_grade)
    && ['premium_sorting', 'general_sorting'].includes(w.assessment?.intended_use)
    && w.sorting_recorded && w.processing?.current && w.processing.advice_status === 'suggested' && !w.coldchain?.hold)
}
