<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getWorkbench, synchronizeInitial, createRound, bindImage, saveAssessment, previewSummary, publishSummary } from '../../api/batch-workbench.js'
import { gradeStatusFor } from '../../utils/presentation.js'
import { formatTime } from '../../utils/batch.js'
import { listInspections } from '../../api/inspection.js'
import { GRADE_OPTIONS, USE_OPTIONS, assessmentProblem, evidenceDraft, steps, writeIntent, defaultUse } from '../../utils/batch-workbench.js'
import InspectionWorkspace from '../inspection/InspectionWorkspace.vue'
import ColdChainWorkspace from '../coldchain/ColdChainWorkspace.vue'
import AssessmentProcessing from '../../components/batch/AssessmentProcessing.vue'
import TraceWorkspace from '../trace/TraceWorkspace.vue'
import '../../styles/batch-workbench.css'
const props = defineProps({ batchId: { type: [String, Number], required: true } })
const route = useRoute(), router = useRouter()
const data = ref(null), loading = ref(false), busy = ref(false), error = ref(''), notice = ref(''), operator = ref('')
const tabContent = ref(null)
const inspection = ref(null), recentImages = ref([]), selectedImages = ref([]), sampleCodes = reactive({}), publicationPreview = ref(null)
const round = reactive({ name: '入厂抽检', sampling_note: '', confirmed: false })
const assessment = reactive({ final_grade: '', intended_use: '', grade_basis: '', scope: false, use: false })
const publication = reactive({ grade: false, use: false, confirmed: false })
const roundIntent = writeIntent(), assessmentIntent = writeIntent(), publicationIntent = writeIntent()
try { operator.value = localStorage.getItem('zhijian.operator') || '' } catch { /* optional remembered operator */ }
let controller = new AbortController()
const tabs = [['overview', '品控总览'], ['inspection', '照片质检'], ['assessment', '检查与修正'], ['processing', '加工品控'], ['coldchain', '冷链观测'], ['report', '报告与溯源']]
const tab = computed(() => tabs.some(([v]) => v === route.query.tab) ? route.query.tab : 'overview')
const batch = computed(() => data.value ? { ...data.value.batch, batch_id: data.value.batch.id } : null)
const workflowSteps = computed(() => steps(data.value))
const availableImages = computed(() => recentImages.value.filter(r => !data.value?.evidence.records.some(e => e.inspection_id === r.inspection_id)))
const coldLabels = { not_started: '尚无观测', unverified: '待重新核验 B 结果', normal: '末次观测未触发超温', insufficient_data: '持续时间证据不足', pending: '超温持续计时中', active: '已触发持续超温' }
const grade = computed(() => data.value?.assessment_current ? data.value.assessment.final_grade : '—')
const gradeSource = computed(() => data.value?.assessment?.source === 'ai_initial' ? 'AI 初评' : '人工修正')
const gradeStatus = computed(() => gradeStatusFor(data.value))
const evidenceMessage = computed(() => gradeStatus.value.message)
function setTab(value) { return router.replace({ path: route.path, query: { ...route.query, tab: value } }) }
async function goNext() {
  const target = data.value?.next_action?.tab
  if (!tabs.some(([value]) => value === target)) return
  if (tab.value !== target) await setTab(target)
  await nextTick()
  // Let Vue Router finish its default scroll before positioning the action area.
  await new Promise(resolve => requestAnimationFrame(resolve))
  tabContent.value?.focus({ preventScroll: true })
  tabContent.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function accept(value) {
  if (String(value.batch?.id) !== String(props.batchId)) throw new Error('工作台返回了不同批次。')
  if (data.value?.fingerprint !== value.fingerprint) { publicationPreview.value = null; publication.confirmed = false; assessment.scope = false; assessment.use = false }
  if (data.value?.assessment?.id !== value.assessment?.id) {
    assessment.final_grade = value.assessment?.final_grade || ''
    assessment.intended_use = value.assessment?.intended_use || ''
    assessment.grade_basis = ''
  }
  data.value = value
  for (const row of value.evidence.records) if (!(row.inspection_id in sampleCodes)) sampleCodes[row.inspection_id] = row.sample_code || ''
}
async function load() {
  if (loading.value) return; loading.value = true; error.value = ''
  const local = controller
  try { const value = await synchronizeInitial(props.batchId, { signal: local.signal }); if (!local.signal.aborted) accept(value) }
  catch (e) { if (!local.signal.aborted) error.value = e.message }
  finally { if (!local.signal.aborted) loading.value = false }
}
async function recent() { const local = controller; try { const v = await listInspections(props.batchId, { signal: local.signal }); if (!local.signal.aborted) recentImages.value = v } catch (e) { if (!local.signal.aborted) error.value = e.message } }
async function action(fn, message) {
  if (busy.value) return; busy.value = true; error.value = ''; notice.value = ''; const local = controller
  try { if (!operator.value.trim()) throw new Error('请填写操作人，便于追溯本次记录。'); try { localStorage.setItem('zhijian.operator', operator.value.trim()) } catch { /* optional storage */ } const result = await fn(local.signal); if (local.signal.aborted) return; if (!result.persisted) throw new Error('接口未确认保存，请先刷新核对。'); const back = await getWorkbench(props.batchId, { signal: local.signal }); if (!local.signal.aborted) { accept(back); notice.value = message } }
  catch (e) { if (!local.signal.aborted) error.value = `${e.message} 连接中断时请先刷新核对记录。` }
  finally { if (!local.signal.aborted) busy.value = false }
}
function newRound() {
  action(signal => {
    if (!round.confirmed || !round.sampling_note.trim()) throw new Error('请填写抽检范围并确认建立新轮次。')
    return createRound(props.batchId, roundIntent({ name: round.name.trim(), sampling_note: round.sampling_note.trim(), operator: operator.value.trim(), new_round_confirmed: true, expected_round_id: data.value.round?.id || 0 }), { signal })
  }, '新的质检轮次已建立。上传照片后自动生成本轮等级。')
}
async function attachSelected() {
  if (busy.value || !data.value?.round) return; busy.value = true; error.value = ''; const local = controller
  try {
    for (const id of [...selectedImages.value]) { const r = await bindImage(props.batchId, data.value.round.id, { inspection_id: Number(id), sample_code: '', expected_fingerprint: data.value.evidence.fingerprint }, { signal: local.signal }); if (local.signal.aborted) return; accept(r.workbench); selectedImages.value = selectedImages.value.filter(v => v !== id) }
    notice.value = '历史照片已纳入本轮，初评等级已同步。'
  } catch (e) { if (!local.signal.aborted) error.value = e.message }
  finally { if (!local.signal.aborted) busy.value = false }
}
async function sample(row) {
  if (busy.value) return; busy.value = true; error.value = ''; const local = controller
  try { const r = await bindImage(props.batchId, data.value.round.id, { inspection_id: row.inspection_id, sample_code: sampleCodes[row.inspection_id] || '', expected_fingerprint: data.value.evidence.fingerprint }, { signal: local.signal }); if (!local.signal.aborted) { accept(r.workbench); notice.value = '样品编号已保存，等级来源已同步。' } }
  catch (e) { if (!local.signal.aborted) error.value = e.message } finally { if (!local.signal.aborted) busy.value = false }
}
async function review(id) { setTab('inspection'); await nextTick(); inspection.value?.openRecord(id) }
function approve() {
  action(signal => {
    const issue = assessmentProblem(assessment.final_grade, assessment.intended_use, data.value.evidence); if (issue) throw new Error(issue)
    if (assessment.grade_basis.trim().length < 2) throw new Error('请说明等级或用途的修正原因。')
    return saveAssessment(props.batchId, assessmentIntent({ round_id: data.value.round.id, expected_fingerprint: data.value.evidence.fingerprint, expected_assessment_id: data.value.assessment?.id || 0, final_grade: assessment.final_grade, intended_use: assessment.intended_use, grade_basis: assessment.grade_basis.trim(), operator: operator.value.trim(), scope_confirmed: true, use_confirmed: true }), { signal })
  }, '修正已保存。后续品控读取新等级，原始 AI 结果和历史记录保留。')
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
  }, '公开摘要已发布，扫码可查看。来源更新后会提示重新发布。')
}
watch(() => [publication.grade, publication.use], () => { publicationPreview.value = null; publication.confirmed = false })
watch(() => props.batchId, () => { controller.abort(); controller = new AbortController(); data.value = null; loading.value = false; busy.value = false; error.value = ''; notice.value = ''; selectedImages.value = []; recentImages.value = []; Object.keys(sampleCodes).forEach(k => delete sampleCodes[k]); Object.assign(assessment, { final_grade: '', intended_use: '', grade_basis: '', scope: false, use: false }); load(); recent() }, { immediate: true })
onBeforeUnmount(() => controller.abort())
</script>
<template>
  <div class="q4-workbench">
    <header class="q4-heading">
      <div><p class="eyebrow">批次工作台</p><h1>{{ data?.batch.batch_code || '正在读取批次' }}</h1><p>{{ data ? `${data.batch.product} · ${data.batch.variety || '品种待记录'} · ${data.batch.origin || '产地待记录'}` : '正在读取批次信息…' }}</p></div>
      <div class="button-row"><RouterLink class="btn secondary" to="/batches">切换批次</RouterLink><button class="btn secondary" :disabled="loading || busy" @click="load">{{ loading ? '更新中…' : '刷新' }}</button></div>
    </header>
    <p v-if="error" class="notice danger" role="alert">{{ error }}</p><p v-if="notice" class="notice success" role="status">{{ notice }}</p>
    <section v-if="!data" class="q4-panel"><h2>{{ loading ? '正在汇总批次记录…' : '暂时无法读取工作台' }}</h2><p v-if="!loading">请检查服务连接后重试。已有业务记录仍保存在数据库中。</p><button v-if="!loading" class="btn" @click="load">重新加载</button></section>
    <template v-if="data">
      <section class="q4-hero" :data-grade="grade">
        <div class="q4-hero-main"><div class="q4-grade-tile"><small>{{ data.assessment_current ? gradeSource : gradeStatus.label }}</small><strong>{{ grade === 'REJECT' ? '拒收' : grade }}</strong><span>{{ data.assessment_current ? '本轮等级' : '暂无有效等级' }}</span></div><div><span class="q4-eyebrow">{{ gradeStatus.detail }}</span><h2>{{ gradeStatus.title }}</h2><p>{{ data.evidence_summary }}</p><p class="q4-hero-use">处理方向 · {{ data.use_label }}</p><button v-if="data.assessment_current" class="q4-inline" @click="setTab('assessment')">检查与修正等级 →</button></div></div>
        <aside><small>下一步</small><strong>{{ data.next_action.label }}</strong><button class="q4-next" @click="goNext">前往处理 <span>→</span></button></aside>
      </section>
      <nav class="q4-tabs" aria-label="批次业务页面"><button v-for="[value,label] in tabs" :key="value" :class="{ active: tab === value }" :aria-current="tab === value ? 'page' : undefined" @click="setTab(value)">{{ label }}<span v-if="value === 'assessment'" class="q4-optional">可选</span></button></nav>
      <div ref="tabContent" tabindex="-1" aria-label="当前业务内容">
      <section v-show="tab === 'overview'">
        <div class="q4-stat-grid"><article><span>已检测照片</span><strong>{{ data.evidence.unique_photo_count }}<small> 张</small></strong><p>本轮去重统计</p></article><article><span>人工检查</span><strong>{{ data.evidence.reviewed_count }}<small> 条</small></strong><p>{{ data.evidence.pending_count ? `${data.evidence.pending_count} 条已标记待复查` : '可在 AI 初评后按需检查' }}</p></article><article><span>加工进度</span><strong class="q4-stat-text">{{ data.quality_disposition_recorded ? '已处置' : data.processing_executed ? '已执行' : data.processing.adopted && data.processing.current ? '已采用' : data.processing.current ? '有建议' : '待处理' }}</strong><p>按实际作业记录更新</p></article><article><span>冷链告警</span><strong>{{ data.coldchain.unresolved_alerts }}<small> 条待处理</small></strong><p>{{ data.coldchain.reading_count }} 条观测记录</p></article></div>
        <div class="q4-step-grid"><button v-for="step in workflowSteps" :key="step.id" @click="setTab(step.id)"><h3>{{ step.title }}</h3><p>{{ step.text }}</p><span class="text-link">查看 →</span></button></div>
        <div class="q4-two"><section class="q4-panel"><div class="section-row"><h3>本轮质检照片</h3><button class="q4-inline" @click="setTab('inspection')">上传更多 →</button></div><p>{{ evidenceMessage }}</p><div class="q4-thumb-grid"><button v-for="row in data.evidence.records.slice(0,6)" :key="row.inspection_id" @click="review(row.inspection_id)"><img :src="row.result_url" :alt="`检测记录${row.inspection_id}`" loading="lazy" /><span>{{ row.effective_grade }} · {{ row.grade_source === 'human_corrected' ? '人工修正' : 'AI 初评' }}</span></button></div><div v-if="!data.evidence.record_count" class="q4-empty"><p>还没有质检照片</p><button class="btn" @click="setTab('inspection')">上传质检照片</button></div></section><section class="q4-panel"><h3>处理依据</h3><p>{{ data.scope_note }}</p><p v-if="data.assessment">{{ data.assessment.grade_basis }}</p><p v-if="data.coldchain.hold" class="notice warning">存在冷链待核查事项，处理后才能继续采用工艺。</p><details><summary>查看等级口径</summary><p>A：未检出当前目标缺陷；B：轻度问题；C：用途待评估；REJECT：拒收。AI 拒收初评与实际拒收处置分别记录。</p><p>当前模型检测擦伤和虫害，初评规则依据候选框数量和范围。没有检测框仍会给 A 初评，但其他缺陷需要补拍或人工修正。当前等级不是食品安全检测结果。</p><p>{{ data.round?.sampling_note }}</p></details></section></div>
      </section>
      <section v-show="tab === 'inspection'">
        <InspectionWorkspace ref="inspection" :batch="batch" embedded @changed="load" />
        <details class="q4-panel"><summary>抽检轮次、样品编号与历史照片</summary><p>当前轮次：{{ data.round?.name || '首次上传时自动建立' }}。同一颗苹果的不同角度可使用同一个样品编号。</p>
          <div v-if="data.round" class="table-wrap"><table><thead><tr><th>记录</th><th>有效等级</th><th>样品编号</th><th>操作</th></tr></thead><tbody><tr v-for="row in data.evidence.records" :key="row.inspection_id"><td>#{{ row.inspection_id }}</td><td>{{ row.effective_grade }} · {{ row.grade_source === 'human_corrected' ? '人工修正' : 'AI 初评' }}</td><td><input v-model="sampleCodes[row.inspection_id]" :disabled="busy" maxlength="64" :aria-label="`记录${row.inspection_id}样品编号`" placeholder="例如 APPLE-01，可留空" /></td><td><button class="btn small secondary" :disabled="busy" @click="sample(row)">保存编号</button> <button class="btn small secondary" @click="review(row.inspection_id)">查看</button></td></tr></tbody></table></div>
          <details v-if="data.round"><summary>纳入历史照片</summary><button class="btn secondary" @click="recent">刷新可选记录</button><div class="q4-pick-list"><label v-for="r in availableImages" :key="r.inspection_id" class="q4-check"><input v-model="selectedImages" type="checkbox" :value="r.inspection_id" :disabled="busy" /><span>#{{ r.inspection_id }} · {{ r.created_at }}</span></label></div><button class="btn" :disabled="busy || !selectedImages.length" @click="attachSelected">纳入所选照片</button></details>
          <details><summary>建立复检轮次</summary><div class="form-grid"><label>轮次名称<input v-model="round.name" maxlength="80" :disabled="busy" /></label><label>操作人<input v-model="operator" maxlength="50" :disabled="busy" /></label><label class="full">抽检范围<textarea v-model="round.sampling_note" maxlength="2000" rows="2" :disabled="busy" placeholder="说明这次实际抽检的箱位、样品和范围" /></label><label class="q4-check full"><input v-model="round.confirmed" type="checkbox" :disabled="busy" /><span>开始新的质检轮次，保留此前等级和作业历史。</span></label><button class="btn" :disabled="busy || !round.confirmed" @click="newRound">建立复检轮次</button></div></details>
        </details>
      </section>
      <section v-show="tab === 'assessment'" class="q4-panel">
        <div class="section-row"><div><p class="eyebrow">人工检查 · 可选</p><h2>检查与修正等级</h2></div><span class="badge" :class="data.assessment_current ? 'ready' : 'pending'">{{ data.assessment_current ? `${gradeSource}已生效` : '等待有效初评' }}</span></div>
        <p>AI 已自动给出等级并接入后续品控。需要调整时，填写修正原因；原始检测结果和之前的等级版本都会保留。</p>
        <div v-if="data.assessment" class="q4-source-banner"><div><small>当前有效来源</small><h3>{{ data.assessment.final_grade }} · {{ data.grade_label }}</h3><p>{{ gradeSource }} · 第 {{ data.assessment.revision }} 版 · {{ data.use_label }}</p></div><button class="btn secondary" :disabled="!data.assessment_current" @click="setTab('processing')">直接进入加工品控 →</button></div>
        <form class="form-grid" @submit.prevent="approve"><label>修正等级<select v-model="assessment.final_grade" :disabled="busy" @change="assessment.intended_use = defaultUse(assessment.final_grade)"><option value="">请选择</option><option v-for="[v,l] in GRADE_OPTIONS" :key="v" :value="v">{{ v }} · {{ l }}</option></select></label><label>处理方向<select v-model="assessment.intended_use" :disabled="busy"><option value="">请选择</option><option v-for="[v,l] in USE_OPTIONS" :key="v" :value="v">{{ l }}</option></select></label><label>检查人<input v-model="operator" maxlength="50" :disabled="busy" placeholder="本次检查人员" required /></label><label class="full">修正原因<textarea v-model="assessment.grade_basis" :disabled="busy" maxlength="3000" rows="3" required minlength="2" placeholder="说明看到的问题、分选结果或用途调整依据" /></label><div class="form-actions"><button class="btn" :disabled="busy || !data.evidence.record_count || data.evidence.pending_count > 0">保存修正</button><button class="btn secondary" type="button" @click="setTab('inspection')">查看照片与检测框</button></div></form>
        <details v-if="data.assessment_history?.length"><summary>查看 {{ data.assessment_history.length }} 版等级记录</summary><article v-for="a in data.assessment_history" :key="a.id" class="q4-history"><b>第 {{ a.revision }} 版 · {{ a.final_grade }} · {{ a.grade_policy_version?.startsWith('ai-batch-initial-v5') ? 'AI 初评' : '人工修正' }}</b><span>{{ formatTime(a.created_at) }} · {{ a.approved_by }}</span><p>{{ a.grade_basis }}</p></article><p v-if="data.assessment_history_truncated">此处显示最近 50 版，完整历史保留在数据库。</p></details>
      </section>
      <section v-show="tab === 'processing'"><AssessmentProcessing :workbench="data" @changed="accept" /></section>
      <section v-show="tab === 'coldchain'"><section class="q4-panel"><div class="section-row"><h2>冷链状态</h2><span class="badge" :class="data.coldchain.hold ? 'pending' : 'neutral'">{{ data.coldchain.hold ? '需处理' : data.coldchain.reading_count ? '已记录观测' : '等待观测' }}</span></div><p>入厂外观等级 {{ data.assessment?.final_grade || '未检测' }}；冷链异常产生独立待办，并约束后续工艺采用。</p><div class="q4-two"><article v-for="s in data.coldchain.sources" :key="s.source" class="q4-source-banner"><div><h3>{{ s.source === 'sensor' ? '传感器观测' : '模拟观测' }}</h3><p>{{ s.reading_count }} 条 · {{ coldLabels[s.status] || s.status }}</p><p v-if="s.latest">{{ s.latest.timestamp }} · {{ s.latest.temperature }} ℃ / {{ s.latest.humidity }} %</p><p v-if="s.has_historical_gap" class="notice warning">历史观测存在断点，请检查原区段。</p></div></article></div><p v-if="data.coldchain.unverified_history" class="notice warning">部分历史读数缺少完整分析结果，请重新导入原始 CSV 核验。</p></section><ColdChainWorkspace :batch="batch" @changed="load" /></section>
      <section v-show="tab === 'report'"><section class="q4-panel"><div class="section-row"><div><p class="eyebrow">同批次全程记录</p><h2>内部品控报告</h2></div><RouterLink class="btn" :to="`/batches/${batchId}/report`">查看报告 / 打印</RouterLink></div><p>统一汇总当前等级、加工建议及采用值、实际执行、冷链观测和异常处置。</p></section>
        <section class="q4-panel"><h2>发布溯源摘要</h2><p>选择公开内容，预览后发布。原图与内部备注保留在管理端。</p><label>发布人<input v-model="operator" :disabled="busy" maxlength="50" placeholder="填写本次发布人员" /></label><div class="button-row"><label class="q4-check"><input v-model="publication.grade" type="checkbox" :disabled="busy" /><span>公开品控等级</span></label><label class="q4-check"><input v-model="publication.use" type="checkbox" :disabled="busy" /><span>公开处理方向</span></label></div><button class="btn secondary" :disabled="busy || !data.assessment_current" @click="preview">预览公开内容</button>
          <article v-if="publicationPreview" class="q4-preview"><h3>{{ publicationPreview.snapshot.batch_code }}</h3><p>{{ publicationPreview.snapshot.quality_summary }}</p><p>{{ publicationPreview.snapshot.evidence_scope }}</p><p v-if="publicationPreview.snapshot.grade">品控等级：{{ publicationPreview.snapshot.grade }}</p><p v-if="publicationPreview.snapshot.intended_use">处理方向：{{ publicationPreview.snapshot.intended_use }}</p><p>{{ publicationPreview.snapshot.processing_summary }}</p><p>{{ publicationPreview.snapshot.coldchain_summary }}</p><label class="q4-check"><input v-model="publication.confirmed" type="checkbox" :disabled="busy" /><span>确认发布以上内容</span></label><button class="btn" :disabled="busy || !publication.confirmed" @click="publish">发布溯源摘要</button></article>
          <p v-if="data.publication" class="notice" :class="data.publication.current ? 'success' : 'warning'">{{ data.publication.current ? '公开摘要已与当前记录同步' : '记录已变化，公开摘要需要重新发布' }} · {{ formatTime(data.publication.published_at) }}</p>
        </section><TraceWorkspace :batch="batch" :refresh-key="`${data.fingerprint}:${data.publication?.id || ''}`" />
      </section>
      </div>
    </template>
  </div>
</template>
