<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import EmptyState from '../../components/EmptyState.vue'
import { getInspectionReady, listInspections, getInspection, predictInspection, reviewInspection } from '../../api/inspection.js'

const props = defineProps({ batch: { type: Object, required: true } })
const controller = new AbortController()
let disposed = false
const options = { signal: controller.signal }
const file = ref(null), preview = ref(''), requestId = ref('')
const error = ref(''), notice = ref(''), ready = ref(null), history = ref([]), record = ref(null)
const busy = ref(false), refreshing = ref(false), selectedCandidate = ref(null), imageError = ref(false)
const form = reactive({ reviewer: '', remark: '', final_grade: '', conclusion: 'needs_recheck', publish_summary: false, candidates: [] })
const decisions = { confirmed: '确认目标', false_positive: '疑似误检', duplicate: '重复框', uncertain: '不确定' }
const conclusions = { target_confirmed: '已人工确认图中存在目标', no_target_confirmed: '本次未确认目标（不等于合格）', needs_recheck: '需要进一步复核' }
const burdenLevels = { none_observed: '未观察到目标候选', low: '低', moderate: '中等', high: '高' }
const burdenFlags = {
  detection_count_ge_4: '候选总数达到 4 个',
  scratch_large_box_ge_0_025: '存在较大的表面擦伤候选框',
  pest_damage_large_box_ge_0_0045: '存在较大的虫害损伤候选框',
}
const prediction = computed(() => record.value?.prediction)
const detections = computed(() => prediction.value?.detections || [])
const burden = computed(() => prediction.value?.defect_burden || null)
const viewBox = computed(() => prediction.value ? `0 0 ${prediction.value.image.width} ${prediction.value.image.height}` : '0 0 1 1')
const confirmedCandidates = computed(() => form.candidates.filter(c => c.decision === 'confirmed'))
function time(v) { return v ? new Date(v).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '—' }
function percent(v) { return `${(Number(v) * 100).toFixed(2)}%` }
function areaPercent(v) { return `${(Number(v) * 100).toFixed(2)}%` }
function bbox(d) { return d.bbox_xyxy.map(v => Number(v).toFixed(1)).join(', ') }
function showError(e) { if (!disposed && e.name !== 'AbortError') error.value = e.message }
function setRecord(value) {
  if (String(value.batch_id) !== String(props.batch.batch_id)) throw new Error('记录批次不匹配，已停止展示。')
  record.value = value; imageError.value = false; selectedCandidate.value = null
  const latest = value.latest_review
  form.reviewer = latest?.reviewer || ''; form.remark = ''; form.final_grade = latest?.final_grade || ''
  form.publish_summary = false; form.conclusion = latest?.conclusion || 'needs_recheck'
  form.candidates = value.prediction.detections.map((_, index) => {
    const previous = latest?.candidate_reviews?.find(c => c.candidate_index === index + 1)
    return { candidate_index: index + 1, decision: previous?.decision || '', duplicate_of: previous?.duplicate_of ?? null, note: '' }
  })
}
async function refresh() {
  if (refreshing.value || disposed) return
  refreshing.value = true; error.value = ''
  const results = await Promise.allSettled([getInspectionReady(options), listInspections(props.batch.batch_id, options)])
  if (!disposed) {
    if (results[0].status === 'fulfilled') ready.value = results[0].value
    else { ready.value = null; showError(results[0].reason) }
    if (results[1].status === 'fulfilled') history.value = results[1].value
    else showError(results[1].reason)
    refreshing.value = false
  }
}
function choose(event) {
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''; file.value = null; record.value = null; error.value = ''; notice.value = ''
  const f = event.target.files?.[0]; event.target.value = ''
  if (!f) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(f.type) || !f.size || f.size > 10 * 1024 * 1024) {
    error.value = '请选择非空 JPG、PNG 或 WebP 图片，不超过 10 MiB。'; return
  }
  if (!globalThis.crypto?.randomUUID) { error.value = '浏览器无法生成请求编号，请使用本机 localhost 或 HTTPS。'; return }
  file.value = f; preview.value = URL.createObjectURL(f); requestId.value = crypto.randomUUID()
}
async function detect() {
  if (busy.value || !file.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const value = await predictInspection(props.batch.batch_id, file.value, { ...options, requestId: requestId.value })
    if (disposed) return
    setRecord(value)
    notice.value = '模型结果和图片已保存。下面的候选框尚需人工复核，不是已确认的缺陷数量。'
    await refresh()
  } catch (e) { showError(e) }
  finally { if (!disposed) busy.value = false }
}
async function openRecord(id) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { const value = await getInspection(id, options); if (!disposed) setRecord(value) }
  catch (e) { showError(e) }
  finally { if (!disposed) busy.value = false }
}
function updateDecision(candidate) { if (candidate.decision !== 'duplicate') candidate.duplicate_of = null }
async function submitReview() {
  if (busy.value || !record.value) return
  if (!form.reviewer.trim() || !form.remark.trim() || form.candidates.some(c => !c.decision)) {
    error.value = '请填写复核人、说明，并逐个选择候选状态。'; return
  }
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const value = await reviewInspection(record.value.inspection_id, {
      expected_revision: record.value.review_revision, reviewer: form.reviewer.trim(), remark: form.remark.trim(),
      final_grade: form.final_grade.trim() || null, conclusion: form.conclusion, publish_summary: form.publish_summary,
      candidate_reviews: form.candidates.map(c => ({ ...c, duplicate_of: c.decision === 'duplicate' ? Number(c.duplicate_of) : null })),
    }, options)
    if (disposed) return
    setRecord(value); notice.value = '人工复核已单独保存，模型原始结果未改动。'
    await refresh()
  } catch (e) { showError(e) }
  finally { if (!disposed) busy.value = false }
}
onMounted(refresh)
onBeforeUnmount(() => { disposed = true; controller.abort(); if (preview.value) URL.revokeObjectURL(preview.value) })
</script>

