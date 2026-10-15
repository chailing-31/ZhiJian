<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { getProcessingHistory, getProcessingTrace } from '../../api/processing.js'
import { formatTime } from '../../utils/batch.js'
import { validId } from '../../utils/batch.js'
import { useRoute } from 'vue-router'
const route = useRoute()
const props = defineProps({ batchId: { type: [String, Number], required: true }, refreshKey: { type: String, default: '' } })
const records = ref([]), selected = ref(null), detail = ref(null), loading = ref(false), error = ref(''), historyError = ref(''), panel = ref(null)
let historyAbort, detailAbort
const source = computed(() => detail.value?.source_assessment)
const evidence = computed(() => source.value?.snapshot?.evidence?.records || [])
async function history() {
  historyAbort?.abort(); const controller = new AbortController(); historyAbort = controller; historyError.value = ''
  try { const result = await getProcessingHistory(props.batchId, { signal: controller.signal }); if (!controller.signal.aborted) records.value = result.records }
  catch (e) { if (!controller.signal.aborted) historyError.value = e.message }
}
async function open(recordId, scroll = true) {
  detailAbort?.abort(); const controller = new AbortController(); detailAbort = controller
  selected.value = recordId; detail.value = null; loading.value = true; error.value = ''
  try {
    const result = await getProcessingTrace(props.batchId, recordId, { signal: controller.signal })
    if (!controller.signal.aborted) detail.value = result
  } catch (e) { if (!controller.signal.aborted) error.value = e.message }
  finally { if (!controller.signal.aborted) { loading.value = false; if (scroll) { await nextTick(); panel.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }) } } }
}
watch(() => [props.batchId, props.refreshKey, route.query.processing_record], (value, previous) => {
  if (!previous || String(value[0]) !== String(previous[0])) { detailAbort?.abort(); selected.value = null; detail.value = null; error.value = ''; records.value = []; loading.value = false }
  history()
  const requested = validId(value[2])
  if (requested && (!previous || value[2] !== previous[2] || String(value[0]) !== String(previous[0]))) open(requested, false)
  else if (selected.value) open(selected.value, false)
}, { immediate: true })
onBeforeUnmount(() => { historyAbort?.abort(); detailAbort?.abort() })
defineExpose({ open })
</script>
<template>
  <section class="q4-panel processing-trace">
    <div class="section-row"><div><h3>加工来源追溯</h3><p class="footnote">查看生成建议时保存的等级、照片和后续作业。最多显示最近 200 条建议。</p></div><button class="btn secondary" @click="history">刷新记录</button></div>
    <p v-if="historyError" class="notice danger" role="alert">{{ historyError }}</p>
    <div class="table-wrap" tabindex="0" aria-label="加工建议历史，可横向滚动"><table><thead><tr><th scope="col">工艺记录</th><th scope="col">生成时间</th><th scope="col">输入等级</th><th scope="col">来源版本</th><th scope="col">操作</th></tr></thead><tbody>
      <tr v-for="record in records" :key="record.record_id" :class="{ 'trace-selected': selected === record.record_id }"><td>#{{ record.record_id }}</td><td>{{ formatTime(record.created_at) }}</td><td>{{ record.inputs?.grade || '未记录' }}</td><td>{{ record.inputs?.grade_source?.revision ? `评定 #${record.inputs.grade_source.assessment_id} / v${record.inputs.grade_source.revision}` : '未关联批次评定' }}</td><td><button class="btn secondary" :aria-pressed="selected === record.record_id" @click="open(record.record_id)">查看来源 #{{ record.record_id }}</button></td></tr>
      <tr v-if="!records.length && !historyError"><td colspan="5">暂无加工建议记录</td></tr>
    </tbody></table></div>
    <div v-if="selected" ref="panel" class="trace-detail" aria-live="polite">
      <h3>工艺 #{{ selected }} 的来源与执行记录</h3>
      <p v-if="loading">正在读取保存的来源…</p>
      <p v-if="error" class="notice danger" role="alert">{{ error }} <button class="btn secondary" @click="open(selected)">重试</button></p>
      <template v-if="detail">
        <p v-if="detail.source_status === 'unlinked'" class="notice warning">历史记录未关联批次评定，无法确认其等级和照片来源。以下仅展示该记录已保存的输入、建议与采用值。</p>
        <template v-if="source">
          <p class="notice" :class="detail.record_current ? 'success' : 'warning'">{{ detail.record_current ? '该建议关联当前有效来源。' : detail.source_current ? '来源等级仍有效，但已有更新的加工建议；本条保留供追溯。' : '这是历史来源快照。等级或证据已经变化，不能作为当前加工依据。' }}</p>
          <p><strong>原来源：</strong>评定 #{{ source.id }} / 第 {{ source.revision }} 版 · {{ source.final_grade }} · {{ source.grade_policy_version?.startsWith('ai-batch-initial') ? 'AI 初评' : '人工修正' }}</p>
          <p v-if="detail.current_assessment"><strong>当前最新评定：</strong>#{{ detail.current_assessment.id }} / 第 {{ detail.current_assessment.revision }} 版 · {{ detail.current_assessment.final_grade }}{{ detail.current_assessment.valid ? '' : '（当前证据已变化，待更新）' }}</p>
          <p>记录时间：{{ formatTime(source.created_at) }} · 评定人：{{ source.approved_by }}</p>
          <p><strong>评定依据：</strong>{{ source.grade_basis }}</p>
          <p><strong>当时抽检范围：</strong>{{ source.snapshot?.round?.sampling_note || '未记录' }}</p>
          <h4>当时使用的质检证据</h4>
          <p class="footnote">以下等级与复核版本来自保存的评定快照；原图和检测图为对应检测记录的图像。</p>
          <div class="trace-evidence"><article v-for="item in evidence" :key="item.inspection_id">
            <a :href="`/api/inspections/${item.inspection_id}/artifacts/result`" target="_blank" rel="noopener"><img :src="`/api/inspections/${item.inspection_id}/artifacts/result`" :alt="`质检 #${item.inspection_id} 检测图`" loading="lazy" /></a>
            <p>质检 #{{ item.inspection_id }} · 当时等级 {{ item.effective_grade || item.image_grade || '未记录' }} · 复核版本 {{ item.review_revision || 0 }}</p>
            <p>样品：{{ item.sample_code || '未编号' }} · 模型：{{ item.model_version }}</p><p v-if="item.review_remark">当时复核备注：{{ item.review_remark }}</p>
            <a class="text-link" :href="`/api/inspections/${item.inspection_id}/artifacts/input`" target="_blank" rel="noopener">打开原图</a>
          </article></div>
          <p v-if="!evidence.length">该历史快照没有可展示的照片证据。</p>
        </template>
        <h4>保存的加工输入与建议</h4>
        <p>输入等级 {{ detail.record.inputs?.grade || '未记录' }}；环境温度 {{ detail.record.inputs?.temperature ?? '未记录' }} ℃；湿度 {{ detail.record.inputs?.humidity ?? '未记录' }} %；原料温度 {{ detail.record.inputs?.material_temperature ?? '未记录' }} ℃。</p>
        <p>建议预冷 {{ detail.record.advice?.advice?.pre_cooling_time || '不提供' }}；清洗档位 {{ detail.record.advice?.advice?.washing_pressure || '不提供' }}；规则版本 {{ detail.record.advice?.rule_version || '未记录' }}。</p>
        <p>{{ detail.record.advice?.reason }}</p>
        <h4>采用参数历史</h4>
        <article v-for="item in detail.adoptions" :key="item.id" class="q4-history"><b>采用版本 #{{ item.id }} · {{ item.values?.pre_cooling_time }} / {{ item.values?.washing_pressure }}</b><span>{{ formatTime(item.created_at) }} · {{ item.operator }}</span><p>{{ item.note }}</p></article>
        <p v-if="!detail.adoptions.length">{{ detail.record.adopted_values ? `历史采用值：${detail.record.adopted_values.pre_cooling_time || '未记录'} / ${detail.record.adopted_values.washing_pressure || '未记录'}；没有独立的采用版本历史。` : '尚未保存采用值。' }}</p>
        <h4>相关实际作业</h4>
        <article v-for="item in detail.operations" :key="item.id" class="q4-history"><b>{{ item.kind === 'sorting_completed' ? '来源评定对应的分选记录' : '加工执行记录' }} #{{ item.id }}</b><span>{{ formatTime(item.performed_at) }} · {{ item.operator }} · {{ item.current ? '当前证据' : '历史记录' }}</span><p>{{ item.note }}</p><p v-if="item.kind === 'processing_completed' && !item.parameters_match" class="notice warning">采用参数版本已变化，这条执行记录不代表当前参数已经执行。</p></article>
        <p v-if="!detail.operations.length">尚无相关实际作业记录。</p>
      </template>
    </div>
  </section>
</template>
<style scoped>
.trace-detail { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--line); scroll-margin-top: 128px; }
.processing-trace > .table-wrap { max-height: 360px; overflow: auto; }
.processing-trace th { position: sticky; top: 0; }
.trace-selected { background: #f1f6f3; }
.trace-evidence { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
.trace-evidence article { min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 5px; overflow-wrap: anywhere; }
.trace-evidence img { width: 100%; height: 180px; object-fit: contain; background: #f5f6f7; }
</style>
