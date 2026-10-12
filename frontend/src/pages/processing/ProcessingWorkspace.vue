<script setup>
import { computed, reactive, ref } from 'vue'
import { requestAdvice, saveAdoptedValues, getProcessingHistory } from '../../api/processing.js'
import PendingNotice from '../../components/PendingNotice.vue'
import EmptyState from '../../components/EmptyState.vue'
const props = defineProps({ batch: { type: Object, required: true } })
const draft = reactive({ grade: '', materialTemperature: '', temperature: '', humidity: '' })
const operator = ref(''), note = ref(''), advice = ref(null), adopted = ref(null), recordId = ref(null)
const loading = ref(false), saving = ref(false), error = ref(''), savedMessage = ref('')
const records = ref([]), historyLoading = ref(false)
const finalValues = reactive({ pre_cooling_time: '', washing_pressure: '', action: '' })
function selectRecord(record) {
  advice.value = record.advice; recordId.value = record.record_id; adopted.value = record.adopted_values
  operator.value = record.operator || ''; note.value = ''
  const values = record.adopted_values || record.advice?.advice || {}
  Object.assign(finalValues, { pre_cooling_time: values.pre_cooling_time || '', washing_pressure: values.washing_pressure || '', action: values.action || '' })
}
async function loadHistory() {
  historyLoading.value = true
  try { records.value = (await getProcessingHistory(props.batch.batch_id)).records; if (!recordId.value && records.value.length) selectRecord(records.value[0]) }
  catch (err) { error.value = err.message } finally { historyLoading.value = false }
}
loadHistory()
const statusLabel = computed(() => ({ suggested: '可供人工确认', manual_review: '需要人工复核', blocked: '暂停流转' }[advice.value?.status] || '待返回'))
function num(value) { if (value === '' || value === null || value === undefined) return null; const n = Number(value); if (!Number.isFinite(n)) throw new Error('温度和湿度必须是有限数值。'); return n }
async function generate() {
  loading.value = true; error.value = ''; savedMessage.value = ''
  try {
    if (!draft.grade) throw new Error('请先填写原料等级。')
    const result = await requestAdvice(props.batch.batch_id, { grade: draft.grade, temperature: num(draft.temperature), humidity: num(draft.humidity), material_temperature: num(draft.materialTemperature), operator: operator.value.trim() || undefined })
    selectRecord({ ...result, operator: operator.value }); savedMessage.value = '规则建议已保存为待确认记录。'
    await loadHistory()
  } catch (err) { error.value = err.message } finally { loading.value = false }
}
async function adopt() {
  if (!recordId.value || !operator.value.trim() || advice.value?.status === 'blocked') return
  saving.value = true; error.value = ''; savedMessage.value = ''
  try {
    if (!finalValues.pre_cooling_time.trim() || !finalValues.washing_pressure.trim()) throw new Error('请填写人工采用的预冷时间和清洗档位。')
    const result = await saveAdoptedValues(recordId.value, { operator: operator.value.trim(), note: note.value.trim(), adopted_values: { ...finalValues } })
    adopted.value = result.adopted_values; savedMessage.value = '人工采用值已保存。'; await loadHistory()
  } catch (err) { error.value = err.message } finally { saving.value = false }
}
</script>
<template>
  <PendingNotice text="规则参数来自 B 的演示配置，结果只作为人工确认草稿；系统不会自动控制设备或宣称最优工艺。" />
  <div class="two-column">
    <section class="card"><div class="section-row"><h2>01 工艺输入</h2><span class="badge neutral">保存至当前批次</span></div><div class="form-grid"><label>原料等级<input v-model.trim="draft.grade" placeholder="A / B / C / REJECT" /></label><label>原料温度（℃）<input v-model="draft.materialTemperature" type="number" step="any" placeholder="未录入" /></label><label>环境温度（℃）<input v-model="draft.temperature" type="number" step="any" placeholder="未录入" /></label><label>环境湿度（%）<input v-model="draft.humidity" type="number" min="0" max="100" step="any" placeholder="未录入" /></label><label class="full">操作员<input v-model.trim="operator" placeholder="填写人工确认人" /></label></div><button class="btn full-width" :disabled="loading" @click="generate">{{ loading ? '正在计算并保存…' : '生成规则建议' }}</button><p v-if="error" class="notice danger" role="alert">{{ error }}</p><p v-if="savedMessage" class="notice success" role="status">{{ savedMessage }}</p></section>
    <section class="card"><div class="section-row"><h2>02 建议与依据</h2><span class="badge" :class="advice ? 'ready' : 'pending'">{{ advice ? statusLabel : '暂无结果' }}</span></div><EmptyState v-if="!advice" title="暂无规则输出" description="提交一组真实工艺输入后，系统会保存 B 返回的规则依据和演示建议。" icon="sliders" /><template v-else><dl class="detail-list"><div><dt>规则版本</dt><dd>{{ advice.rule_version || '未记录' }}</dd></div><div><dt>规则标识</dt><dd>{{ advice.rule_id || '未记录' }}</dd></div><div><dt>依据</dt><dd>{{ advice.rule_basis || '未记录' }}</dd></div><div><dt>说明</dt><dd>{{ advice.reason || '未记录' }}</dd></div></dl><div class="notice subtle">{{ advice.advice?.action || '当前规则未提供可采用参数。' }}</div></template></section>
  </div>
  <section class="card"><div class="section-row"><h2>03 建议值与采用值</h2><span class="badge neutral">需要人工确认</span></div><div v-if="advice" class="table-wrap"><table><thead><tr><th>工艺参数</th><th>规则建议值</th><th>已保存采用值</th></tr></thead><tbody><tr><td>预冷时间</td><td>{{ advice.advice?.pre_cooling_time || '不提供' }}</td><td>{{ adopted?.pre_cooling_time || '未保存' }}</td></tr><tr><td>清洗压力</td><td>{{ advice.advice?.washing_pressure || '不提供' }}</td><td>{{ adopted?.washing_pressure || '未保存' }}</td></tr><tr><td>处理提示</td><td colspan="2">{{ advice.advice?.action || '未提供' }}</td></tr></tbody></table></div><EmptyState v-else title="暂无可采用值" description="先生成一条规则建议，再由人工确认是否采用。" icon="sliders" /><div v-if="advice" class="form-grid"><label>人工采用预冷时间<input v-model.trim="finalValues.pre_cooling_time" placeholder="例如 6h" /></label><label>人工采用清洗档位<input v-model.trim="finalValues.washing_pressure" placeholder="例如 normal" /></label><label class="full">采用 / 修改说明<textarea v-model.trim="note" rows="2" placeholder="记录人工确认或修改原因" /></label><button class="btn" :disabled="saving || loading || !operator.trim() || advice.status === 'blocked'" @click="adopt">{{ saving ? '正在保存…' : '保存采用记录' }}</button></div><p class="footnote">建议不是产线控制指令；所有数值均标记为演示规则，实际工艺须按业务确认结果执行。</p></section>
  <section class="card"><div class="section-row"><h2>04 已保存加工历史</h2><button class="btn secondary" :disabled="historyLoading || loading || saving" @click="loadHistory">刷新历史</button></div><div class="table-wrap"><table><thead><tr><th>记录</th><th>时间</th><th>建议状态</th><th>操作员</th><th>操作</th></tr></thead><tbody><tr v-for="record in records" :key="record.record_id"><td>#{{ record.record_id }}</td><td>{{ record.created_at }}</td><td>{{ record.advice?.status }}</td><td>{{ record.operator || '未记录' }}</td><td><button class="btn secondary" :disabled="loading || saving" @click="selectRecord(record)">查看 / 人工确认</button></td></tr><tr v-if="!records.length"><td colspan="5" class="empty-cell">暂无已保存加工记录</td></tr></tbody></table></div></section>
</template>
