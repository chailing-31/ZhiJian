<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getWorkbench, createRound, bindImage, saveAssessment, previewSummary, publishSummary } from '../../api/batch-workbench.js'
import { listInspections } from '../../api/inspection.js'
import { GRADE_OPTIONS, USE_OPTIONS, assessmentProblem, evidenceDraft, steps, writeIntent } from '../../utils/batch-workbench.js'
import InspectionWorkspace from '../inspection/InspectionWorkspace.vue'
import ColdChainWorkspace from '../coldchain/ColdChainWorkspace.vue'
import AssessmentProcessing from '../../components/batch/AssessmentProcessing.vue'
import '../../styles/batch-workbench.css'
const props = defineProps({ batchId: { type: [String, Number], required: true } })
const route = useRoute(), router = useRouter()
const data = ref(null), loading = ref(false), busy = ref(false), error = ref(''), notice = ref(''), operator = ref('')
const inspection = ref(null), recentImages = ref([]), selectedImages = ref([]), sampleCodes = reactive({}), publicationPreview = ref(null)
const round = reactive({ name: '入厂抽检', sampling_note: '', confirmed: false })
const assessment = reactive({ final_grade: '', intended_use: '', grade_basis: '', scope: false, use: false })
const publication = reactive({ grade: false, use: false, confirmed: false })
const roundIntent = writeIntent(), assessmentIntent = writeIntent(), publicationIntent = writeIntent()
let controller = new AbortController()
const tabs = [['overview', '品控总览'], ['inspection', '抽检与复核'], ['assessment', '批次评定'], ['processing', '加工品控'], ['coldchain', '冷链观测'], ['report', '报告与溯源']]
const tab = computed(() => tabs.some(([v]) => v === route.query.tab) ? route.query.tab : 'overview')
const batch = computed(() => data.value ? { ...data.value.batch, batch_id: data.value.batch.id } : null)
const workflowSteps = computed(() => steps(data.value))
const availableImages = computed(() => recentImages.value.filter(r => !data.value?.evidence.records.some(e => e.inspection_id === r.inspection_id)))
const coldLabels = { not_started: '尚无观测', unverified: '待重新核验 B 结果', normal: '末次观测未触发超温', insufficient_data: '持续时间证据不足', pending: '超温持续计时中', active: '已触发持续超温' }
const evidenceMessage = computed(() => !data.value?.evidence.record_count ? '尚无本轮图像证据。' : data.value.evidence.pending_count ? '先核对候选和人工发现，再决定批次用途。' : data.value.evidence.confirmed_target_records ? '本轮存在已确认外观缺陷，先分选，再由人员核定等级和用途。' : '本轮未确认两类目标；这不是整批果实无缺陷或自动 A 级结论。')
function setTab(value) { router.replace({ path: route.path, query: { ...route.query, tab: value } }) }
function accept(value) {
  if (String(value.batch?.id) !== String(props.batchId)) throw new Error('工作台返回了不同批次。')
  if (data.value?.fingerprint !== value.fingerprint) { publicationPreview.value = null; publication.confirmed = false; assessment.scope = false; assessment.use = false }
  data.value = value
  for (const row of value.evidence.records) if (!(row.inspection_id in sampleCodes)) sampleCodes[row.inspection_id] = row.sample_code || ''
}
async function load() {
  if (loading.value) return; loading.value = true; error.value = ''
  const local = controller
  try { const value = await getWorkbench(props.batchId, { signal: local.signal }); if (!local.signal.aborted) accept(value) }
  catch (e) { if (!local.signal.aborted) error.value = e.message }
  finally { if (!local.signal.aborted) loading.value = false }
}
async function recent() { const local = controller; try { const v = await listInspections(props.batchId, { signal: local.signal }); if (!local.signal.aborted) recentImages.value = v } catch (e) { if (!local.signal.aborted) error.value = e.message } }
async function action(fn, message) {
  if (busy.value) return; busy.value = true; error.value = ''; notice.value = ''; const local = controller
  try { if (!operator.value.trim()) throw new Error('请填写本次确认人。'); const result = await fn(local.signal); if (local.signal.aborted) return; if (!result.persisted) throw new Error('接口未确认保存，请先刷新核对。'); const back = await getWorkbench(props.batchId, { signal: local.signal }); if (!local.signal.aborted) { accept(back); notice.value = message } }
  catch (e) { if (!local.signal.aborted) error.value = `${e.message} 连接中断时请先刷新核对记录。` }
  finally { if (!local.signal.aborted) busy.value = false }
}
function newRound() {
  action(signal => {
    if (!round.confirmed || !round.sampling_note.trim()) throw new Error('请填写抽检范围并确认建立新轮次。')
    return createRound(props.batchId, roundIntent({ name: round.name.trim(), sampling_note: round.sampling_note.trim(), operator: operator.value.trim(), new_round_confirmed: true, expected_round_id: data.value.round?.id || 0 }), { signal })
  }, '本轮抽检已建立。后续新上传图片自动纳入本轮；旧评定与记录仍保留为历史。')
}
async function attachSelected() {
  if (busy.value || !data.value?.round) return; busy.value = true; error.value = ''; const local = controller
  try {
    for (const id of [...selectedImages.value]) { const r = await bindImage(props.batchId, data.value.round.id, { inspection_id: Number(id), sample_code: '', expected_fingerprint: data.value.evidence.fingerprint }, { signal: local.signal }); if (local.signal.aborted) return; accept(r.workbench); selectedImages.value = selectedImages.value.filter(v => v !== id) }
    notice.value = '所选历史图片已纳入当前轮次。相同像素图片去重统计，不自动视为多个果实。'
  } catch (e) { if (!local.signal.aborted) error.value = e.message }
  finally { if (!local.signal.aborted) busy.value = false }
}
async function sample(row) {
  if (busy.value) return; busy.value = true; error.value = ''; const local = controller
  try { const r = await bindImage(props.batchId, data.value.round.id, { inspection_id: row.inspection_id, sample_code: sampleCodes[row.inspection_id] || '', expected_fingerprint: data.value.evidence.fingerprint }, { signal: local.signal }); if (!local.signal.aborted) { accept(r.workbench); notice.value = '样品编号已保存；已有评定将按实际证据版本核对是否过期。' } }
  catch (e) { if (!local.signal.aborted) error.value = e.message } finally { if (!local.signal.aborted) busy.value = false }
}
async function review(id) { setTab('inspection'); await nextTick(); inspection.value?.openRecord(id) }
function approve() {
  action(signal => {
    const issue = assessmentProblem(assessment.final_grade, assessment.intended_use, data.value.evidence); if (issue) throw new Error(issue)
    if (!assessment.scope || !assessment.use) throw new Error('请确认抽检证据范围和拟定用途。')
    return saveAssessment(props.batchId, assessmentIntent({ round_id: data.value.round.id, expected_fingerprint: data.value.evidence.fingerprint, expected_assessment_id: data.value.assessment?.id || 0, final_grade: assessment.final_grade, intended_use: assessment.intended_use, grade_basis: assessment.grade_basis.trim(), operator: operator.value.trim(), scope_confirmed: true, use_confirmed: true }), { signal })
  }, '批次评定已批准并回读。加工页将自动使用此评定的等级，不再重复填写。')
}
async function preview() {
  if (busy.value) return; busy.value = true; error.value = ''; const local = controller
  try { const v = await previewSummary(props.batchId, { publish_grade: publication.grade, publish_use: publication.use }, { signal: local.signal }); if (!local.signal.aborted) { publicationPreview.value = v; publication.confirmed = false } }
  catch (e) { if (!local.signal.aborted) error.value = e.message } finally { if (!local.signal.aborted) busy.value = false }
}
function publish() {
  action(signal => {
    if (!publicationPreview.value || !publication.confirmed) throw new Error('请先预览，并明确批准公开。')
    return publishSummary(props.batchId, publicationIntent({ expected_fingerprint: publicationPreview.value.source_fingerprint, publish_grade: publication.grade, publish_use: publication.use, confirm_public_summary: true, operator: operator.value.trim() }), { signal })
  }, '白名单摘要已发布。后续来源更新时，公开页将保留历史快照并提示待更新。')
}
watch(() => [publication.grade, publication.use], () => { publicationPreview.value = null; publication.confirmed = false })
watch(() => props.batchId, () => { controller.abort(); controller = new AbortController(); data.value = null; loading.value = false; busy.value = false; error.value = ''; notice.value = ''; selectedImages.value = []; recentImages.value = []; Object.keys(sampleCodes).forEach(k => delete sampleCodes[k]); Object.assign(assessment, { final_grade: '', intended_use: '', grade_basis: '', scope: false, use: false }); load(); recent() }, { immediate: true })
onBeforeUnmount(() => controller.abort())
</script>
<template>
  <div class="q4-workbench">
    <header class="q4-heading"><div><p class="eyebrow">BATCH QUALITY WORKSPACE · 批次品控</p><h1>{{ data?.batch.batch_code || '正在读取批次' }}</h1><p>{{ data ? `${data.batch.product} · ${data.batch.variety || '品种待记录'} · ${data.batch.origin || '产地待记录'}` : '以同一批次的证据驱动后续操作' }}</p></div><div class="button-row"><RouterLink class="btn secondary" to="/batches">批次列表</RouterLink><button class="btn secondary" :disabled="loading || busy" @click="load">{{ loading ? '读取中…' : '刷新实际记录' }}</button></div></header>
    <p v-if="error" class="notice danger" role="alert">{{ error }}</p><p v-if="notice" class="notice success" role="status">{{ notice }}</p>
    <section v-if="!data && !loading" class="q4-panel"><h2>工作台尚未就绪</h2><p>V4 需要完成代码安装、增量建表以及 Java 重启。原质检数据不应删除或重新初始化。</p><RouterLink class="btn secondary" :to="`/inspection?batch_id=${batchId}`">查看原质检入口</RouterLink></section>
    <template v-if="data">
      <section class="q4-hero"><div><span class="q4-eyebrow">{{ data.assessment_current ? '当前已批准业务结论' : '等待有效批次评定' }}</span><h2>{{ data.headline }}</h2><p>{{ data.evidence_summary }}</p><p class="q4-hero-use">拟定用途 · {{ data.use_label }}</p><span class="q4-scope">{{ data.round?.sampling_note || '尚未填写抽检范围，不自动推导批次等级。' }}</span></div><aside><small>当前待办</small><strong>{{ data.next_action.label }}</strong><button class="q4-next" @click="setTab(data.next_action.tab)">处理下一步 <span>→</span></button></aside></section>
      <nav class="q4-tabs" aria-label="批次工作台步骤"><button v-for="[value,label] in tabs" :key="value" :class="{ active: tab === value }" :aria-current="tab === value ? 'page' : undefined" @click="setTab(value)">{{ label }}</button></nav>
      <section v-show="tab === 'overview'">
        <div class="q4-stat-grid"><article><span>本轮去重照片</span><strong>{{ data.evidence.unique_photo_count }}<small> 张</small></strong><p>{{ data.evidence.record_count }} 条原始记录</p></article><article><span>已完成人工复核</span><strong>{{ data.evidence.reviewed_count }}<small> 条</small></strong><p>{{ data.evidence.pending_count }} 条待复核</p></article><article><span>有明确编号的样品</span><strong>{{ data.evidence.identified_sample_count ?? '未确认' }}</strong><p>同果多角度使用相同编号</p></article><article><span>冷链待核查</span><strong>{{ data.coldchain.unresolved_alerts }}<small> 条告警</small></strong><p>{{ data.coldchain.ongoing_episodes }} 段持续异常</p></article></div>
        <div class="q4-step-grid"><button v-for="(step,i) in workflowSteps" :key="step.id" @click="setTab(step.id)"><span class="q4-step-number">0{{ i+1 }}</span><h3>{{ step.title }}</h3><p>{{ step.text }}</p><span class="text-link">查看并处理 →</span></button></div>
        <div class="q4-two"><section class="q4-panel"><h3>本轮检查发现</h3><p>{{ evidenceMessage }}</p><div class="q4-thumb-grid"><button v-for="row in data.evidence.records.slice(0,6)" :key="row.inspection_id" @click="review(row.inspection_id)"><img :src="row.result_url" :alt="`检测记录${row.inspection_id}`" loading="lazy" /><span>#{{ row.inspection_id }} · {{ row.reviewed ? row.confirmed_target ? '已确认缺陷' : '未确认目标' : '待复核' }}</span></button></div><p v-if="!data.evidence.record_count" class="q4-empty">先建立抽检轮次，再上传图片。</p></section><section class="q4-panel"><h3>处理依据与限制</h3><p>{{ data.scope_note }}</p><p>用途是业务选择，不是食品可食用性判断；C 级不能直接解释为适合榨汁或制干。</p><p v-if="data.assessment">当前显示评定 #{{ data.assessment.id }} / 第 {{ data.assessment.revision }} 版。<br />{{ data.assessment.grade_basis }}</p><p v-if="data.coldchain.hold" class="notice warning">已有冷链待核查事项。初始外观等级保留，但不能忽略后续异常继续采用工艺。</p></section></div>
      </section>
      <section v-show="tab === 'inspection'">
        <section class="q4-panel"><div class="section-row"><h3>{{ data.round ? `当前抽检：${data.round.name}` : '先建立本轮抽检' }}</h3><span class="badge neutral">{{ data.round ? `轮次 #${data.round.id}` : '未建立' }}</span></div><p>{{ data.round?.sampling_note }}</p><details :open="!data.round"><summary>{{ data.round ? '建立下一轮抽检（当前评定将需重新核定）' : '填写抽检范围' }}</summary><div class="form-grid"><label>轮次名称<input v-model="round.name" maxlength="80" :disabled="busy" /></label><label>确认人<input v-model="operator" maxlength="50" :disabled="busy" /></label><label class="full">抽检范围与代表性说明<textarea v-model="round.sampling_note" maxlength="2000" rows="2" :disabled="busy" placeholder="例如从本批次不同箱位抽取样品；明确实际检查范围，不以图片数代替果实数" /></label><label class="q4-check full"><input v-model="round.confirmed" type="checkbox" :disabled="busy" /><span>确认建立新轮次，后续上传图片自动归入本轮；历史记录不清空。</span></label><button class="btn" :disabled="busy || !round.confirmed" @click="newRound">建立抽检轮次</button></div></details></section>
        <InspectionWorkspace v-if="data.round" :key="data.round.id" ref="inspection" :batch="batch" embedded @changed="load" />
        <section v-if="data.round" class="q4-panel"><h3>本轮证据与样品编号</h3><p>同一颗苹果的多角度照片使用同一编号；不能识别独立样品时留空，系统只统计照片，不计算果实不良率。</p><div class="table-wrap"><table><thead><tr><th>图片记录</th><th>复核结论</th><th>样品编号</th><th>操作</th></tr></thead><tbody><tr v-for="row in data.evidence.records" :key="row.inspection_id"><td>#{{ row.inspection_id }}<span class="cell-sub">{{ row.model_version }}</span></td><td>{{ row.reviewed ? row.confirmed_target ? '已确认外观缺陷' : '未确认两类目标' : '待复核' }}</td><td><input v-model="sampleCodes[row.inspection_id]" :disabled="busy" maxlength="64" :aria-label="`记录${row.inspection_id}样品编号`" placeholder="例如 APPLE-01；可留空" /></td><td><button class="btn small secondary" :disabled="busy" @click="sample(row)">保存编号</button> <button class="btn small secondary" @click="review(row.inspection_id)">查看与复核</button></td></tr></tbody></table></div><details><summary>纳入已有检测记录（近期100条）</summary><button class="btn secondary" @click="recent">刷新可选记录</button><div class="q4-pick-list"><label v-for="r in availableImages" :key="r.inspection_id" class="q4-check"><input v-model="selectedImages" type="checkbox" :value="r.inspection_id" :disabled="busy" /><span>#{{ r.inspection_id }} · {{ r.created_at }} · {{ r.review_revision ? '已有复核' : '未复核' }}</span></label></div><button class="btn" :disabled="busy || !selectedImages.length" @click="attachSelected">纳入所选记录</button></details></section>
      </section>
      <section v-show="tab === 'assessment'" class="q4-panel"><div class="section-row"><h2>确认本轮批次业务评定</h2><span class="badge" :class="data.assessment_current ? 'ready' : 'pending'">{{ data.assessment_current ? '当前评定有效' : '需要人工核定' }}</span></div><p>{{ data.evidence_summary }}</p><p>{{ evidenceMessage }}</p><div v-if="data.assessment" class="q4-source-banner"><p>已保存评定 #{{ data.assessment.id }} / 第 {{ data.assessment.revision }} 版 · {{ data.assessment.final_grade }} · {{ data.use_label }}<br />确认人：{{ data.assessment.approved_by }} · {{ data.assessment.created_at }}</p><b>{{ data.assessment_current ? '可作为后续业务输入' : '证据已更新，不再用于新工艺' }}</b></div><div class="form-grid"><label>人工业务等级<select v-model="assessment.final_grade" :disabled="busy"><option value="">请选择，不由最后一张图片自动决定</option><option v-for="[v,l] in GRADE_OPTIONS" :key="v" :value="v">{{ v }} · {{ l }}</option></select></label><label>拟定用途<select v-model="assessment.intended_use" :disabled="busy"><option value="">请选择</option><option v-for="[v,l] in USE_OPTIONS" :key="v" :value="v">{{ l }}</option></select></label><label>批次确认人<input v-model="operator" maxlength="50" :disabled="busy" /></label><div><button class="btn secondary" :disabled="busy" @click="assessment.grade_basis = evidenceDraft(data)">生成证据摘要草稿</button></div><label class="full">评定依据、范围与用途限制<textarea v-model="assessment.grade_basis" :disabled="busy" maxlength="3000" rows="4" placeholder="填写实际业务依据；不使用未检测的糖度、农残、保质期作为模型结论" /></label><label class="q4-check full"><input v-model="assessment.scope" type="checkbox" :disabled="busy" /><span>已核对本轮全部图片复核及抽检范围，不将单图观察自动外推为整批合格证明。</span></label><label class="q4-check full"><input v-model="assessment.use" type="checkbox" :disabled="busy" /><span>确认所选用途为本次拟定业务方向；不把用途待评估当作已经批准具体食品加工。</span></label><button class="btn" :disabled="busy || !data.round || data.evidence.pending_count > 0 || !assessment.scope || !assessment.use" @click="approve">保存第 {{ (data.assessment?.revision || 0)+1 }} 版批次评定</button><button class="btn secondary" :disabled="!data.assessment_current" @click="setTab('processing')">已核定，进入加工品控 →</button></div><p class="footnote">A/B/C/REJECT 为本项目人工业务档位。历史 D 不自动转换；未检出目标不自动核定 A。</p><details v-if="data.assessment_history?.length"><summary>批次评定历史（最近50版）</summary><article v-for="a in data.assessment_history" :key="a.id" class="q4-history"><b>第 {{ a.revision }} 版 · {{ a.final_grade }} · 抽检 #{{ a.round_id }}</b><span>{{ a.created_at }} · {{ a.approved_by }}</span><p>{{ a.grade_basis }}</p></article><p v-if="data.assessment_history_truncated">页面仅显示最近50版，完整历史保留在数据库。</p></details></section>
      <section v-show="tab === 'processing'"><AssessmentProcessing :workbench="data" @changed="accept" /></section>
      <section v-show="tab === 'coldchain'"><section class="q4-panel"><h2>同批次冷链观测与处置</h2><p>入厂外观等级：{{ data.assessment?.final_grade || '尚未核定' }}；冷链异常不改写历史外观评定，而是形成当前核查待办。</p><div class="q4-two"><article v-for="s in data.coldchain.sources" :key="s.source" class="q4-source-banner"><div><h3>{{ s.source === 'sensor' ? '传感器来源' : '模拟数据' }}</h3><p>{{ s.reading_count }} 条；观测结果：{{ coldLabels[s.status] || s.status }}</p><p v-if="s.latest">最近观测：{{ s.latest.timestamp }}<br />{{ s.latest.temperature }} ℃ / {{ s.latest.humidity }} %</p><p v-if="s.analysis?.reason">{{ s.analysis.reason }}</p><p v-if="s.has_historical_gap" class="notice warning">历史存在观测断点，请检查原区段。</p></div></article></div><p v-if="data.coldchain.unverified_history" class="notice warning">检测到安装前保存的读数，但未保存对应完整 B 观测结果。可重新导入同一原始 CSV，由原有幂等接口核对并补存 B 结果；不要新编造读数。</p><p class="footnote">本版每批次每种来源只对应一个监测对象；不同设备／监测点不能混传。实际读数时效由操作员核对，不把历史末点正常显示为“当前实时正常”。</p><button class="btn secondary" @click="load">处置或导入后刷新批次待办</button></section><ColdChainWorkspace :batch="batch" @changed="load" /></section>
      <section v-show="tab === 'report'"><section class="q4-panel"><h2>内部报告</h2><p>报告读取同一份批次评定、工艺来源、采用历史、实际执行和冷链记录。未执行环节不会自动补全。</p><RouterLink class="btn" :to="`/batches/${batchId}/report`">打开完整内部报告／打印</RouterLink> <RouterLink class="btn secondary" :to="`/traceability?batch_id=${batchId}`">二维码与公开溯源</RouterLink></section><section class="q4-panel"><h2>批准公开的业务摘要</h2><p>先预览，再发布。默认不公开等级和用途；原图、模型分数、内部备注和操作员不进入公开快照。</p><label>发布确认人<input v-model="operator" :disabled="busy" maxlength="50" /></label><label class="q4-check"><input v-model="publication.grade" type="checkbox" :disabled="busy" /><span>额外公开人工业务等级</span></label><label class="q4-check"><input v-model="publication.use" type="checkbox" :disabled="busy" /><span>额外公开拟定用途</span></label><button class="btn secondary" :disabled="busy || !data.assessment_current" @click="preview">预览实际将公开的内容</button><article v-if="publicationPreview" class="q4-preview"><h3>{{ publicationPreview.snapshot.batch_code }}</h3><p>{{ publicationPreview.snapshot.quality_summary }}</p><p>{{ publicationPreview.snapshot.evidence_scope }}</p><p v-if="publicationPreview.snapshot.grade">{{ publicationPreview.snapshot.grade }}</p><p v-if="publicationPreview.snapshot.intended_use">{{ publicationPreview.snapshot.intended_use }}</p><p>{{ publicationPreview.snapshot.processing_summary }}</p><p>{{ publicationPreview.snapshot.coldchain_summary }}</p><p v-for="s in publicationPreview.snapshot.coldchain_sources" :key="s.source">{{ s.source === 'sensor' ? '传感器来源' : '模拟数据' }}：{{ s.reading_count }} 条</p><p>{{ publicationPreview.snapshot.scope_note }}</p><label class="q4-check"><input v-model="publication.confirmed" type="checkbox" :disabled="busy" />明确批准公开上述快照</label><button class="btn" :disabled="busy || !publication.confirmed" @click="publish">保存公开快照</button></article><p v-if="data.publication" class="notice" :class="data.publication.current ? 'success' : 'warning'">{{ data.publication.current ? '已发布摘要与当前记录一致' : '已有公开摘要，但来源更新后尚未重新发布' }} · {{ data.publication.published_at }}</p><a class="btn secondary" :href="`/trace/${encodeURIComponent(data.batch.batch_code)}`" target="_blank" rel="noopener">打开实际公开页 ↗</a></section></section>
      <p class="q4-footer-note">本轮为受控本机业务工作台；放行审批、设备控制、账号权限和多监测点隔离不在此版本中宣称完成。</p>
    </template>
  </div>
</template>
