<script setup>
import { useRoute } from 'vue-router'
import { getBatch } from '../../api/batch.js'
import { useAsyncData } from '../../composables/useAsyncData.js'
import { formatTime, statusLabel } from '../../utils/batch.js'
import PageHeading from '../../components/PageHeading.vue'
import StateNotice from '../../components/StateNotice.vue'
import EventTimeline from '../../components/EventTimeline.vue'
const route = useRoute()
const { data: batch, loading, error, reload } = useAsyncData(signal => getBatch(route.params.id, { signal }), () => route.params.id)
function print() { window.print() }
</script>
<template>
  <PageHeading eyebrow="管理端 · 记录预览" title="批次记录预览" description="由现有批次详情与事件生成，不代表完整的质量检测报告。"><RouterLink class="btn secondary no-print" :to="`/batches/${route.params.id}`">返回批次</RouterLink><button class="btn no-print" :disabled="!batch" @click="print">打印 / 另存为 PDF</button></PageHeading>
  <StateNotice :loading="loading" :error="error" @retry="reload" />
  <template v-if="batch"><div class="notice warning">内部开发演示记录 · 非正式质检报告。包含内部供应商字段，不应作为公开报告分发。</div><section class="card"><h2 class="mono">{{ batch.batch_code }}</h2><dl class="info-grid"><div><dt>产品 / 品种</dt><dd>{{ batch.product }} / {{ batch.variety || '未录入' }}</dd></div><div><dt>产地</dt><dd>{{ batch.origin || '未录入' }}</dd></div><div><dt>供应商（内部）</dt><dd>{{ batch.supplier || '未录入' }}</dd></div><div><dt>状态</dt><dd>{{ statusLabel(batch.status) }}</dd></div><div><dt>登记时间</dt><dd>{{ formatTime(batch.created_at) }}</dd></div></dl></section><section class="card"><h2>已保存事件</h2><EventTimeline :events="batch.events || []" /></section><section class="card"><h2>完整报告待接入内容</h2><div class="table-wrap"><table><thead><tr><th>内容</th><th>当前状态</th></tr></thead><tbody><tr v-for="label in ['模型原始结果与证据图', '人工复核与改判记录', '加工建议与采用参数', '冷链监测与告警处置']" :key="label"><td>{{ label }}</td><td>专用数据接口尚未接入本报告预览</td></tr></tbody></table></div></section></template>
</template>
