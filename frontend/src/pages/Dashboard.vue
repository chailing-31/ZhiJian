<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { getWorkspaceBatches } from '../api/engineering.js'
import BatchFlowStatus from '../components/BatchFlowStatus.vue'
const batches = ref([]), error = ref(''), loading = ref(false), selected = ref('')
const abort = new AbortController()
const batch = computed(() => batches.value.find(b => String(b.batch_id) === String(selected.value)))
async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  try { const data = await getWorkspaceBatches({ signal: abort.signal }); if (!abort.signal.aborted) { batches.value = data; if (!batch.value && data.length) selected.value = String(data[0].batch_id) } }
  catch (e) { if (!abort.signal.aborted) error.value = e.message }
  finally { if (!abort.signal.aborted) loading.value = false }
}
onMounted(load); onBeforeUnmount(() => abort.abort())
</script>
<template>
  <section class="e-hero"><div><p class="e-kicker">SMART FRESH · 批次协同质检</p><h1>从一张照片，<br />到一份可追溯记录。</h1><p>上传、检查、复核与后续处理，在同一个批次内完成。</p><div class="e-hero-actions"><RouterLink class="btn" :to="batch ? `/inspection?batch_id=${batch.batch_id}` : '/batches'">开始视觉质检 →</RouterLink><RouterLink class="btn secondary" to="/batches">管理批次</RouterLink></div></div><div class="e-hero-card"><span class="e-kicker">当前工作批次</span><h2>{{ batch?.batch_code || '选择或创建批次' }}</h2><p>{{ batch ? `${batch.product} · ${batch.variety || '品种待补充'}` : '不使用虚构批次填充界面' }}</p><label>切换批次<select v-model="selected" :disabled="loading || !batches.length"><option value="" disabled>请选择</option><option v-for="b in batches" :key="b.batch_id" :value="String(b.batch_id)">{{ b.batch_code }}</option></select></label><p class="e-note">{{ batch?.origin || '产地信息尚未录入' }}</p></div></section>
  <p v-if="error" class="notice danger" role="alert">{{ error }}<button class="e-text-button" @click="load">重试</button></p>
  <BatchFlowStatus v-if="batch" :batch-id="batch.batch_id" />
  <section class="e-module-grid"><RouterLink :to="batch ? `/inspection?batch_id=${batch.batch_id}` : '/inspection'" class="e-module-card"><span class="e-module-index">01 / VISION</span><h2>可视化外观检查</h2><p>真实图像、交互检测框、原图对照、人工等级与复核留痕。</p><strong>进入质检工作区 →</strong></RouterLink><RouterLink :to="batch ? `/processing?batch_id=${batch.batch_id}` : '/processing'" class="e-module-card"><span class="e-module-index">02 / PROCESS</span><h2>下一步如何处理</h2><p>引用经人工批准的等级，生成规则建议并保存实际采用值。</p><strong>查看加工建议 →</strong></RouterLink><RouterLink :to="batch ? `/coldchain?batch_id=${batch.batch_id}` : '/coldchain'" class="e-module-card"><span class="e-module-index">03 / TRACE</span><h2>全程记录与追溯</h2><p>冷链读数、异常处置、综合报告与授权公开的溯源摘要。</p><strong>查看冷链记录 →</strong></RouterLink></section>
  <section class="card"><div class="section-row"><h2>最近登记批次</h2><button class="btn secondary" :disabled="loading" @click="load">刷新</button></div><div class="table-wrap"><table><thead><tr><th>批次编号</th><th>产品 / 品种</th><th>产地</th><th>操作</th></tr></thead><tbody><tr v-for="b in batches.slice(0, 8)" :key="b.batch_id"><td><strong>{{ b.batch_code }}</strong></td><td>{{ b.product }} / {{ b.variety || '未录入' }}</td><td>{{ b.origin || '未录入' }}</td><td><RouterLink class="text-link" :to="`/inspection?batch_id=${b.batch_id}`">开始质检</RouterLink> · <RouterLink class="text-link" :to="`/batches/${b.batch_id}/report`">综合记录</RouterLink></td></tr><tr v-if="!batches.length"><td colspan="4">{{ loading ? '正在读取数据库…' : error ? '读取失败，无法判断批次数量' : '尚无批次，请先登记。' }}</td></tr></tbody></table></div></section>
</template>
