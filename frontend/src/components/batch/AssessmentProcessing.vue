<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { assessmentAdvice, recordOperation, getWorkbench } from '../../api/batch-workbench.js'
import { saveAdoptedValues } from '../../api/processing.js'
import { canAdopt, processNumber, writeIntent, localTimeInput, toZonedTime } from '../../utils/batch-workbench.js'
const props = defineProps({ workbench: { type: Object, required: true } })
const emit = defineEmits(['changed'])
const busy = ref(false), error = ref(''), notice = ref(''), operator = ref(''), processApproved = ref(false)
const values = reactive({ temperature: '', humidity: '', material_temperature: '' })
const adopted = reactive({ pre_cooling_time: '', washing_pressure: '', action: '' }), adoptionNote = ref('')
const operation = reactive({ kind: 'sorting_completed', note: '', performed_at: localTimeInput(), confirmed: false })
const adviceIntent = writeIntent(), opIntent = writeIntent(); const abort = new AbortController()
const w = computed(() => props.workbench), p = computed(() => w.value.processing || {}), a = computed(() => w.value.assessment)
const eligible = computed(() => canAdopt(w.value))
watch(() => p.value.fingerprint, () => { const v = p.value.adopted_values || p.value.advice?.advice || {}; Object.assign(adopted, { pre_cooling_time: v.pre_cooling_time || '', washing_pressure: v.washing_pressure || '', action: v.action || '' }); adoptionNote.value = '' }, { immediate: true })
watch(() => a.value?.id, () => { processApproved.value = false; operation.confirmed = false })
async function act(fn, message) {
  if (busy.value) return; busy.value = true; error.value = ''; notice.value = ''
  try { if (!operator.value.trim()) throw new Error('请填写操作员。'); await fn(); if (abort.signal.aborted) return; const back = await getWorkbench(w.value.batch.id, { signal: abort.signal }); if (!abort.signal.aborted) { emit('changed', back); notice.value = message } }
  catch (e) { if (!abort.signal.aborted) error.value = `${e.message} 网络中断后请先刷新回读，不要反复新增。` }
  finally { busy.value = false }
}
function generate() {
  act(async () => {
    if (!w.value.assessment_current || !processApproved.value) throw new Error('请先批准当前批次评定，并确认将其用于本次工艺输入。')
    const humidity = processNumber(values.humidity, '环境湿度'); if (humidity < 0 || humidity > 100) throw new Error('湿度必须在0—100%。')
    await assessmentAdvice(w.value.batch.id, adviceIntent({ assessment_id: a.value.id, assessment_revision: a.value.revision, operator: operator.value.trim(), use_as_process_input_confirmed: true, temperature: processNumber(values.temperature, '环境温度'), humidity, material_temperature: processNumber(values.material_temperature, '原料温度', false) }), { signal: abort.signal })
  }, 'B 的真实建议与批次评定来源已保存并重新读取。')
}
function adopt() {
  act(async () => {
    if (!eligible.value) throw new Error('当前工艺不满足采用条件，请按工作台待办处理。')
    if (!adoptionNote.value.trim() || !adopted.pre_cooling_time.trim() || !adopted.washing_pressure.trim()) throw new Error('请填写采用参数及采用或修改依据。')
    await saveAdoptedValues(p.value.record_id, { operator: operator.value.trim(), note: adoptionNote.value.trim(), adopted_values: { ...adopted }, expected_processing_fingerprint: p.value.fingerprint }, { signal: abort.signal })
  }, '采用值已保存并回读；这不是实际加工完成，执行后请另行登记。')
}
function saveOperation() {
  act(async () => {
    if (!w.value.assessment_current || !operation.confirmed || !operation.note.trim()) throw new Error('请确认实际已执行，填写结果，且使用当前有效批次评定。')
    const body = { assessment_id: a.value.id, assessment_revision: a.value.revision, operator: operator.value.trim(), kind: operation.kind, note: operation.note.trim(), performed_at: toZonedTime(operation.performed_at), actually_performed_confirmed: true }
    if (operation.kind === 'processing_completed') Object.assign(body, { processing_id: p.value.record_id, expected_processing_fingerprint: p.value.fingerprint })
    await recordOperation(w.value.batch.id, opIntent(body), { signal: abort.signal })
    operation.confirmed = false
  }, '人员填写的实际执行记录已保存并回读，不修改 AI 原始结果。')
}
onBeforeUnmount(() => abort.abort())
</script>
<template>
  <section class="q4-processing">
    <p v-if="error" class="notice danger" role="alert">{{ error }}</p><p v-if="notice" class="notice success" role="status">{{ notice }}</p>
    <div class="q4-source-banner"><div><small>等级来源 · 已批准批次评定</small><h3>{{ w.assessment_current ? `${a.final_grade} · ${w.grade_label}` : '尚无当前有效评定' }}</h3><p v-if="a">评定 #{{ a.id }} / 第 {{ a.revision }} 版 · {{ w.use_label }} · {{ a.approved_by }}</p></div><span class="badge" :class="w.assessment_current ? 'ready' : 'pending'">{{ w.assessment_current ? '自动带入，不再手填等级' : '请先到批次评定页核定' }}</span></div>
    <div class="q4-two"><section class="q4-panel"><h3>工艺条件</h3><label>操作员<input v-model="operator" :disabled="busy" maxlength="50" placeholder="填写本次确认人员" /></label><div class="form-grid"><label>环境温度（℃）<input v-model="values.temperature" type="number" step="any" :disabled="busy" placeholder="实际录入" /></label><label>环境湿度（%）<input v-model="values.humidity" type="number" step="any" min="0" max="100" :disabled="busy" placeholder="实际录入" /></label><label>原料温度（℃，可选）<input v-model="values.material_temperature" type="number" step="any" :disabled="busy" placeholder="未采集则留空" /></label></div><label class="q4-check"><input v-model="processApproved" type="checkbox" :disabled="busy || !w.assessment_current" /><span>批准使用上述批次评定及用途作为本次工艺输入，温湿度已核对。</span></label><button class="btn full-width" :disabled="busy || !w.assessment_current || !processApproved" @click="generate">{{ busy ? '处理并保存中…' : '生成 B 工艺建议' }}</button><p class="footnote">不会从照片编造温湿度。C／REJECT 仍调用原有 B 规则返回复核或阻断，不伪造参数。</p></section>
    <section class="q4-panel"><h3>B 返回的建议与依据</h3><template v-if="p.record_id"><p class="badge" :class="p.current ? 'ready' : 'pending'">{{ p.current ? `关联评定 #${a.id}` : '历史记录，未关联当前评定' }}</p><p class="q4-action">{{ p.advice?.advice?.action || p.advice?.reason || '当前无可采用参数' }}</p><dl class="q4-facts"><div><dt>建议状态</dt><dd>{{ ({ suggested: '待人工采用', manual_review: '需负责人核查', blocked: '暂停流转' })[p.advice_status] || p.advice_status }}</dd></div><div><dt>预冷时长</dt><dd>{{ p.advice?.advice?.pre_cooling_time || '不提供' }}</dd></div><div><dt>清洗档位</dt><dd>{{ p.advice?.advice?.washing_pressure || '不提供' }}</dd></div><div><dt>规则版本</dt><dd>{{ p.advice?.rule_version }}</dd></div></dl><p>{{ p.advice?.reason }}</p><details><summary>规则与来源详情</summary><p>{{ p.advice?.rule_basis }}</p><p>工艺 #{{ p.record_id }}；来源评定 #{{ p.inputs?.grade_source?.assessment_id || '未关联' }} / v{{ p.inputs?.grade_source?.revision || '—' }}</p></details></template><p v-else class="q4-empty">填写左侧条件后调用 B 服务。建议、输入和评定来源一并保存。</p><p class="footnote">预冷和清洗为现有演示规则；不表示最优生产参数或设备控制指令。</p></section></div>
    <section v-if="p.record_id" class="q4-panel"><h3>人工采用值</h3><p v-if="!eligible" class="notice warning">先完成有效批次评定和实际分选记录，并处理冷链待办。非 suggested 建议不能通过填写参数绕过。</p><form class="form-grid" @submit.prevent="adopt"><label>采用预冷时长<input v-model="adopted.pre_cooling_time" :disabled="busy || !eligible" maxlength="500" /></label><label>采用清洗档位<input v-model="adopted.washing_pressure" :disabled="busy || !eligible" maxlength="500" /></label><label class="full">处理提示<input v-model="adopted.action" :disabled="busy || !eligible" maxlength="500" /></label><label class="full">采用／修改依据<textarea v-model="adoptionNote" :disabled="busy || !eligible" maxlength="2000" rows="2" /></label><button class="btn" :disabled="busy || !eligible">保存采用值及版本历史</button></form><p v-if="p.adopted" class="footnote">已保存采用值；修改后旧执行记录会保留，但不再作为当前参数的执行证据。</p></section>
    <section class="q4-panel"><h3>实际作业登记</h3><p>先按实际作业登记分选；加工建议采用后，再登记真实执行。未执行不能为了推进页面勾选完成。</p><div class="form-grid"><label>记录类型<select v-model="operation.kind" :disabled="busy"><option value="sorting_completed">实际分选已完成</option><option value="processing_completed">实际加工执行已完成</option><option value="reject_disposition">人工拒收处置</option></select></label><label>实际执行时间<input v-model="operation.performed_at" type="datetime-local" :disabled="busy" /></label><label class="full">执行结果与依据<textarea v-model="operation.note" :disabled="busy" maxlength="2000" rows="3" placeholder="记录实际作业、异常及结果；不是拟执行计划" /></label><label class="q4-check full"><input v-model="operation.confirmed" type="checkbox" :disabled="busy" /><span>我确认上述作业已经实际发生，记录为人工填报而非设备自动反馈。</span></label><button class="btn" :disabled="busy || !w.assessment_current || !operation.confirmed" @click="saveOperation">保存实际作业记录</button></div></section>
    <details class="q4-panel"><summary>已保存执行与采用历史</summary><article v-for="o in w.operations" :key="o.id" class="q4-history"><b>{{ ({ sorting_completed: '分选记录', processing_completed: '加工执行', reject_disposition: '拒收处置' })[o.kind] }}</b><span>{{ o.performed_at }} · {{ o.operator }} · {{ o.current ? '当前证据' : '历史证据' }}</span><p>{{ o.note }}</p></article><article v-for="v in w.adoption_history" :key="`adopt-${v.id}`" class="q4-history"><b>工艺 #{{ v.processing_id }} · 采用版本 #{{ v.id }}</b><span>{{ v.created_at }} · {{ v.operator }}</span><p>{{ v.values?.pre_cooling_time }} / {{ v.values?.washing_pressure }} · {{ v.note }}</p></article></details>
  </section>
</template>
