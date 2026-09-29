<script setup>
import { useRoute } from 'vue-router'
import { getBatch } from '../../api/batch.js'
import { useAsyncData } from '../../composables/useAsyncData.js'
import { formatTime, moduleLocation, statusLabel } from '../../utils/batch.js'
import PageHeading from '../../components/PageHeading.vue'
import StateNotice from '../../components/StateNotice.vue'
import StageTimeline from '../../components/StageTimeline.vue'
import EventTimeline from '../../components/EventTimeline.vue'
const route = useRoute()
const { data: batch, loading, error, reload } = useAsyncData(signal => getBatch(route.params.id, { signal }), () => route.params.id)
</script>
<template>
  <PageHeading eyebrow="业务中枢 · 同一批次" :title="batch?.batch_code || '批次详情'" description="在同一个批次下进入各模块，避免结果与记录关联错位。"><RouterLink class="btn secondary" to="/batches">返回列表</RouterLink></PageHeading>
  <StateNotice :loading="loading" :error="error" @retry="reload" />
  <template v-if="batch">
    <section class="card"><div class="section-row"><h2>批次基础信息</h2><span class="badge ready">{{ statusLabel(batch.status) }}</span></div><dl class="info-grid"><div><dt>产品 / 品种</dt><dd>{{ batch.product }} / {{ batch.variety || '未录入' }}</dd></div><div><dt>产地</dt><dd>{{ batch.origin || '未录入' }}</dd></div><div><dt>供应商（内部）</dt><dd>{{ batch.supplier || '未录入' }}</dd></div><div><dt>登记时间</dt><dd>{{ formatTime(batch.created_at) }}</dd></div><div><dt>内部批次 ID</dt><dd class="mono">{{ batch.batch_id }}</dd></div><div><dt>公开批次编号</dt><dd class="mono">{{ batch.batch_code }}</dd></div></dl></section>
    <section class="card"><h2>流程记录覆盖</h2><StageTimeline :events="batch.events || []" /></section>
    <section class="card"><h2>进入批次工作区</h2><div class="action-grid"><RouterLink class="action-tile" :to="moduleLocation('/inspection', batch.batch_id)"><strong>AI 质检</strong><small>图片、检测、复核 · 待接入</small></RouterLink><RouterLink class="action-tile" :to="moduleLocation('/processing', batch.batch_id)"><strong>加工品控</strong><small>规则建议与采用值 · 待接入</small></RouterLink><RouterLink class="action-tile" :to="moduleLocation('/coldchain', batch.batch_id)"><strong>冷链监测</strong><small>数据、告警与处置 · 待接入</small></RouterLink><RouterLink class="action-tile" :to="moduleLocation('/traceability', batch.batch_id)"><strong>溯源与报告</strong><small>公开时间线 / 二维码 / 记录预览</small></RouterLink></div></section>
    <section class="card"><div class="section-row"><h2>已保存的批次事件</h2><button class="btn small secondary" @click="reload">刷新记录</button></div><EventTimeline :events="batch.events || []" /></section>
  </template>
</template>
