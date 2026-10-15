<script setup>
import { useAsyncData } from '../composables/useAsyncData.js'
import { formatTime } from '../utils/batch.js'
import { publicSummary } from '../api/batch-workbench.js'
const props=defineProps({batchCode:{type:String,required:true},refreshKey:{type:String,default:''}})
const { data, loading, error, reload } = useAsyncData(signal => publicSummary(props.batchCode, { signal }), () => `${props.batchCode}:${props.refreshKey}`)
</script>
<template>
  <section class="card">
    <div class="section-row"><h2>已发布的批次品控摘要</h2><button class="btn secondary" :disabled="loading" @click="reload">刷新公开摘要</button></div>
    <p v-if="loading" role="status">正在读取公开摘要…</p>
    <p v-else-if="error" class="notice danger" role="alert">公开摘要读取失败。{{ error }} 请点击刷新重试。</p>
    <p v-else-if="data && !data.published">当前批次尚未发布品控摘要。</p>
    <template v-else-if="data?.published">
      <p>{{ data.snapshot.quality_summary }}</p><p v-if="data.snapshot.grade_source">等级来源：{{ data.snapshot.grade_source }}</p><p>{{ data.snapshot.evidence_scope }}</p>
      <p v-if="data.snapshot.grade"><strong>品控等级：</strong>{{ data.snapshot.grade }}</p><p v-if="data.snapshot.intended_use"><strong>拟定用途：</strong>{{ data.snapshot.intended_use }}</p>
      <p><strong>加工记录：</strong>{{ data.snapshot.processing_summary }}</p><p>{{ data.snapshot.coldchain_summary }}</p>
      <p v-for="s in (data.snapshot.coldchain_sources || []).filter(s => s.reading_count > 0)" :key="s.source">{{ s.source === 'sensor' ? '传感器来源' : '模拟数据' }}：{{ s.reading_count }} 条</p>
      <p class="footnote">发布时间：{{ formatTime(data.published_at) }} · 评定第 {{ data.snapshot.assessment_revision }} 版</p>
      <p v-if="!data.current" class="notice warning">这是已批准的历史快照，后续记录已经变化，尚未重新发布。</p>
      <p v-else class="notice success">公开摘要与当前记录同步。</p><p class="footnote">{{ data.snapshot.scope_note }}</p>
    </template>
  </section>
</template>