<template>
  <section class="notice info"><strong>内部联调模型。</strong>类别语义已完成 A9 数据审计；A11 增加基于候选框/整图面积的开发版缺陷负担证据。该负担不是果面损伤率或质量等级，独立测试与自动等级仍未完成。没有检出框不等于正常，人工复核也不构成整批合格认证。切换批次会清空未提交表单。</section>
  <div class="section-row"><span :class="['badge', ready?.ready ? 'ready' : 'pending']">{{ ready?.ready ? '后端 / 数据库 / 模型可用' : '连接状态待确认' }}</span><button class="btn secondary" :disabled="busy || refreshing" @click="refresh">刷新状态与历史</button></div>
  <p v-if="ready && !ready.ready" class="notice warning">{{ ready.message }}</p>
  <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
  <p v-if="notice" class="notice info" role="status">{{ notice }}</p>
  <div class="two-column">
    <section class="card">
      <h2>01 输入图片</h2>
      <div class="image-stage"><img v-if="record" :src="record.artifact_urls.input" alt="方向校正后的实际模型输入图" @error="imageError = true" /><img v-else-if="preview" :src="preview" alt="待上传的本地预览" /><EmptyState v-else title="选择一张苹果图片" description="JPG / PNG / WebP，不超过 10 MiB" /></div>
      <label class="file-control"><span class="btn secondary">选择图片</span><input :disabled="busy" type="file" accept="image/jpeg,image/png,image/webp" aria-label="选择待检图片" @change="choose" /></label>
      <p class="footnote break-word">{{ record ? `已保存记录 #${record.inspection_id} 的模型输入图` : file?.name || '可在下方打开已保存的质检记录' }}</p>
      <button class="btn full-width" :disabled="busy || !file || !ready?.ready || !!record" @click="detect">{{ busy ? '正在处理，请勿重复提交…' : record ? '当前记录已保存，请选图开始新检测' : '开始检测并保存' }}</button>
      <p class="footnote">关闭或切换页面不能撤销后端已开始的检测。超时后先刷新历史；同一选图请求重试不会重复新增业务记录。</p>
    </section>
    <section class="card">
      <div class="section-row"><h2>02 模型原始候选</h2><span class="badge neutral">{{ record ? `${detections.length} 个候选框` : '尚未检测' }}</span></div>
      <div v-if="record" class="a3-overlay">
        <svg :viewBox="viewBox" role="img" aria-label="模型候选位置，可点击右侧或下方列表定位">
          <image :href="record.artifact_urls.input" :width="prediction.image.width" :height="prediction.image.height" />
          <g v-for="(d, i) in detections" :key="i" @click="selectedCandidate = i + 1">
            <rect :x="d.bbox_xyxy[0]" :y="d.bbox_xyxy[1]" :width="d.bbox_xyxy[2] - d.bbox_xyxy[0]" :height="d.bbox_xyxy[3] - d.bbox_xyxy[1]" :class="{ active: selectedCandidate === i + 1 }" />
            <text :x="d.bbox_xyxy[0]" :y="Math.max(24, d.bbox_xyxy[1] - 7)" :font-size="Math.max(18, prediction.image.width / 36)">{{ i + 1 }}</text>
          </g>
        </svg>
      </div>
      <div v-else class="image-stage"><EmptyState title="等待真实模型结果" description="不使用示例检测框填充" /></div>
      <p v-if="imageError" class="notice danger">图片文件加载失败，请检查后端证据目录。</p>
      <template v-if="record">
        <div class="result-summary"><div><span>记录 ID</span><strong>{{ record.inspection_id }}</strong></div><div><span>模型调用耗时</span><strong>{{ Number(prediction.inference_ms).toFixed(1) }} ms</strong></div><div><span>人工复核版本</span><strong>{{ record.review_revision }}</strong></div></div>
        <div v-if="burden" class="a11-burden">
          <div class="section-row"><strong>A11 缺陷负担证据</strong><span class="badge neutral">{{ burdenLevels[burden.burden_level] || burden.burden_level }}</span></div>
          <div class="result-summary">
            <div><span>候选总数</span><strong>{{ burden.detection_count }}</strong></div>
            <div><span>候选联合覆盖 / 整图</span><strong>{{ areaPercent(burden.union_bbox_area_ratio_image) }}</strong></div>
            <div><span>最大单处候选 / 整图</span><strong>{{ areaPercent(burden.max_bbox_area_ratio_image) }}</strong></div>
          </div>
          <p v-if="burden.escalation_flags?.length" class="footnote">升级依据：{{ burden.escalation_flags.map(f => burdenFlags[f] || f).join('；') }}</p>
          <p class="footnote">规则 {{ burden.rule_version }}；分母为整张图像面积。该值不是苹果表面真实损伤率，不是质量等级，也不代表食品安全或整批合格。</p>
        </div>
        <p v-else class="footnote">该历史记录生成于 A11 之前，未保存缺陷负担字段。</p>
        <p class="footnote break-word">模型：{{ prediction.model_version }}<br />时间：{{ time(prediction.executed_at) }}<br />置信度阈值：{{ prediction.confidence_threshold }} · 去重阈值：{{ prediction.iou_threshold }} · 独立评测状态：{{ prediction.evaluation_status }}</p>
        <div class="a3-links"><a :href="record.artifact_urls.input" target="_blank" rel="noopener">放大输入图</a><a :href="record.artifact_urls.result" target="_blank" rel="noopener">查看 AI 服务保存的原始标注图</a></div>
        <p class="footnote">图上数字为候选序号，不是类别编号。复核不会隐藏或删除原始候选。耗时不含完整上传、下载与落库。</p>
      </template>
    </section>
  </div>
  <section v-if="record" class="card">
    <h2>03 人工复核候选</h2>
    <p class="footnote">置信度是模型分数，不是准确率。疑似重复框需指向一个已确认的候选；不确定项保留为“需要进一步复核”。</p>
    <div class="table-wrap"><table class="a3-candidates"><thead><tr><th>候选</th><th>原始类别 / 分数</th><th>原始坐标 xyxy</th><th>人工标记</th><th>备注 / 重复对象</th></tr></thead><tbody>
      <tr v-for="(d, i) in detections" :key="i" :class="{ chosen: selectedCandidate === i + 1 }">
        <td><button class="btn secondary" @click="selectedCandidate = i + 1">{{ i + 1 }}</button></td>
        <td>{{ d.class_label }}<br /><strong>{{ percent(d.confidence) }}</strong></td><td>{{ bbox(d) }}</td>
        <td><select v-model="form.candidates[i].decision" :disabled="busy" :aria-label="`候选${i + 1}人工状态`" @change="updateDecision(form.candidates[i])"><option value="">未复核</option><option v-for="(label, value) in decisions" :key="value" :value="value">{{ label }}</option></select></td>
        <td><select v-if="form.candidates[i].decision === 'duplicate'" v-model="form.candidates[i].duplicate_of" :disabled="busy" :aria-label="`候选${i + 1}重复于`"><option :value="null">关联已确认候选</option><option v-for="c in confirmedCandidates" :key="c.candidate_index" :value="c.candidate_index">候选 {{ c.candidate_index }}</option></select><input v-model="form.candidates[i].note" maxlength="500" :disabled="busy" :aria-label="`候选${i + 1}备注`" placeholder="可选备注" /></td>
      </tr><tr v-if="!detections.length"><td colspan="5">未检出目标候选，仍需人工查看图片；不能据此判为正常。</td></tr>
    </tbody></table></div>
    <form class="form-grid" @submit.prevent="submitReview">
      <label>复核人<input v-model="form.reviewer" required maxlength="50" :disabled="busy" /></label>
      <label>图像复核结论<select v-model="form.conclusion" :disabled="busy"><option v-for="(label, value) in conclusions" :key="value" :value="value">{{ label }}</option></select></label>
      <label>人工等级（可留空）<input v-model="form.final_grade" maxlength="20" :disabled="busy" placeholder="没有核定标准时留空" /></label>
      <label class="full">复核说明<textarea v-model="form.remark" required maxlength="4000" rows="3" :disabled="busy" placeholder="说明确认、误检、重复或仍需核实的情况；不能以单图替代整批判定。" /></label>
      <label class="full a3-checkbox"><input v-model="form.publish_summary" type="checkbox" :disabled="busy" />将“已保存一次人工复核”这一通用摘要公开到溯源页（不公开备注、等级、原图或候选详情；历史公开事件保留）</label>
      <button class="btn" type="submit" :disabled="busy">{{ busy ? '正在保存…' : `保存第 ${record.review_revision + 1} 版复核` }}</button>
    </form>
    <p class="footnote">复核人目前为手工录入，尚无登录身份校验；仅用于受控本地联调。模型建议等级未配置，不自动分级、不自动将批次设为合格。</p>
    <details v-if="record.reviews.length"><summary>已保存的复核历史（{{ record.reviews.length }} 版）</summary><article v-for="r in record.reviews" :key="r.revision" class="a3-review"><strong>第 {{ r.revision }} 版 · {{ r.reviewer }} · {{ conclusions[r.conclusion] }}</strong><p>{{ time(r.created_at) }} · 人工等级：{{ r.final_grade || '未填写' }}</p><p>{{ r.remark }}</p><p v-for="c in r.candidate_reviews" :key="c.candidate_index" class="footnote">候选 {{ c.candidate_index }}：{{ decisions[c.decision] }}<span v-if="c.duplicate_of"> → {{ c.duplicate_of }}</span> {{ c.note }}</p></article></details>
  </section>
  <section class="card"><div class="section-row"><h2>04 当前批次质检历史</h2><span class="badge neutral">最近 100 条 A3 记录</span></div><p v-if="!history.length">{{ error ? '历史读取失败，不能据此认定无记录。' : '暂无已保存记录。' }}</p><button v-for="r in history" :key="r.inspection_id" class="row" :disabled="busy" @click="openRecord(r.inspection_id)"><strong>#{{ r.inspection_id }} · {{ r.review_revision ? `复核 ${r.review_revision} 版` : '待人工复核' }}</strong><span>{{ time(r.created_at) }} · {{ r.model_version }}</span></button></section>
</template>

<style scoped>
.a3-overlay { background: #101713; border-radius: 12px; overflow: hidden; }
.a3-overlay svg { display: block; width: 100%; max-height: 540px; }
.a3-overlay rect { fill: transparent; stroke: #fb724e; stroke-width: 2; vector-effect: non-scaling-stroke; cursor: pointer; }
.a3-overlay rect.active { stroke: #fff; stroke-width: 4; }
.a3-overlay text { fill: #fff; stroke: #182f29; stroke-width: 2px; paint-order: stroke; font-weight: 800; pointer-events: none; }
.a3-links { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 12px; }
.a3-candidates { min-width: 720px; }
.a3-candidates td { vertical-align: top; white-space: normal; }
.a3-candidates .chosen { background: #edf6ef; }
.a3-checkbox { display: flex; align-items: start; gap: 10px; }
.a3-checkbox input { width: auto; margin-top: 4px; }
.a3-review { border-top: 1px solid #dce8df; padding: 14px 0; }
.a3-review p { white-space: pre-wrap; overflow-wrap: anywhere; }
.a11-burden { margin-top: 14px; padding: 14px; border: 1px solid #dce8df; border-radius: 10px; background: #f8fbf9; }
details { margin-top: 20px; }
</style>
