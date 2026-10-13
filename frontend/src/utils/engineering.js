// Detector classes, screening suggestions, and human material grades are distinct.
export const GRADE_OPTIONS = ['A','B','C','REJECT']
export const GRADE_LABELS = { A:'优选外观', B:'一般外观', C:'用途待评估', REJECT:'人工拒收' }
export const FLOW_LABELS = {
  REVIEW_REQUIRED:'先完成最新质检与人工定级',
  COLDCHAIN_ATTENTION:'冷链异常待核实，暂停新的工艺采用',
  REJECTED_HOLD:'人工拒收，暂停流转',
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
  if(!p) return {grade:null,source:'none',title:'等待检测',action:'上传原图，查看模型实际候选；模型只检测擦伤和虫害。',canProcess:false}
  const review=record.latest_review, final=normalizedGrade(review?.final_grade)
  if(review && !['target_confirmed','manual_target_confirmed','no_target_confirmed'].includes(review.conclusion)) return {grade:null,source:'review_pending',title:'待复查，不是一个质量等级',action:'仍存在漏检或不确定情况。请补拍、诊断或完成复核，暂不生成可采用工艺。',canProcess:false}
  if(review?.final_grade && !final) return {grade:null,source:'legacy_grade',title:'历史等级需要重新核定',action:`历史值 ${review.final_grade} 不属于统一 A/B/C/REJECT 业务契约；不会静默把 D 改为拒收。`,canProcess:false}
  if(final && review) {
    if(final==='A' && ['target_confirmed','manual_target_confirmed'].includes(review.conclusion)) return {grade:null,source:'review_pending',title:'历史复核与 A 定义冲突',action:'已确认存在目标缺陷，不能沿用本版 A 定义，请重新核定。',canProcess:false}
    const actions={A:'人工优选外观：可进入分选、包装或加工条件评估；实际用途需确认。',B:'人工一般外观：进一步分选，核对 B 规则、环境和设备条件后再采用工艺。',C:'用途待评估：由负责人复核适用性，B 服务不会自动给预冷与清洗参数。',REJECT:'人工拒收：暂停流转并登记处置；不提供可采用的工艺参数。'}
    return {grade:final,source:'human',title:`人工业务等级 · ${GRADE_LABELS[final]}`,action:actions[final],canProcess:true}
  }
  const suggested=['B','C'].includes(p.suggested_grade)?p.suggested_grade:null
  if(p.detections?.length && !suggested) return {grade:null,source:'model_rule',title:'发现疑似缺陷，建议专项复核',action:'较大或较多候选不能直接证明拒收；旧规则 D 也不自动传给加工。请先核定业务等级。',canProcess:false}
  return {grade:suggested,source:'model_rule',title:suggested?'实验性外观建议 · 待人工核定':'未检出两类目标 · 等级待定',action:suggested?'规则只依据候选框给出参考，不是模型学出的品质类别；先完成复核再传递给 B。':'未检出擦伤和虫害不等于优果。模型不覆盖所有缺陷，先核实原图和其他外观信息。',canProcess:false}
}
export function reviewProblem(form,count) {
  if(!form.reviewer?.trim()||!form.remark?.trim()) return '请填写复核人和复核说明。'
  if(form.candidates.length!==count||form.candidates.some(c=>!['confirmed','false_positive','duplicate','uncertain'].includes(c.decision))) return '请逐一标记所有候选框。'
  const confirmed=new Set(form.candidates.filter(c=>c.decision==='confirmed').map(c=>c.candidate_index))
  for(const c of form.candidates) if(c.decision==='duplicate'&&(!confirmed.has(Number(c.duplicate_of))||Number(c.duplicate_of)===c.candidate_index)) return '重复框必须关联到另一个已确认候选。'
  if(form.conclusion==='target_confirmed'&&!confirmed.size) return '没有已确认模型候选。确有人工发现可选择“人工发现缺陷”，填写依据；不确定时选“需要复查”。'
  if(form.conclusion==='no_target_confirmed'&&confirmed.size) return '未确认目标结论与已确认候选冲突。'
  if(form.candidates.some(c=>c.decision==='uncertain')&&form.conclusion!=='needs_recheck') return '存在不确定候选，请选择需要复查。'
  if(form.final_grade&&!normalizedGrade(form.final_grade)) return '请选择 A/B/C/REJECT，或保持等级未核定。'
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
