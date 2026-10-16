<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { getInspectionReady, listInspections, getInspection, predictInspection, reviewInspection } from '../../api/inspection.js'
import BatchFlowStatus from '../../components/BatchFlowStatus.vue'
import InspectionViewer from '../../components/InspectionViewer.vue'
import { decisionFor, candidateCaption, reviewProblem, GRADE_OPTIONS, GRADE_LABELS } from '../../utils/engineering.js'
const props = defineProps({ batch: { type: Object, required: true }, embedded: { type: Boolean, default: false } })
const emit = defineEmits(['changed'])
const file = ref(null), preview = ref(''), requestId = ref(''), record = ref(null), history = ref([])
const ready = ref(null), busy = ref(false), refreshing = ref(false), error = ref(''), historyError = ref(''), notice = ref('')
const selected = ref(null), refreshKey = ref(0), phase = ref(''), historyLoaded = ref(false)
const form = reactive({ reviewer: '', remark: '', final_grade: '', conclusion: 'needs_recheck', publish_summary: false, candidates: [] })
const decisions = { confirmed: '确认目标', false_positive: '误检', duplicate: '重复框', uncertain: '不确定' }
const conclusions = { target_confirmed: '确认模型候选中的目标缺陷', manual_target_confirmed: '人工发现缺陷（模型未覆盖或漏检）', no_target_confirmed: '未确认目标（不代表合格）', needs_recheck: '尚不确定，需要复查' }
const prediction = computed(() => record.value?.prediction)
const detections = computed(() => prediction.value?.detections || [])
const decision = computed(() => decisionFor(record.value))
const confirmed = computed(() => form.candidates.filter(c => c.decision === 'confirmed'))
let controller = new AbortController(), disposed = false
function showError(e) { if (!disposed && e.name !== 'AbortError') error.value = e.message }
function date(value) { return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '未记录' }
function percent(value) { return Number.isFinite(Number(value)) ? `${(Number(value) * 100).toFixed(2)}%` : '未记录' }
function setRecord(value) {
  if (String(value.batch_id) !== String(props.batch.batch_id)) throw new Error('返回记录不属于当前批次，已停止展示。')
  record.value = value; selected.value = null
  const latest = value.latest_review
  Object.assign(form, { reviewer: latest?.reviewer || '', remark: '', final_grade: latest?.final_grade || '', conclusion: latest?.conclusion || 'needs_recheck', publish_summary: false })
  form.candidates = value.prediction.detections.map((_, i) => {
    const old = latest?.candidate_reviews?.find(c => c.candidate_index === i + 1)
    return { candidate_index: i + 1, decision: old?.decision || '', duplicate_of: old?.duplicate_of ?? null, note: '' }
  })
}
async function refresh() {
  if (refreshing.value || disposed) return
  const local = controller; refreshing.value = true; historyError.value = ''
  const result = await Promise.allSettled([getInspectionReady({ signal: local.signal }), listInspections(props.batch.batch_id, { signal: local.signal })])
  if (local.signal.aborted || disposed) return
  ready.value = result[0].status === 'fulfilled' ? result[0].value : null
  if (result[1].status === 'fulfilled') { history.value = result[1].value; historyLoaded.value = true }
  else { historyError.value = result[1].reason.message; historyLoaded.value = false }
  if (result[0].status !== 'fulfilled') showError(result[0].reason)
  refreshing.value = false; refreshKey.value++
}
function chooseFile(f) {
  if (busy.value || !f) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(f.type) || !f.size || f.size > 10 * 1024 * 1024) { error.value = '请选择不超过 10 MiB 的非空 JPG / PNG / WebP 图片。'; return }
  if (!globalThis.crypto?.randomUUID) { error.value = '请使用 localhost 或 HTTPS 打开网页，浏览器需要安全的请求编号生成能力。'; return }
  if (preview.value) URL.revokeObjectURL(preview.value)
  file.value = f; preview.value = URL.createObjectURL(f); requestId.value = crypto.randomUUID()
  record.value = null; error.value = ''; notice.value = ''; phase.value = ''
}
function choose(event) { chooseFile(event.target.files?.[0]); event.target.value = '' }
function dropped(event) { chooseFile(event.dataTransfer?.files?.[0]) }
async function detect() {
  if (busy.value || !file.value || !ready.value?.ready || record.value) return
  busy.value = true; error.value = ''; notice.value = ''; phase.value = '正在检测并生成等级…'
  const local = controller
  try {
    const saved = await predictInspection(props.batch.batch_id, file.value, { signal: local.signal, requestId: requestId.value })
    if (local.signal.aborted) return
    phase.value = '正在从后端重新读取保存结果…'
    const readback = await getInspection(saved.inspection_id, { signal: local.signal })
    if (local.signal.aborted) return
    if (readback.prediction.prediction_id !== saved.prediction.prediction_id) throw new Error('预测编号回读不一致，请保留诊断记录。')
    setRecord(readback); notice.value = `检测已保存，AI 初评为 ${readback.initial_assessment?.grade || readback.effective_grade} 级。等级已带入批次品控，可继续加工流程。`
    await refresh(); emit('changed')
  } catch (e) { showError(e) }
  finally { if (!disposed && !local.signal.aborted) { busy.value = false; phase.value = '' } }
}
async function openRecord(id) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  const local = controller
  try { const value = await getInspection(id, { signal: local.signal }); if (!local.signal.aborted) setRecord(value) }
  catch (e) { showError(e) }
  finally { if (!disposed && !local.signal.aborted) busy.value = false }
}
async function submitReview() {
  if (busy.value || !record.value) return
  const problem = reviewProblem(form, detections.value.length)
  if (problem) { error.value = problem; return }
  busy.value = true; error.value = ''; notice.value = ''
  const local = controller
  try {
    const saved = await reviewInspection(record.value.inspection_id, {
      expected_revision: record.value.review_revision, reviewer: form.reviewer.trim(), remark: form.remark.trim(),
      conclusion: form.conclusion, final_grade: form.final_grade || null, publish_summary: form.publish_summary,
      candidate_reviews: form.candidates.map(c => ({ ...c, duplicate_of: c.decision === 'duplicate' ? Number(c.duplicate_of) : null })),
    }, { signal: local.signal })
    const readback = await getInspection(saved.inspection_id, { signal: local.signal })
    if (local.signal.aborted) return
    if (readback.review_revision !== saved.review_revision) throw new Error('复核版本已再次更新，请重新打开记录确认。')
    setRecord(readback); notice.value = '检查结果已保存，批次等级与后续品控来源已同步更新。'; await refresh(); emit('changed')
  } catch (e) { showError(e) }
  finally { if (!disposed && !local.signal.aborted) busy.value = false }
}
function exportRecord() {
  const blob = new Blob([JSON.stringify(record.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob), a = document.createElement('a')
  a.href = url; a.download = `inspection-${record.value.inspection_id}.json`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
watch(() => props.batch.batch_id, () => {
  controller.abort(); controller = new AbortController()
  if (preview.value) URL.revokeObjectURL(preview.value)
  file.value = null; preview.value = ''; record.value = null; ready.value = null; history.value = []; historyLoaded.value = false
  busy.value = false; refreshing.value = false; error.value = ''; notice.value = ''; refresh()
}, { immediate: true })
defineExpose({ openRecord })
onBeforeUnmount(() => { disposed = true; controller.abort(); if (preview.value) URL.revokeObjectURL(preview.value) })
</script>
<template>
  <div class="e-inspection">
    <BatchFlowStatus v-if="!embedded" :batch-id="batch.batch_id" :refresh-key="refreshKey" />
    <div class="e-work-heading"><div><h2>照片质检</h2><p>上传照片获取外观初评，可在检测后检查和修正。</p></div><span class="badge" :class="ready?.ready ? 'ready' : 'pending'">{{ ready?.ready ? '可以开始检测' : '请先检查模型连接' }}</span></div>
    <p v-if="error" class="notice danger" role="alert">{{ error }}</p><p v-if="notice" class="notice success" role="status">{{ notice }}</p>
    <div class="e-inspection-grid">
      <section class="e-panel e-visual-panel"><div class="e-section-line"><h3>照片与检测结果</h3><span class="e-muted">{{ record ? `已保存 #${record.inspection_id}` : 'AI 自动初评' }}</span></div>
        <InspectionViewer v-if="record" :record="record" :selected="selected" @select="selected = $event" />
        <div v-else class="e-upload-zone" @dragover.prevent @drop.prevent="dropped"><img v-if="preview" :src="preview" alt="待上传照片的本地预览" /><div v-else><span class="e-upload-symbol" aria-hidden="true">＋</span><h3>上传一张苹果照片</h3><p>拖入图片，或使用下方按钮选择</p><small>建议单个苹果、光线均匀、缺陷区域清晰</small></div></div>
        <div class="e-upload-actions"><label class="e-file-button"><input :disabled="busy" type="file" accept="image/jpeg,image/png,image/webp" aria-label="选择质检照片" @change="choose" /><span class="btn secondary">{{ record ? '换一张图片' : '选择图片' }}</span></label><button class="btn" :disabled="busy || !file || !!record || !ready?.ready" @click="detect">{{ busy ? phase || '正在处理…' : '检测并生成等级' }}</button></div>
        <p class="e-note">{{ file?.name || '支持 JPG / PNG / WebP，最大 10 MiB' }}。超时不代表后端已取消，请先刷新历史；刷新后可查看已保存记录。</p>
        <div v-if="record && detections.length" class="e-candidate-list"><button v-for="(d, i) in detections" :key="i" class="e-candidate" :class="{ active: selected === i + 1 }" @click="selected = i + 1"><span class="e-candidate-index">{{ i + 1 }}</span><span>{{ d.class_label || d.class_name }}</span><strong>{{ percent(d.confidence) }}</strong></button></div>
        <div v-else-if="record" class="e-empty-result"><strong>本次未检出擦伤或虫害候选</strong><p>本图已生成 A 级外观初评。若仍看到其他问题，可补拍或展开人工检查修正。</p></div>
      </section>
      <aside class="e-panel e-decision-panel"><p class="e-kicker">本图品控等级</p><h3>{{ decision.title }}</h3><div class="e-grade-display" :class="{ pending: !decision.grade }"><strong>{{ decision.grade === 'REJECT' ? '拒收' : decision.grade || '待定' }}</strong><span>{{ decision.source === 'human' ? '人工修正已生效' : decision.grade ? 'AI 初评已生效' : '等待检测' }}</span></div><p class="e-action-copy">{{ decision.action }}</p>
        <dl v-if="record" class="e-decision-facts"><div><dt>检测候选</dt><dd>{{ detections.length }} 处</dd></div><div><dt>AI 原始初评</dt><dd>{{ record.initial_assessment?.grade || prediction.initial_grade || '历史未记录' }}</dd></div><div><dt>人工复核</dt><dd>{{ record.review_revision ? `第 ${record.review_revision} 版` : '尚未检查（可选）' }}</dd></div><div><dt>工艺参数</dt><dd>后续按条件计算</dd></div></dl>
        <RouterLink v-if="record" class="btn full-width" :to="{ path: `/batches/${batch.batch_id}`, query: { tab: 'processing' } }">继续加工品控 →</RouterLink><p class="e-note">本轮多张照片按最高问题等级汇总，人工修正优先。</p>
        <RouterLink class="btn secondary full-width" :to="`/batches/${batch.batch_id}/report`">查看批次综合记录</RouterLink>
        <p class="e-note">单图外观建议不是整批果蔬合格或食品安全结论；模型不覆盖所有缺陷。</p>
      </aside>
    </div>
    <details v-if="record" class="e-panel e-review-panel"><summary>人工检查与修正（可选）</summary><div class="e-section-line"><div><h3>图像检查</h3></div><span class="badge neutral">原始模型结果不会被覆盖</span></div>
      <div v-if="detections.length" class="table-wrap"><table><thead><tr><th>候选与类别</th><th>人工判断</th><th>关联 / 备注</th></tr></thead><tbody><tr v-for="(d, i) in detections" :key="i" :class="{ 'e-selected-row': selected === i + 1 }"><td><button class="e-text-button" @click="selected = i + 1">{{ candidateCaption(d, i) }}</button></td><td><select v-model="form.candidates[i].decision" :disabled="busy" :aria-label="`候选 ${i + 1} 的判断`" @change="form.candidates[i].decision !== 'duplicate' && (form.candidates[i].duplicate_of = null)"><option value="">请选择</option><option v-for="(label, code) in decisions" :key="code" :value="code">{{ label }}</option></select></td><td><select v-if="form.candidates[i].decision === 'duplicate'" v-model="form.candidates[i].duplicate_of" :disabled="busy" aria-label="重复于哪个已确认框"><option :value="null">请选择已确认框</option><option v-for="c in confirmed" :key="c.candidate_index" :value="c.candidate_index">候选 {{ c.candidate_index }}</option></select><input v-model="form.candidates[i].note" :disabled="busy" maxlength="500" placeholder="复核备注（可选）" :aria-label="`候选 ${i + 1} 备注`" /></td></tr></tbody></table></div>
      <form class="form-grid" @submit.prevent="submitReview"><label>复核人<input v-model="form.reviewer" :disabled="busy" required maxlength="50" placeholder="请输入复核人" /></label><label>图像结论<select v-model="form.conclusion" :disabled="busy"><option v-for="(label, key) in conclusions" :key="key" :value="key">{{ label }}</option></select></label><label>修正等级（可留空）<select v-model="form.final_grade" :disabled="busy"><option value="">保留 AI 初评</option><option v-for="g in GRADE_OPTIONS" :key="g" :value="g">{{ `${g} · ${GRADE_LABELS[g]}` }}</option><option v-if="form.final_grade && !GRADE_OPTIONS.includes(form.final_grade)" :value="form.final_grade">历史等级 {{ form.final_grade }}（请重新核定）</option></select></label><p v-if="form.conclusion === 'manual_target_confirmed'" class="notice warning full">这是人工发现，不计入模型检出数量。请说明部位、缺陷现象与核定依据；模型原始候选和图片不会被改写。</p><label class="full">复核说明 / 等级依据<textarea v-model="form.remark" required :disabled="busy" maxlength="4000" rows="3" placeholder="说明确认或漏检的部位、采用的人工标准，以及等级判断依据。" /></label><label class="full e-checkbox"><input v-model="form.publish_summary" type="checkbox" :disabled="busy" /><span>公开“已保存人工复核”通用摘要。不会公开等级、原图、候选和内部备注；已有公开事件保留。</span></label><button class="btn" :disabled="busy" type="submit">{{ busy ? '正在保存…' : '保存检查与修正' }}</button></form>
      <details v-if="record.reviews?.length"><summary>查看 {{ record.reviews.length }} 版复核历史</summary><article v-for="r in record.reviews" :key="r.revision" class="e-review-history"><strong>第 {{ r.revision }} 版 · {{ r.reviewer }} · {{ r.final_grade || '未核定等级' }}</strong><p>{{ date(r.created_at) }} · {{ conclusions[r.conclusion] }}</p><p>{{ r.remark }}</p></article></details>
    </details>
    <details v-if="record" class="e-panel e-technical"><summary>技术详情与诊断导出</summary><p>实际推理策略：{{ prediction.runtime?.profile || '历史未记录' }} · 图像遍数：{{ prediction.runtime?.pass_count || '历史未记录' }}。增强策略尚待同图评测。</p><p>推理版本：{{ prediction.model_version }} · {{ date(prediction.executed_at) }}</p><p>置信度阈值 {{ prediction.confidence_threshold }} / NMS IoU {{ prediction.iou_threshold }} / 模型调用 {{ prediction.inference_ms }} ms（不含完整上传和保存）</p><p>独立评测状态：{{ prediction.evaluation_status }}。模型分数不是准确率。</p><p>候选框并集 / 整图：{{ percent(prediction.defect_burden?.union_bbox_area_ratio_image) }}；这不是苹果真实损伤面积比例。</p><p>等级规则：{{ prediction.grade_rule_version || '历史未记录' }}；等级依据：{{ prediction.grade_basis || '历史未记录' }}</p><button class="btn secondary" @click="exportRecord">导出当前记录 JSON（内部）</button><a :href="record.artifact_urls.result" target="_blank" rel="noopener">打开服务端保存图 ↗</a><p class="e-note">旧记录的服务端标注图不会重绘。新版本编号修复只作用于重新上传的新记录。</p></details>
    <section class="e-panel"><div class="e-section-line"><h3>当前批次 · 已保存质检</h3><button class="btn secondary" :disabled="busy || refreshing" @click="refresh">刷新历史</button></div><p v-if="historyError" class="notice danger">{{ historyError }}</p><div class="e-history-list"><button v-for="r in history" :key="r.inspection_id" :disabled="busy" class="e-history-row" :class="{ active: record?.inspection_id === r.inspection_id }" @click="openRecord(r.inspection_id)"><strong>#{{ r.inspection_id }}</strong><span>{{ date(r.created_at) }}</span><span>{{ r.review_revision ? `已复核 ${r.review_revision} 版` : `AI 初评 ${r.initial_grade || r.suggested_grade || '已生成'}` }}</span><span>打开 →</span></button></div><p v-if="!history.length" class="e-note">{{ refreshing ? '正在读取…' : historyLoaded ? '暂无已保存质检记录' : '尚未成功读取，不能认定无记录' }}</p></section>
  </div>
</template>
