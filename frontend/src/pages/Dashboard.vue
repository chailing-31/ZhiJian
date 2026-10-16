<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { getDashboard } from '../api/engineering.js'
import Icon from '../components/Icon.vue'
import GradeBadge from '../components/GradeBadge.vue'
import EmptyState from '../components/EmptyState.vue'
import { attentionFor, gradeSource, gradeStatusFor, workbenchLocation } from '../utils/presentation.js'
const data = ref(null), error = ref(''), loading = ref(false), abort = new AbortController()
const refreshedAt = ref('')
const recent = computed(() => data.value?.recent || [])
const attention = computed(() => recent.value.map(w => ({ w, issue: attentionFor(w) })).filter(v => v.issue))
const last = computed(() => {
  let id = ''
  try { id = sessionStorage.getItem('zhijian.activeBatch') || '' } catch { /* optional preference */ }
  return recent.value.find(w => String(w.batch.id) === id) || recent.value[0]
})
const lastLabel = computed(() => {
  let id = ''
  try { id = sessionStorage.getItem('zhijian.activeBatch') || '' } catch { /* optional preference */ }
  return last.value && String(last.value.batch.id) === id ? '上次打开' : '最近登记'
})
async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  try { const value = await getDashboard({ signal: abort.signal }); if (!abort.signal.aborted) { data.value = value; refreshedAt.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai' }).format(new Date()) } }
  catch (e) { if (!abort.signal.aborted) error.value = e.message }
  finally { if (!abort.signal.aborted) loading.value = false }
}
onMounted(load); onBeforeUnmount(() => abort.abort())
</script>
<template>
  <header class="zj-dashboard-heading"><div><h1>工作台</h1><p class="lead">{{ refreshedAt ? `数据更新于 ${refreshedAt}` : loading ? '正在读取业务数据…' : '批次与待办' }}</p></div><RouterLink class="btn" :to="{ path: '/batches', query: { new: '1' } }"><Icon name="plus" />新建批次</RouterLink></header>
  <p v-if="error" class="notice danger" role="alert"><Icon name="alert" />{{ error }} <button class="e-text-button" @click="load">重新加载</button></p>
  <div class="ui-overview-metrics">
    <RouterLink to="/batches" class="ui-metric"><div><span>登记批次</span><Icon name="box" /></div><strong>{{ data?.batch_count ?? '—' }}<small>批</small></strong><p>全部已登记批次</p></RouterLink>
    <RouterLink to="/inspection" class="ui-metric"><div><span>质检记录</span><Icon name="scan" /></div><strong>{{ data?.inspection_count ?? '—' }}<small>条</small></strong><p>已保存照片检测</p></RouterLink>
    <RouterLink to="/coldchain" class="ui-metric" :class="{ 'has-alert': data?.open_alert_count > 0 }"><div><span>冷链待办</span><Icon name="thermometer" /></div><strong>{{ data?.open_alert_count ?? '—' }}<small>条</small></strong><p>未处置告警</p></RouterLink>
  </div>
  <div class="ui-dashboard-columns">
    <section class="card ui-recent-panel"><div class="section-row"><h2>最近工作批次 <span class="count">{{ recent.length }}</span></h2><button class="ui-icon-button" aria-label="刷新工作台" :disabled="loading" @click="load"><Icon name="refresh" :class="{ 'ui-spinning': loading }" /></button></div>
      <div v-if="loading && !data" class="ui-skeleton-list" aria-label="正在加载批次" role="status"><div v-for="i in 3" :key="i" class="ui-skeleton" /></div>
      <div v-else-if="recent.length" class="ui-recent-list"><div class="ui-recent-head" aria-hidden="true"><span>批次 / 产品</span><span>当前等级</span><span>下一步</span></div><article v-for="w in recent" :key="w.batch.id" class="ui-recent-row"><div class="ui-recent-identity"><RouterLink :to="workbenchLocation(w)">{{ w.batch.batch_code }}</RouterLink><p>{{ w.batch.product }}<span>·</span>{{ w.batch.variety || w.batch.origin || '未填写' }}</p></div><GradeBadge :grade="w.assessment_current ? w.assessment.final_grade : ''" :source="w.assessment_current ? gradeSource(w.assessment) : gradeStatusFor(w).label" compact /><RouterLink class="ui-next-link" :to="workbenchLocation(w, w.next_action.tab)"><span>{{ w.next_action.label }}</span><Icon name="arrow" /></RouterLink></article></div>
      <EmptyState v-else :title="error ? '批次读取失败' : '暂无批次'" :description="error ? '重新加载后可继续查看。' : '新建批次后即可上传质检照片。'" icon="box"><RouterLink v-if="!error" class="btn" :to="{ path: '/batches', query: { new: '1' } }"><Icon name="plus" />新建批次</RouterLink></EmptyState>
      <RouterLink class="ui-card-footer" to="/batches">查看全部批次 <Icon name="arrow" /></RouterLink>
    </section>
    <aside class="ui-dashboard-aside">
      <section class="card ui-attention-panel"><div class="section-row"><h2>最近批次待办</h2><span class="count">{{ data ? attention.length : '—' }}</span></div><div v-if="attention.length" class="ui-attention-list"><RouterLink v-for="{ w, issue } in attention" :key="w.batch.id" :to="workbenchLocation(w, issue.tab)"><Icon :name="issue.icon" /><div><strong>{{ issue.label }}</strong><small>{{ w.batch.batch_code }}</small></div><Icon name="chevron" /></RouterLink></div><div v-else class="ui-calm-state"><strong>{{ data ? '暂无待处理事项' : '等待读取状态' }}</strong></div></section>
      <section v-if="last" class="card ui-last-batch"><h2>{{ lastLabel }}</h2><strong>{{ last.batch.batch_code }}</strong><p>{{ last.batch.product }} · {{ last.batch.origin || '产地未填写' }}</p><RouterLink class="btn secondary" :to="workbenchLocation(last, last.next_action.tab)">继续处理<Icon name="arrow" /></RouterLink></section>
    </aside>
  </div>
</template>
