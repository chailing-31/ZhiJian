<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { assessmentAdvice, recordOperation, getWorkbench } from '../../api/batch-workbench.js'
import { formatTime } from '../../utils/batch.js'
import { saveAdoptedValues } from '../../api/processing.js'
import { canAdopt, processNumber, writeIntent, localTimeInput, toZonedTime } from '../../utils/batch-workbench.js'
import ProcessingTrace from './ProcessingTrace.vue'
const props = defineProps({ workbench: { type: Object, required: true } })
const emit = defineEmits(['changed'])
const busy = ref(false), error = ref(''), notice = ref(''), operator = ref('')
const trace = ref(null), errorCode = ref('')
const values = reactive({ temperature: '', humidity: '', material_temperature: '' })
const adopted = reactive({ pre_cooling_time: '', washing_pressure: '', action: '' }), adoptionNote = ref('')
const operation = reactive({ kind: 'sorting_completed', note: '', performed_at: localTimeInput(), confirmed: false })
const adviceIntent = writeIntent(), opIntent = writeIntent(); const abort = new AbortController()
const w = computed(() => props.workbench), p = computed(() => w.value.processing || {}), a = computed(() => w.value.assessment)
const eligible = computed(() => canAdopt(w.value))
watch(() => p.value.fingerprint, () => { const v = p.value.adopted_values || p.value.advice?.advice || {}; Object.assign(adopted, { pre_cooling_time: v.pre_cooling_time || '', washing_pressure: v.washing_pressure || '', action: v.action || '' }); adoptionNote.value = '' }, { immediate: true })
watch(() => a.value?.id, () => { operation.confirmed = false; operation.kind = ['C','REJECT'].includes(a.value?.final_grade) ? 'quality_disposition' : 'sorting_completed' }, { immediate: true })
try { operator.value = localStorage.getItem('zhijian.operator') || '' } catch { /* optional storage */ }
async function act(fn, message) {
  if (busy.value) return; busy.value = true; error.value = ''; errorCode.value = ''; notice.value = ''
  let saved = false
  try { if (!operator.value.trim()) throw new Error('请填写操作员。'); try { localStorage.setItem('zhijian.operator', operator.value.trim()) } catch { /* optional storage */ } const result = await fn(); if (!result?.persisted) throw new Error('接口未确认保存，请刷新核对记录。'); saved = true; if (abort.signal.aborted) return; const back = await getWorkbench(w.value.batch.id, { signal: abort.signal }); if (!abort.signal.aborted) { emit('changed', back); notice.value = message } }
  catch (e) { if (!abort.signal.aborted) { errorCode.value = e.code || ''; error.value = `${saved ? '记录已保存，但回读失败，请刷新核对。' : ''}${e.message}` } }
  finally { busy.value = false }
}
function generate() {
  act(async () => {
    if (!w.value.assessment_current) throw new Error('当前等级来源不可用，请先处理工作台提示。')
    const required = a.value.final_grade !== 'REJECT'; const humidity = processNumber(values.humidity, '环境湿度', required); if (humidity !== null && (humidity < 0 || humidity > 100)) throw new Error('湿度必须在0—100%。')
    return assessmentAdvice(w.value.batch.id, adviceIntent({ assessment_id: a.value.id, assessment_revision: a.value.revision, operator: operator.value.trim(), use_as_process_input_confirmed: true, temperature: processNumber(values.temperature, '环境温度', required), humidity, material_temperature: processNumber(values.material_temperature, '原料温度', false) }), { signal: abort.signal })
  }, '加工建议已保存，当前等级来源已关联。')
}
function adopt() {
  act(async () => {
    if (!eligible.value) throw new Error('当前工艺不满足采用条件，请按工作台待办处理。')
    if (!adoptionNote.value.trim() || !adopted.pre_cooling_time.trim() || !adopted.washing_pressure.trim()) throw new Error('请填写采用参数及采用或修改依据。')
    return saveAdoptedValues(p.value.record_id, { operator: operator.value.trim(), note: adoptionNote.value.trim(), adopted_values: { ...adopted }, expected_processing_fingerprint: p.value.fingerprint }, { signal: abort.signal })
  }, '采用值已保存并回读；这不是实际加工完成，执行后请另行登记。')
}
function saveOperation() {
  act(async () => {
    if (!w.value.assessment_current || !operation.confirmed || !operation.note.trim()) throw new Error('请确认实际已执行，填写结果，且使用当前有效批次评定。')
    const body = { assessment_id: a.value.id, assessment_revision: a.value.revision, operator: operator.value.trim(), kind: operation.kind, note: operation.note.trim(), performed_at: toZonedTime(operation.performed_at), actually_performed_confirmed: true }
    if (operation.kind === 'processing_completed') Object.assign(body, { processing_id: p.value.record_id, expected_processing_fingerprint: p.value.fingerprint })
    const result = await recordOperation(w.value.batch.id, opIntent(body), { signal: abort.signal })
    operation.confirmed = false
    return result
  }, '人员填写的实际执行记录已保存并回读，不修改 AI 原始结果。')
}
onBeforeUnmount(() => abort.abort())
</script>
<template>
  <section class="q4-processing">
    <p v-if="error" class="notice danger" role="alert">{{ error }}</p><p v-if="notice" class="notice success" role="status">{{ notice }}</p>
    <RouterLink v-if="errorCode === 'COLDCHAIN_REVIEW_REQUIRED'" class="btn secondary" :to="{ path: `/batches/${w.batch.id}`, query: { tab: 'coldchain' } }">前往核查冷链</RouterLink>
    <section class="q4-panel"><div class="section-row"><h3>加工记录与溯源</h3><span class="badge" :class="w.publication?.current ? 'ready' : 'pending'">{{ !w.publication ? '尚未发布' : w.publication.current ? '已发布，来源一致' : '记录已更新，待重新发布' }}</span></div><p>保存后的加工记录可在内部报告查看。公开页展示经预览确认的摘要，加工更新后需重新发布。</p><div class="button-row"><RouterLink class="btn secondary" :to="`/batches/${w.batch.id}/report`">查看内部报告</RouterLink><RouterLink class="btn secondary" :to="{ path: `/batches/${w.batch.id}`, query: { tab: 'report' } }">前往溯源发布</RouterLink><button v-if="p.record_id" class="btn secondary" @click="trace?.open(p.record_id)">查看当前加工来源</button></div></section>
    <div class="q4-source-banner"><div><small>{{ a?.source === 'ai_initial' ? '等级来源 · AI 初评' : '等级来源 · 人工修正' }}</small><h3>{{ w.assessment_current ? `${a.final_grade} · ${w.grade_label}` : '尚无当前有效评定' }}</h3><p v-if="a">评定 #{{ a.id }} / 第 {{ a.revision }} 版 · {{ w.use_label }} · {{ a.approved_by }}</p></div><span class="badge" :class="w.assessment_current ? 'ready' : 'pending'">{{ w.assessment_current ? '自动带入，不再手填等级' : '请先处理等级来源问题' }}</span></div>
    <div class="q4-two"><section class="q4-panel"><h3>加工条件</h3><label>操作员<input v-model="operator" :disabled="busy" maxlength="50" placeholder="填写本次确认人员" /></label><div class="form-grid"><label>环境温度（℃）<input v-model="values.temperature" type="number" step="any" :disabled="busy" placeholder="实际录入" /></label><label>环境湿度（%）<input v-model="values.humidity" type="number" step="any" min="0" max="100" :disabled="busy" placeholder="实际录入" /></label><label>原料温度（℃，可选）<input v-model="values.material_temperature" type="number" step="any" :disabled="busy" placeholder="未采集则留空" /></label></div><button class="btn full-width" :disabled="busy || !w.assessment_current" @click="generate">{{ busy ? '处理并保存中…' : '生成加工建议' }}</button><p class="footnote">A/B 根据实际环境计算工艺；C 进入用途评估，REJECT 暂停正常流转。温湿度请按实际记录填写。</p></section>
    <section class="q4-panel"><h3>加工建议</h3><template v-if="p.record_id"><p class="badge" :class="p.current ? 'ready' : 'pending'">{{ p.current ? `关联评定 #${a.id}` : '历史记录，未关联当前评定' }}</p><p class="q4-action">{{ p.advice?.advice?.action || p.advice?.reason || '当前无可采用参数' }}</p><dl class="q4-facts"><div><dt>建议状态</dt><dd>{{ ({ suggested: '待人工采用', manual_review: '需负责人核查', blocked: '暂停流转' })[p.advice_status] || p.advice_status }}</dd></div><div><dt>预冷时长</dt><dd>{{ p.advice?.advice?.pre_cooling_time || '不提供' }}</dd></div><div><dt>清洗档位</dt><dd>{{ p.advice?.advice?.washing_pressure || '不提供' }}</dd></div><div><dt>规则版本</dt><dd>{{ p.advice?.rule_version }}</dd></div></dl><p>{{ p.advice?.reason }}</p><details><summary>规则与来源详情</summary><p>{{ p.advice?.rule_basis }}</p><p>工艺 #{{ p.record_id }}；来源评定 #{{ p.inputs?.grade_source?.assessment_id || '未关联' }} / v{{ p.inputs?.grade_source?.revision || '—' }}</p></details></template><p v-else class="q4-empty">填写加工条件后生成建议，等级会自动带入。</p><p class="footnote">建议由现有工艺规则计算，实际参数由作业人员检查采用。</p></section></div>
    <section v-if="p.record_id" class="q4-panel"><h3>人工采用值</h3><p v-if="!eligible" class="notice warning">采用条件：当前 A/B 等级、实际分选已登记、建议可用且没有未处理的冷链问题。</p><form class="form-grid" @submit.prevent="adopt"><label>采用预冷时长<input v-model="adopted.pre_cooling_time" :disabled="busy || !eligible" maxlength="500" /></label><label>采用清洗档位<input v-model="adopted.washing_pressure" :disabled="busy || !eligible" maxlength="500" /></label><label class="full">处理提示<input v-model="adopted.action" :disabled="busy || !eligible" maxlength="500" /></label><label class="full">采用／修改依据<textarea v-model="adoptionNote" :disabled="busy || !eligible" maxlength="2000" rows="2" /></label><button class="btn" :disabled="busy || !eligible">保存采用值及版本历史</button></form><p v-if="p.adopted" class="footnote">已保存采用值；修改后旧执行记录会保留，但不再作为当前参数的执行证据。</p></section>
    <section class="q4-panel"><h3>实际作业登记</h3><p>先按实际作业登记分选；加工建议采用后，再登记真实执行。未执行不能为了推进页面勾选完成。</p><div class="form-grid"><label>记录类型<select v-model="operation.kind" :disabled="busy"><option v-if="['A','B'].includes(a?.final_grade)" value="sorting_completed">实际分选已完成</option><option v-if="['A','B'].includes(a?.final_grade)" value="processing_completed">实际加工执行已完成</option><option v-if="['C','REJECT'].includes(a?.final_grade)" value="quality_disposition">品质处置（评估 / 隔离 / 退货）</option></select></label><label>实际执行时间<input v-model="operation.performed_at" type="datetime-local" :disabled="busy" /></label><label class="full">执行结果与依据<textarea v-model="operation.note" :disabled="busy" maxlength="2000" rows="3" placeholder="记录实际作业、异常及结果；不是拟执行计划" /></label><label class="q4-check full"><input v-model="operation.confirmed" type="checkbox" :disabled="busy" /><span>我确认上述作业已经实际发生，记录为人工填报而非设备自动反馈。</span></label><button class="btn" :disabled="busy || !w.assessment_current || !operation.confirmed" @click="saveOperation">保存实际作业记录</button></div></section>
    <details class="q4-panel"><summary>已保存执行与采用历史</summary><article v-for="o in w.operations" :key="o.id" class="q4-history"><b>{{ ({ sorting_completed: '分选记录', processing_completed: '加工执行', reject_disposition: '拒收处置', quality_disposition: '品质处置' })[o.kind] }}</b><span>{{ formatTime(o.performed_at) }} · {{ o.operator }} · {{ o.current ? '当前证据' : '历史证据' }}</span><p>{{ o.note }}</p></article><article v-for="v in w.adoption_history" :key="`adopt-${v.id}`" class="q4-history"><b>工艺 #{{ v.processing_id }} · 采用版本 #{{ v.id }}</b><span>{{ formatTime(v.created_at) }} · {{ v.operator }}</span><p>{{ v.values?.pre_cooling_time }} / {{ v.values?.washing_pressure }} · {{ v.note }}</p></article></details>
    <ProcessingTrace ref="trace" :batch-id="w.batch.id" :refresh-key="w.fingerprint" />
  </section>
</template>
