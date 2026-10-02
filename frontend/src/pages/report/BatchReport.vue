<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { getFormalReport } from '../../api/report.js'
import { useAsyncData } from '../../composables/useAsyncData.js'
import { formatTime, statusLabel } from '../../utils/batch.js'
import PageHeading from '../../components/PageHeading.vue'
import StateNotice from '../../components/StateNotice.vue'
import EventTimeline from '../../components/EventTimeline.vue'
import EmptyState from '../../components/EmptyState.vue'

const route = useRoute()
const { data: report, loading, error, reload } = useAsyncData(
  signal => getFormalReport(route.params.id, { signal }),
  () => route.params.id,
)
const batch = computed(() => report.value?.batch)
const cold = computed(() => report.value?.coldchain?.summary || {})
function print() { window.print() }
function value(v, suffix = '') {
  if (v === null || v === undefined || v === '') return '未记录'
  if (typeof v === 'number') return `${Math.round(v * 100) / 100}${suffix}`
  return `${v}${suffix}`
}
function compactJson(data) {
  if (data === null || data === undefined) return '未记录'
  try { return JSON.stringify(data) } catch { return String(data) }
}
</script>

<template>
  <PageHeading eyebrow="管理端 · 只读聚合" title="批次综合记录" description="汇总数据库中已经保存的质检、人工复核、加工、冷链、告警与事件；不自动补全缺失环节，也不推导整批合格结论。">
    <RouterLink class="btn secondary no-print" :to="`/batches/${route.params.id}`">返回批次</RouterLink>
    <button class="btn no-print" :disabled="!report" @click="print">打印 / 另存为 PDF</button>
  </PageHeading>
  <StateNotice :loading="loading" :error="error" @retry="reload" />

  <template v-if="report && batch">
    <div class="notice warning">{{ report.disclaimer }}</div>
    <div v-if="report.warnings?.length" class="notice danger"><div><strong>数据源提示</strong><div v-for="item in report.warnings" :key="item">{{ item }}</div></div></div>

    <section class="card">
      <div class="section-row"><div><p class="eyebrow">BATCH DOSSIER</p><h2 class="mono">{{ batch.batch_code }}</h2></div><span class="badge neutral">{{ report.scope }}</span></div>
      <dl class="info-grid">
        <div><dt>产品 / 品种</dt><dd>{{ batch.product }} / {{ batch.variety || '未录入' }}</dd></div>
        <div><dt>产地</dt><dd>{{ batch.origin || '未录入' }}</dd></div>
        <div><dt>供应商（内部）</dt><dd>{{ batch.supplier || '未录入' }}</dd></div>
        <div><dt>状态</dt><dd>{{ statusLabel(batch.status) }}</dd></div>
        <div><dt>登记时间</dt><dd>{{ formatTime(batch.created_at) }}</dd></div>
        <div><dt>报告生成时间</dt><dd>{{ formatTime(report.generated_at) }}</dd></div>
      </dl>
    </section>

    <div class="stats-grid">
      <article class="stat-card"><div class="stat-top"><span>AI 质检记录</span></div><strong>{{ report.inspection.count }}</strong><small>已保存的 inspection 记录</small></article>
      <article class="stat-card"><div class="stat-top"><span>已复核质检</span></div><strong>{{ report.inspection.reviewed_count }}</strong><small>至少存在一版人工复核</small></article>
      <article class="stat-card"><div class="stat-top"><span>加工记录</span></div><strong>{{ report.processing.count }}</strong><small>只统计已落库记录</small></article>
      <article class="stat-card trace-stat"><div class="stat-top"><span>冷链 / 告警</span></div><strong class="word-stat">{{ cold.reading_count || 0 }} / {{ report.coldchain.alert_count || 0 }}</strong><small>读数条数 / 告警条数</small></article>
    </div>

    <section class="card">
      <div class="section-row"><h2>AI 质检与人工复核</h2><span class="badge ready">{{ report.inspection.count }} 条</span></div>
      <EmptyState v-if="!report.inspection.records.length" title="暂无质检记录" description="没有保存的质检记录时，不生成质量结论。" icon="scan" />
      <div v-else class="table-wrap"><table><thead><tr><th>记录</th><th>模型</th><th>候选</th><th>复核</th><th>人工等级</th><th>时间</th><th class="no-print">证据</th></tr></thead><tbody>
        <tr v-for="item in report.inspection.records" :key="item.inspection_id">
          <td class="mono">#{{ item.inspection_id }}</td><td><span class="mono">{{ item.model_version || '未记录' }}</span></td><td>{{ item.candidate_count }}</td>
          <td><template v-if="item.latest_review">{{ item.latest_review.conclusion }}<span class="cell-sub">{{ item.latest_review.reviewer }} · 第 {{ item.review_revision }} 版</span></template><span v-else class="muted">待复核</span></td>
          <td>{{ item.final_grade || '未设置' }}</td><td>{{ formatTime(item.created_at) }}</td><td class="no-print"><a class="text-link" :href="item.artifact_urls.result" target="_blank" rel="noopener">结果图</a></td>
        </tr>
      </tbody></table></div>
    </section>

    <section class="card">
      <div class="section-row"><h2>加工品控记录</h2><span class="badge neutral">{{ report.processing.count }} 条</span></div>
      <EmptyState v-if="!report.processing.records.length" title="暂无加工记录" description="B 模块尚未写入数据时，这里保持为空，不生成建议或采用值。" icon="sliders" />
      <div v-else class="table-wrap"><table><thead><tr><th>记录</th><th>类型</th><th>输入</th><th>建议</th><th>采用值</th><th>操作员 / 时间</th></tr></thead><tbody>
        <tr v-for="item in report.processing.records" :key="item.record_id">
          <td class="mono">#{{ item.record_id }}</td><td>{{ item.advice_type || '未记录' }}</td><td class="mono break-word">{{ compactJson(item.inputs) }}</td><td class="mono break-word">{{ compactJson(item.advice) }}</td><td class="mono break-word">{{ compactJson(item.adopted_values) }}</td><td>{{ item.operator || '未记录' }}<span class="cell-sub">{{ formatTime(item.created_at) }}</span></td>
        </tr>
      </tbody></table></div>
    </section>

    <section class="card">
      <div class="section-row"><h2>冷链监测摘要</h2><span class="badge neutral">{{ cold.reading_count || 0 }} 条读数</span></div>
      <dl class="info-grid">
        <div><dt>时间范围</dt><dd>{{ formatTime(cold.first_time) }} → {{ formatTime(cold.last_time) }}</dd></div>
        <div><dt>温度范围</dt><dd>{{ value(cold.min_temperature, ' ℃') }} ～ {{ value(cold.max_temperature, ' ℃') }}</dd></div>
        <div><dt>平均温度</dt><dd>{{ value(cold.avg_temperature, ' ℃') }}</dd></div>
        <div><dt>湿度范围</dt><dd>{{ value(cold.min_humidity, '%') }} ～ {{ value(cold.max_humidity, '%') }}</dd></div>
        <div><dt>平均湿度</dt><dd>{{ value(cold.avg_humidity, '%') }}</dd></div>
        <div><dt>门开启记录</dt><dd>{{ cold.door_open_count || 0 }} 条</dd></div>
      </dl>
      <p v-if="report.coldchain.readings_truncated" class="footnote">读数较多，页面只返回最近 200 条明细；上方统计仍基于该批次全部已保存读数。</p>
    </section>

    <section class="card">
      <div class="section-row"><h2>冷链告警与处置</h2><span class="badge neutral">{{ report.coldchain.alert_count }} 条</span></div>
      <EmptyState v-if="!report.coldchain.alerts.length" title="暂无告警记录" description="没有告警记录只表示数据库中暂无记录，不自动解释为安全或合格。" icon="thermometer" />
      <div v-else class="table-wrap"><table><thead><tr><th>时间</th><th>级别</th><th>原因</th><th>触发值</th><th>状态</th><th>处置</th></tr></thead><tbody>
        <tr v-for="item in report.coldchain.alerts" :key="item.alert_id"><td>{{ formatTime(item.started_at) }}</td><td>{{ item.level || '未记录' }}</td><td>{{ item.reason || '未记录' }}</td><td>{{ item.trigger_value || '未记录' }}</td><td>{{ item.status || '未记录' }}</td><td>{{ item.resolution || '未记录' }}<span v-if="item.resolved_by" class="cell-sub">{{ item.resolved_by }} · {{ formatTime(item.resolved_at) }}</span></td></tr>
      </tbody></table></div>
    </section>

    <section class="card"><div class="section-row"><h2>全链路已保存事件</h2><span class="badge neutral">{{ report.events.length }} 条</span></div><EventTimeline :events="report.events" /></section>
  </template>
</template>
