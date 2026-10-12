<script setup>
import { computed } from 'vue'
import { listBatches } from '../api/batch.js'
import { useAsyncData } from '../composables/useAsyncData.js'
import { modules } from '../config/modules.js'
import { formatTime, statusLabel } from '../utils/batch.js'
import PageHeading from '../components/PageHeading.vue'
import Icon from '../components/Icon.vue'
import StateNotice from '../components/StateNotice.vue'
import EmptyState from '../components/EmptyState.vue'
const { data, loading, error, reload } = useAsyncData(signal => listBatches({ signal }))
const latest = computed(() => (data.value || []).slice(0, 5))
</script>
<template>
  <PageHeading eyebrow="全链路品控 · 工作台" title="批次总览" description="从原料登记到公开溯源，让同一批次的每一条记录有据可查。"><button class="btn secondary" :disabled="loading" @click="reload"><Icon name="refresh" />刷新</button><RouterLink class="btn" to="/batches"><Icon name="plus" />管理批次</RouterLink></PageHeading>
  <StateNotice :loading="loading" :error="error" @retry="reload" />
  <div class="stats-grid">
    <section class="stat-card"><div class="stat-top"><span>已登记批次</span><Icon name="box" /></div><strong>{{ data ? data.length : '—' }}</strong><small>{{ error ? '读取失败，未展示旧数据' : '来自批次数据库' }}</small></section>
    <section class="stat-card"><div class="stat-top"><span>待复核质检</span><Icon name="scan" /></div><strong>—</strong><small>质检接口待接入，不显示占位数量</small></section>
    <section class="stat-card"><div class="stat-top"><span>待处置告警</span><Icon name="thermometer" /></div><strong>—</strong><small>冷链接口待接入，不代表没有风险</small></section>
    <section class="stat-card trace-stat"><div class="stat-top"><span>公开溯源</span><Icon name="trace" /></div><strong class="word-stat">已接入</strong><small>按批次查询已公开的事件</small></section>
  </div>
  <div class="dashboard-grid">
    <section class="card"><div class="section-row"><div><p class="eyebrow">BATCH RECORDS</p><h2>最近批次</h2></div><RouterLink class="text-link" to="/batches">查看全部 →</RouterLink></div>
      <EmptyState v-if="data && !data.length" title="还没有登记批次" description="从批次管理创建第一批原料。" />
      <div v-for="batch in latest" :key="batch.batch_id" class="batch-row"><div class="batch-avatar"><Icon name="box" /></div><div class="batch-row-main"><RouterLink :to="`/batches/${batch.batch_id}`">{{ batch.batch_code }}</RouterLink><p>{{ batch.product }} · {{ batch.variety || '品种未录入' }} · {{ batch.origin || '产地未录入' }}</p><small>{{ formatTime(batch.created_at) }}</small></div><span class="badge ready">{{ statusLabel(batch.status) }}</span><RouterLink :to="`/batches/${batch.batch_id}`" class="icon-link" :aria-label="`查看 ${batch.batch_code}`"><Icon name="arrow" /></RouterLink></div>
      <p v-if="error" class="muted">批次列表读取失败，请重试。</p>
    </section>
    <section class="card scope-card"><p class="eyebrow">当前版本</p><h2>先把页面连起来，<br />再逐步接入能力。</h2><p>批次和公开时间线读取真实数据库。AI、加工与冷链已预留独立页面，暂不生成检测结论、工艺建议或告警结果。</p><div class="scope-note"><Icon name="leaf" /><span>当前目标：页面完整、边界清晰、模块可独立开发。</span></div></section>
  </div>
  <div class="section-row section-heading"><div><p class="eyebrow">WORKSPACES</p><h2>六大业务模块</h2></div><span class="muted">围绕同一个 batch_id 协作</span></div>
  <div class="module-grid"><RouterLink v-for="item in modules" :key="item.key" :to="item.path" class="module-card"><div class="section-row"><span class="module-icon"><Icon :name="item.icon" /></span><span class="badge" :class="item.tone">{{ item.status }}</span></div><h3>{{ item.name }}</h3><p>{{ item.description }}</p><span class="module-arrow">进入工作区 <Icon name="arrow" /></span></RouterLink></div>
</template>
