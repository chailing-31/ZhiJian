<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { getFormalReport } from '../../api/report.js'
import { useAsyncData } from '../../composables/useAsyncData.js'
import { formatTime, statusLabel } from '../../utils/batch.js'
import { reportStatusFor } from '../../utils/presentation.js'
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
const workbench = computed(() => report.value?.batch_workbench)
const reportStatus = computed(() => reportStatusFor(workbench.value))
const dispositionPath = computed(() => ['C', 'REJECT'].includes(workbench.value?.assessment?.final_grade))
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
  <PageHeading eyebrow="全程品控记录" title="批次品控报告" description="查看本批次已保存的品控记录。">
    <RouterLink class="btn secondary no-print" :to="`/batches/${route.params.id}`">返回批次</RouterLink>
    <button class="btn no-print" :disabled="!report" @click="print">打印 / 另存为 PDF</button>
  </PageHeading>
  <nav v-if="report" class="ui-report-index no-print" aria-label="报告目录"><a href="#report-inspection">质检与复核</a><a href="#report-processing">加工记录</a><a href="#report-coldchain">冷链摘要</a><a href="#report-alerts">告警处置</a><a href="#report-events">业务事件</a></nav>
  <StateNotice :loading="loading" :error="error" @retry="reload" />

  <template v-if="report && batch">
    <section v-if="workbench" class="card q4-report-section report-overview" aria-labelledby="report-overview-title">
      <div class="report-overview-header">
        <div>
          <p class="report-label">本批次品控摘要</p>
          <h2 id="report-overview-title">{{ workbench.headline }}</h2>
          <p class="report-use"><span>拟定用途</span>{{ workbench.use_label }}</p>
        </div>
        <aside class="report-next" :class="{ 'report-complete': reportStatus.complete }">
          <span class="report-label">{{ reportStatus.label }}</span>
          <strong>{{ reportStatus.title }}</strong>
          <p v-if="reportStatus.description" class="report-status-description">{{ reportStatus.description }}</p>
          <a v-if="workbench.state === 'DISPOSITION_RECORDED' && reportStatus.complete" class="text-link no-print" href="#report-operations">查看品质处置记录 →</a>
          <RouterLink v-else-if="!reportStatus.complete" class="text-link no-print" :to="{ path: `/batches/${route.params.id}`, query: { tab: workbench.next_action?.tab || 'overview' } }">前往处理 →</RouterLink>
        </aside>
      </div>

      <dl v-if="workbench.evidence" class="report-evidence">
        <div><dt>已检测外观照片</dt><dd>{{ workbench.evidence.unique_photo_count ?? '—' }}<span>张</span></dd></div>
        <div><dt>人工检查</dt><dd>{{ workbench.evidence.reviewed_count ?? '—' }}<span>条</span></dd></div>
        <div><dt>明确标记待复查</dt><dd>{{ workbench.evidence.pending_count ?? '—' }}<span>条</span></dd></div>
      </dl>
      <p v-else class="report-evidence-fallback">{{ workbench.evidence_summary }}</p>

      <div class="report-detail-grid">
        <div class="report-assessment">
          <h3>评定依据</h3>
          <template v-if="workbench.assessment">
            <div class="report-assessment-meta">
              <strong>评定 #{{ workbench.assessment.id }}</strong>
              <span>第 {{ workbench.assessment.revision }} 版</span>
              <span>{{ workbench.assessment.approved_by }}</span>
            </div>
            <p class="report-basis">{{ workbench.assessment.grade_basis }}</p>
          </template>
          <p v-else class="report-basis">暂无评定记录</p>
        </div>
        <div class="report-execution">
          <h3>执行记录</h3>
          <dl class="report-records">
            <div v-if="dispositionPath || workbench.quality_disposition_recorded"><dt>品质处置</dt><dd :class="{ 'is-recorded': workbench.quality_disposition_recorded }">{{ workbench.quality_disposition_recorded ? '已登记，见下方处置明细' : '待登记' }}</dd></div>
            <div><dt>实际分选</dt><dd :class="{ 'is-recorded': workbench.sorting_recorded }">{{ workbench.sorting_recorded ? '已保存' : dispositionPath ? '当前处置流程不要求' : '尚无当前记录' }}</dd></div>
            <div><dt>实际加工</dt><dd :class="{ 'is-recorded': workbench.processing_executed }">{{ workbench.processing_executed ? '已登记' : dispositionPath ? '当前处置流程不要求' : '尚无当前记录' }}</dd></div>
            <div v-if="workbench.processing.record_id"><dt>工艺记录 #{{ workbench.processing.record_id }}</dt><dd>{{ workbench.processing.current ? '关联当前批次评定' : '来源已过期或未关联' }}</dd></div>
          </dl>
        </div>
      </div>
      <p class="report-scope"><span>说明</span>{{ workbench.scope_note }}</p>
    </section>
    <div class="notice warning">{{ report.disclaimer }}</div>
    <div v-if="report.warnings?.length" class="notice danger"><div><strong>数据源提示</strong><div v-for="item in report.warnings" :key="item">{{ item }}</div></div></div>

    <section class="card">
      <div class="section-row"><div><p class="eyebrow">批次信息</p><h2 class="mono">{{ batch.batch_code }}</h2></div><span class="badge neutral">内部品控记录</span></div>
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
      <article class="stat-card"><div class="stat-top"><span>AI 质检记录</span></div><strong>{{ report.inspection.count }}</strong><small>已保存的照片检测</small></article>
      <article class="stat-card"><div class="stat-top"><span>已复核质检</span></div><strong>{{ report.inspection.reviewed_count }}</strong><small>至少存在一版人工复核</small></article>
      <article class="stat-card"><div class="stat-top"><span>加工记录</span></div><strong>{{ report.processing.count }}</strong><small>已保存的工艺建议</small></article>
      <article class="stat-card trace-stat"><div class="stat-top"><span>冷链 / 告警</span></div><strong class="word-stat">{{ cold.reading_count || 0 }} / {{ report.coldchain.alert_count || 0 }}</strong><small>读数条数 / 告警条数</small></article>
    </div>

    <section id="report-inspection" class="card ui-report-section">
      <div class="section-row"><h2>AI 质检与人工复核</h2><span class="badge ready">{{ report.inspection.count }} 条</span></div>
      <EmptyState v-if="!report.inspection.records.length" title="暂无质检记录" description="没有保存的质检记录时，不生成质量结论。" icon="scan" />
      <div v-else class="table-wrap"><table><thead><tr><th>记录</th><th>模型</th><th>候选</th><th>复核</th><th>有效图像等级</th><th>时间</th><th class="no-print">证据</th></tr></thead><tbody>
        <tr v-for="item in report.inspection.records" :key="item.inspection_id">
          <td class="mono">#{{ item.inspection_id }}</td><td><span class="mono">{{ item.model_version || '未记录' }}</span></td><td>{{ item.candidate_count }}</td>
          <td><template v-if="item.latest_review">{{ item.latest_review.conclusion }}<span class="cell-sub">{{ item.latest_review.reviewer }} · 第 {{ item.review_revision }} 版</span></template><span v-else class="muted">人工检查可选</span></td>
          <td>{{ item.final_grade || item.suggested_grade || '历史未记录' }}</td><td>{{ formatTime(item.created_at) }}</td><td class="no-print"><a class="text-link" :href="item.artifact_urls.result" target="_blank" rel="noopener">结果图</a></td>
        </tr>
      </tbody></table></div>
    </section>

    <section id="report-processing" class="card ui-report-section">
      <div class="section-row"><h2>加工品控记录</h2><span class="badge neutral">{{ report.processing.count }} 条</span></div>
      <EmptyState v-if="!report.processing.records.length" title="暂无加工记录" description="生成加工建议后，输入条件、建议与采用值会记录在这里。" icon="sliders" />
      <div v-else class="table-wrap"><table><thead><tr><th class="no-print">追溯</th><th>记录</th><th>类型</th><th>输入</th><th>建议</th><th>采用值</th><th>操作员 / 时间</th></tr></thead><tbody>
        <tr v-for="item in report.processing.records" :key="item.record_id">
          <td class="no-print"><RouterLink class="text-link" :to="{ path: `/batches/${report.batch.batch_id}`, query: { tab: 'processing', processing_record: item.record_id } }">查看加工来源</RouterLink></td>
          <td class="mono">#{{ item.record_id }}</td><td>{{ item.advice_type || '未记录' }}</td><td>等级 {{ item.inputs?.grade || '未记录' }}<span class="cell-sub">环境 {{ value(item.inputs?.temperature, ' ℃') }} / {{ value(item.inputs?.humidity, '%') }}</span><span class="cell-sub">等级第 {{ item.inputs?.grade_source?.revision || '—' }} 版</span></td><td>{{ item.advice?.advice?.action || item.advice?.reason || '未记录' }}<span class="cell-sub">预冷 {{ item.advice?.advice?.pre_cooling_time || '不提供' }} · 清洗 {{ item.advice?.advice?.washing_pressure || '不提供' }}</span></td><td>{{ item.adopted_values?.pre_cooling_time || '尚未采用' }}<span class="cell-sub">{{ item.adopted_values?.washing_pressure || '' }}</span></td><td>{{ item.operator || '未记录' }}<span class="cell-sub">{{ formatTime(item.created_at) }}</span></td>
        </tr>
      </tbody></table></div>
    </section>

    <section v-if="report.batch_workbench" id="report-operations" class="card ui-report-section"><h2>实际作业与品质处置</h2><div class="table-wrap"><table><thead><tr><th>类型</th><th>时间与人员</th><th>结果</th><th>等级版本</th><th>状态</th></tr></thead><tbody><tr v-for="o in report.batch_workbench.operations" :key="o.id"><td>{{ ({sorting_completed:'实际分选',processing_completed:'实际加工',quality_disposition:'品质处置',reject_disposition:'拒收处置'})[o.kind] }}</td><td>{{ o.performed_at }}<span class="cell-sub">{{ o.operator }}</span></td><td>{{ o.note }}</td><td>#{{ o.assessment_id }}</td><td>{{ o.current ? '当前记录' : '历史记录' }}</td></tr><tr v-if="!report.batch_workbench.operations.length"><td colspan="5">暂无实际作业记录</td></tr></tbody></table></div></section>

    <section id="report-coldchain" class="card ui-report-section">
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

    <section id="report-alerts" class="card ui-report-section">
      <div class="section-row"><h2>冷链告警与处置</h2><span class="badge neutral">{{ report.coldchain.alert_count }} 条</span></div>
      <EmptyState v-if="!report.coldchain.alerts.length" title="暂无告警记录" description="没有告警记录只表示数据库中暂无记录，不自动解释为安全或合格。" icon="thermometer" />
      <div v-else class="table-wrap"><table><thead><tr><th>时间</th><th>级别</th><th>原因</th><th>触发值</th><th>状态</th><th>处置</th></tr></thead><tbody>
        <tr v-for="item in report.coldchain.alerts" :key="item.alert_id"><td>{{ formatTime(item.started_at) }}</td><td>{{ item.level || '未记录' }}</td><td>{{ item.reason || '未记录' }}</td><td>{{ item.trigger_value || '未记录' }}</td><td>{{ item.status || '未记录' }}</td><td>{{ item.resolution || '未记录' }}<span v-if="item.resolved_by" class="cell-sub">{{ item.resolved_by }} · {{ formatTime(item.resolved_at) }}</span></td></tr>
      </tbody></table></div>
    </section>

    <section id="report-events" class="card ui-report-section"><div class="section-row"><h2>全链路已保存事件</h2><span class="badge neutral">{{ report.events.length }} 条</span></div><EventTimeline :events="report.events" /></section>
  </template>
