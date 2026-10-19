// Detector classes, screening suggestions, and human material grades are distinct.
export const GRADE_OPTIONS = ['A','B','C','REJECT']
export const GRADE_LABELS = { A:'优选外观', B:'一般外观', C:'用途待评估', REJECT:'拒收' }
export const FLOW_LABELS = {
  REVIEW_REQUIRED:'上传并获取初评，处理已标记的复查问题',
  COLDCHAIN_ATTENTION:'冷链异常待核实，暂停新的工艺采用',
  REJECTED_HOLD:'拒收初评或修正，暂停正常流转',
  PROCESSING_SOURCE_REQUIRED:'尚无与最新复核关联的工艺建议',
  PROCESSING_REVIEW_REQUIRED:'负责人先评估用途或工艺适用性',
  ADOPTION_REQUIRED:'工艺建议已生成，等待人工采用',
  COLDCHAIN_RECORDS_REQUIRED:'采用值已保存，尚无同批次冷链记录',
  RECORDS_LINKED:'最新复核、工艺记录与同批次冷链资料已关联',
}
export function normalizedGrade(value) {
  const grade=typeof value==='string'?value.trim().toUpperCase():''
  return GRADE_OPTIONS.includes(grade)?grade:null
}
export function decisionFor(record) {
  const p=record?.prediction
  if(!p) return {grade:null,source:'none',title:'等待 AI 质检',action:'上传苹果照片，系统将直接给出外观初评等级。',canProcess:false}
  const review=record.latest_review, final=normalizedGrade(review?.final_grade)
  if(review && !['target_confirmed','manual_target_confirmed','no_target_confirmed'].includes(review.conclusion)) return {grade:null,source:'review_pending',title:'已标记待复查',action:'处理已标记的问题后继续；原始 AI 结果仍保留。',canProcess:false}
  if(review?.final_grade && !final) return {grade:null,source:'legacy_grade',title:'历史等级需要检查',action:'请将历史等级修正为 A/B/C/REJECT。',canProcess:false}
  if(final === 'A' && ['target_confirmed','manual_target_confirmed'].includes(review?.conclusion)) return {grade:null,source:'review_pending',title:'修正等级与确认缺陷冲突',action:'请检查图像结论与等级。',canProcess:false}
  const initial=normalizedGrade(record.initial_assessment?.grade || p.initial_grade || ({none_observed:'A',low:'B',moderate:'C',high:'REJECT'})[p.defect_burden?.burden_level])
  const grade=final || (review?.conclusion === 'no_target_confirmed' ? 'A' : initial)
  if(!grade) return {grade:null,source:'none',title:'历史记录缺少初评依据',action:'请重新检测照片，系统将补充初评。',canProcess:false}
  const source=review && (final || review.conclusion === 'no_target_confirmed') ? 'human' : 'ai_initial'
  const actions={A:'进入商品分选与工艺条件评估。',B:'先分选，再根据实际环境生成加工建议。',C:'进入分选与用途评估，记录后续处置。',REJECT:source==='ai_initial'?'拒收初评：暂停正常流转，可检查修正，并记录实际处置。':'已修正为拒收，请记录实际处置。'}
  return {grade,source,title:`${source === 'human' ? '人工修正' : 'AI 初评'} · ${GRADE_LABELS[grade]}`,action:actions[grade],canProcess:true}
}
export function reviewProblem(form,count) {
  if(!form.reviewer?.trim()||!form.remark?.trim()) return '请填写复核人和复核说明。'
  if(form.candidates.length!==count||form.candidates.some(c=>!['confirmed','false_positive','duplicate','uncertain'].includes(c.decision))) return '请逐一标记所有候选框。'
  const confirmed=new Set(form.candidates.filter(c=>c.decision==='confirmed').map(c=>c.candidate_index))
  for(const c of form.candidates) if(c.decision==='duplicate'&&(!confirmed.has(Number(c.duplicate_of))||Number(c.duplicate_of)===c.candidate_index)) return '重复框必须关联到另一个已确认候选。'
  if(form.conclusion==='target_confirmed'&&!confirmed.size) return '没有已确认模型候选。确有人工发现可选择“人工发现缺陷”，填写依据；不确定时选“需要复查”。'
  if(form.conclusion==='no_target_confirmed'&&confirmed.size) return '未确认目标结论与已确认候选冲突。'
  if(form.candidates.some(c=>c.decision==='uncertain')&&form.conclusion!=='needs_recheck') return '存在不确定候选，请选择需要复查。'
  if(form.final_grade&&!normalizedGrade(form.final_grade)) return '请选择 A/B/C/REJECT，或保留 AI 初评。'
  if(form.final_grade&&form.conclusion==='needs_recheck') return '复核未完成时请保持等级未核定，不能同时填写最终等级。'
  if(form.conclusion==='manual_target_confirmed'&&form.remark.trim().length<10) return '人工发现缺陷请填写至少10字的位置、现象和依据，模型候选不改写。'
  if(form.final_grade==='A'&&(confirmed.size||form.conclusion==='manual_target_confirmed')) return '本版 A 定义为人工未确认可见目标缺陷，与当前已确认候选冲突。'
  return ''
}
export function stageCounts(report) {
  const n=v=>Number.isFinite(Number(v))&&Number(v)>=0?Number(v):0
  return {inspections:n(report?.inspection?.count),reviewed:n(report?.inspection?.reviewed_count),processing:n(report?.processing?.count),readings:n(report?.coldchain?.summary?.reading_count)}
}
export function candidateCaption(d,index){return `${index+1} · ${d.class_label||d.class_name||'未命名类别'} · ${(Number(d.confidence)*100).toFixed(1)}%`}
export function validPredictionGeometry(p){const w=p?.image?.width,h=p?.image?.height;if(!(Number.isFinite(w)&&Number.isFinite(h)&&w>0&&h>0&&Array.isArray(p.detections)))return false;return p.detections.every(d=>{const b=d.bbox_xyxy;return Array.isArray(b)&&b.length===4&&b.every(Number.isFinite)&&0<=b[0]&&b[0]<b[2]&&b[2]<=w&&0<=b[1]&&b[1]<b[3]&&b[3]<=h})}
