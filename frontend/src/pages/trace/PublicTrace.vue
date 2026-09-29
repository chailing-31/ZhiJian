<script setup>
import { useRoute } from 'vue-router'
import { getPublicTrace } from '../../api/trace.js'
import { useAsyncData } from '../../composables/useAsyncData.js'
import Icon from '../../components/Icon.vue'
import EventTimeline from '../../components/EventTimeline.vue'
import StateNotice from '../../components/StateNotice.vue'
const route = useRoute()
const { data, loading, error, reload } = useAsyncData(signal => getPublicTrace(route.params.batchCode, { signal }), () => route.params.batchCode)
</script>
<template>
  <div class="public-app"><header class="public-header"><span class="brand-mark"><Icon name="leaf" /></span><div><strong>智检鲜达</strong><small>果蔬批次公开溯源</small></div><span class="badge neutral">原型展示</span></header><main class="public-main">
    <StateNotice :loading="loading" :error="error" @retry="reload" />
    <template v-if="data"><section class="public-hero"><p class="eyebrow">BATCH TRACEABILITY</p><h1>{{ data.product || '果蔬批次' }}</h1><p class="mono">{{ data.batch_code }}</p><div class="public-product"><div><span>品种</span><strong>{{ data.variety || '未录入' }}</strong></div><div><span>产地</span><strong>{{ data.origin || '未录入' }}</strong></div></div></section><section class="card"><div class="section-row"><h2>公开记录</h2><span class="badge ready">{{ (data.events || []).length }} 条已公开事件</span></div><EventTimeline :events="data.events || []" /></section></template>
    <p class="public-disclaimer">仅展示已记录且可公开的批次信息。未记录环节不代表已经完成；演示及模拟数据以事件来源标签为准。本页不是产品合格证明。</p>
  </main><footer class="public-footer">智检鲜达 · 每一条记录，都有来源</footer></div>
</template>