</template>

<style scoped>
.report-overview { overflow-wrap: anywhere; }
.report-overview-header { display: grid; grid-template-columns: minmax(0, 1fr) minmax(220px, .46fr); gap: 28px; align-items: start; }
.report-overview .report-label { display: block; margin: 0 0 8px; color: #687b70; font-size: 12px; font-weight: 500; }
.report-overview h2 { margin: 0; font-size: 21px; line-height: 1.5; }
.report-use { display: flex; flex-wrap: wrap; gap: 6px 12px; margin: 12px 0 0; font-size: 13px; }
.report-use > span { color: #6b7a73; }
.report-next { padding: 14px 18px; border-left: 3px solid #d6a44b; border-radius: 8px; background: #fff9ed; }
.report-next strong { display: block; color: #6f542b; font-size: 15px; line-height: 1.6; }
.report-next.report-complete { background: #f0f8f2; border-left-color: #339664; }
.report-next.report-complete strong { color: #246d46; }
.report-status-description { margin: 8px 0 0; color: #65766c; font-size: 12px; line-height: 1.8; }
.report-next .text-link { display: inline-flex; align-items: center; min-height: 32px; margin-top: 6px; }
.report-evidence { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0; margin: 22px 0; padding: 16px 0; border: 1px solid #e2ebe3; border-radius: 10px; background: #f6f9f4; }
.report-evidence > div { padding: 0 22px; }
.report-evidence > div + div { border-left: 1px solid #dfe7de; }
.report-evidence dt { color: #66786e; font-size: 12px; }
.report-evidence dd { margin: 5px 0 0; font-size: 25px; font-weight: 650; line-height: 1.3; font-variant-numeric: tabular-nums; }
.report-evidence dd span { margin-left: 7px; color: #6b7a73; font-size: 12px; font-weight: 400; }
.report-evidence-fallback { margin: 20px 0; color: #66786e; }
.report-detail-grid { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr); gap: 28px; }
.report-overview h3 { margin: 0 0 12px; font-size: 14px; font-weight: 650; }
.report-assessment-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; font-size: 12px; color: #6b7a73; }
.report-assessment-meta strong { color: #34483d; font-weight: 600; }
.report-assessment-meta > span { padding: 2px 8px; background: #f1f5f1; border-radius: 5px; }
.report-basis { margin: 12px 0 0; color: #5c6d62; font-size: 13px; line-height: 1.85; }
.report-execution { padding-left: 28px; border-left: 1px solid #e4ebe3; }
.report-records { margin: 0; font-size: 13px; }
.report-records > div { display: flex; justify-content: space-between; align-items: baseline; gap: 10px; padding: 8px 0; border-bottom: 1px solid #edf1eb; }
.report-records > div:first-child { padding-top: 0; }
.report-records > div:last-child { border-bottom: 0; padding-bottom: 0; }
.report-records dt { color: #65766c; }
.report-records dd { margin: 0; text-align: right; color: #738076; }
.report-records dd.is-recorded { color: #19754a; font-weight: 600; }
.report-scope { display: flex; align-items: baseline; gap: 10px; margin: 20px 0 0; padding-top: 14px; border-top: 1px solid #e4ebe3; color: #738076; font-size: 12px; line-height: 1.8; }
.report-scope > span { flex-shrink: 0; font-weight: 600; }
@media (max-width: 800px) {
  .report-overview-header, .report-detail-grid { grid-template-columns: 1fr; gap: 20px; }
  .report-execution { padding: 18px 0 0; border-left: 0; border-top: 1px solid #e4ebe3; }
  .report-evidence > div { padding: 0 10px; }
  .report-overview h2 { font-size: 19px; }
}
@media print {
  .report-overview-header { grid-template-columns: minmax(0, 1fr) minmax(180px, .46fr); gap: 18px; }
  .report-detail-grid { grid-template-columns: 1.15fr 1fr; gap: 18px; }
  .report-execution { padding: 0 0 0 18px; border-top: 0; border-left: 1px solid #ddd; }
  .report-next, .report-evidence { background: #fff; border-color: #ccc; }
  .report-overview-header, .report-evidence, .report-detail-grid { break-inside: avoid; }
}
</style>
