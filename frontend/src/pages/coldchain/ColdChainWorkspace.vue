<script setup>
import ColdchainTrend from '../../components/ColdchainTrend.vue'
import { computed, ref } from 'vue'
import { getColdchain, importReadings, resolveAlert } from '../../api/coldchain.js'
import { parseColdchainCsv } from '../../utils/coldchain-csv.js'
import EmptyState from '../../components/EmptyState.vue'
const props = defineProps({ batch: { type: Object, required: true } })
const emit = defineEmits(['changed'])
const flowRevision = ref(0)
const fileName = ref(''), error = ref(''), loading = ref(false), data = ref(null), selected = ref(null), resolver = ref(''), resolution = ref(''), message = ref('')
const latest = computed(() => data.value?.readings?.[0] || null)
const alerts = computed(() => data.value?.alerts || [])
async function refresh() { loading.value = true; error.value = ''; try { data.value = await getColdchain(props.batch.batch_id); emit('changed'); flowRevision.value++ } catch (err) { error.value = err.message } finally { loading.value = false } }
async function upload(event) {
  if (loading.value) return
  fileName.value = ''; error.value = ''; message.value = ''
  const file = event.target.files?.[0]; if (!file) return
  if (!file.name.toLowerCase().endsWith('.csv')) { error.value = '请选择 CSV 文件。'; event.target.value = ''; return }
  if (file.size > 5 * 1024 * 1024) { error.value = '文件超过本地选择限制 5 MiB。'; event.target.value = ''; return }
  let uploaded = 0
  loading.value = true
  try { const readings = parseColdchainCsv(await file.text()); fileName.value = file.name; for (const reading of readings) { await importReadings(props.batch.batch_id, reading); uploaded++ }; message.value = `已上报 ${readings.length} 条读数。`; await refresh() }
  catch (err) { const message = uploaded ? `已保存前 ${uploaded} 条，其余未上报；可重新导入同一文件。${err.message}` : err.message; await refresh(); error.value = message }
  finally { loading.value = false; event.target.value = '' }
}
async function resolve() { if (loading.value || !selected.value || !resolver.value.trim() || !resolution.value.trim()) return; loading.value = true; try { await resolveAlert(selected.value.alert_id, { resolved_by: resolver.value.trim(), resolution: resolution.value.trim() }); message.value = '告警处置已保存。'; selected.value = null; await refresh() } catch (err) { error.value = err.message } finally { loading.value = false } }
refresh()
</script>
<template>
  <section class="card"><div class="section-row"><h2>导入冷链观测</h2><span class="badge neutral">{{ loading ? '正在处理' : '可导入 CSV' }}</span></div><div class="input-strip"><label class="file-control"><span class="btn secondary">选择并上报 CSV</span><input type="file" accept=".csv,text/csv" aria-label="选择冷链 CSV 文件" @change="upload" /></label><span class="muted break-word">{{ fileName || '尚未选择文件' }}</span><button class="btn secondary" :disabled="loading" @click="refresh">刷新历史</button></div><p v-if="error" class="notice danger" role="alert">{{ error }}</p><p v-if="message" class="notice success" role="status">{{ message }}</p><p class="footnote">CSV 必须含 timestamp、temperature、humidity、source；source=simulation 表示模拟数据，source=sensor 表示真实采集数据。</p></section>
  <section class="card"><div class="section-row"><h2>温湿度观测</h2><span class="badge ready">{{ data?.summary?.reading_count ?? '—' }} 条已保存</span></div><div v-if="!data || !data.summary?.reading_count" class="chart-empty"><EmptyState :title="loading ? '正在读取观测记录…' : error ? '观测记录读取失败' : '暂无已保存读数'" description="导入 CSV 后这里显示真实保存的最近读数与规则结果。" icon="thermometer" /></div><div v-else class="result-summary"><div><span>最近温度</span><strong>{{ latest?.temperature ?? '—' }} ℃</strong></div><div><span>最近湿度</span><strong>{{ latest?.humidity ?? '—' }} %</strong></div><div><span>数据来源</span><strong>{{ latest?.source === 'sensor' ? '传感器' : latest?.source === 'simulation' ? '模拟数据' : '未记录' }}</strong></div><div><span>读数时间</span><strong>{{ latest?.timestamp || '—' }}</strong></div></div></section>
  <ColdchainTrend :readings="data?.readings || []" />
  <section class="card"><div class="section-row"><h2>告警与处置</h2><span class="badge" :class="alerts.length ? 'pending' : 'neutral'">{{ alerts.length }} 条记录</span></div><div class="table-wrap"><table><thead><tr><th>触发时间</th><th>级别</th><th>原因</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in alerts" :key="item.alert_id"><td>{{ item.started_at }}</td><td>{{ item.level }}</td><td>{{ item.reason }}</td><td>{{ ({ active: '待处置', observed: '已恢复，待确认', resolved: '已处置' })[item.status] || item.status }}</td><td><button class="btn secondary" :disabled="item.status === 'resolved'" @click="selected = item">{{ item.status === 'resolved' ? '已处置' : '处置' }}</button></td></tr><tr v-if="!alerts.length"><td colspan="5" class="empty-cell">暂无已保存告警记录</td></tr></tbody></table></div><div v-if="selected" class="form-grid"><label>处置人<input v-model.trim="resolver" /></label><label>处置说明<input v-model.trim="resolution" /></label><button class="btn" :disabled="loading || !resolver || !resolution" @click="resolve">保存处置</button></div></section>
</template>
