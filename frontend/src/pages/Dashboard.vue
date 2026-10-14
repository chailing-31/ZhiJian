<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { getDashboard } from '../api/engineering.js'
import Icon from '../components/Icon.vue'
const data = ref(null), error = ref(''), loading = ref(false), abort = new AbortController()
async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  try { const value = await getDashboard({ signal: abort.signal }); if (!abort.signal.aborted) data.value = value }
  catch (e) { if (!abort.signal.aborted) error.value = e.message }
  finally { if (!abort.signal.aborted) loading.value = false }
}
onMounted(load); onBeforeUnmount(() => abort.abort())
</script>
<template>
  <header class="zj-dashboard-heading"><div><p class="eyebrow">品控工作台</p><h1>让每一批果实，流转有据。</h1><p class="muted">从 AI 初评到加工、冷链和溯源，围绕同一批次推进。</p></div><RouterLink class="btn" to="/batches"><Icon name="plus" />登记新批次</RouterLink></header>
  <p v-if="error" class="notice danger" role="alert">{{ error }} <button class="e-text-button" @click="load">重新加载</button></p>
  <div class="zj-dashboard-stats"><article><Icon name="box" /><span>业务批次</span><strong>{{ data?.batch_count ?? '—' }}</strong><small>已登记批次总数</small></article><article><Icon name="scan" /><span>AI 质检记录</span><strong>{{ data?.inspection_count ?? '—' }}</strong><small>已保存的照片检测</small></article><article><Icon name="thermometer" /><span>待处理告警</span><strong>{{ data?.open_alert_count ?? '—' }}</strong><small>按冷链处置记录统计</small></article></div>
  <section class="zj-start-panel"><div><span class="zj-start-label">从照片开始</span><h2>上传后自动评级，下一步清楚可见。</h2><p>系统生成 A/B/C/REJECT 外观初评，直接带入加工品控。需要时再人工检查修正。</p><RouterLink class="btn" :to="data?.recent?.length ? `/batches/${data.recent[0].batch.id}?tab=inspection` : '/batches'">开始 AI 质检 <span>→</span></RouterLink></div><div class="zj-start-visual" aria-hidden="true"><span>A</span><span>B</span><span>C</span><span>拒收</span><small>自动初评 · 持续留痕</small></div></section>
  <section class="card"><div class="section-row"><div><h2>最近工作批次</h2><p class="muted">查看当前等级与待办，继续上次工作。</p></div><button class="btn secondary" :disabled="loading" @click="load">{{ loading ? '更新中…' : '刷新' }}</button></div>
    <div class="zj-recent-grid"><RouterLink v-for="w in data?.recent || []" :key="w.batch.id" :to="`/batches/${w.batch.id}`" class="zj-batch-card"><div><span class="zj-grade-chip" :data-grade="w.assessment_current ? w.assessment.final_grade : ''">{{ w.assessment_current ? w.assessment.final_grade === 'REJECT' ? '拒收' : w.assessment.final_grade : '—' }}</span><span class="badge neutral">{{ w.assessment_current ? w.assessment.source === 'ai_initial' ? 'AI 初评' : '人工修正' : '等待质检' }}</span></div><h3>{{ w.batch.batch_code }}</h3><p>{{ w.batch.product }} · {{ w.batch.variety || '品种待录入' }} · {{ w.batch.origin || '产地待录入' }}</p><div class="zj-batch-next"><span>{{ w.next_action.label }}</span><strong>→</strong></div></RouterLink></div>
    <p v-if="!data?.recent?.length" class="q4-empty">{{ loading ? '正在读取批次记录…' : error ? '读取失败，请重试' : '暂无工作批次，登记后即可开始质检。' }}</p>
    <RouterLink class="text-link" to="/batches">查看全部批次 →</RouterLink>
  </section>
</template>
