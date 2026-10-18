<script setup>
import { useRoute } from 'vue-router'
import { getPublicTrace } from '../../api/trace.js'
import { useAsyncData } from '../../composables/useAsyncData.js'
import { formatTime } from '../../utils/batch.js'
import Icon from '../../components/Icon.vue'
import EventTimeline from '../../components/EventTimeline.vue'
import StateNotice from '../../components/StateNotice.vue'

const route = useRoute()
const { data, loading, error, reload } = useAsyncData(
  signal => getPublicTrace(route.params.batchCode, { signal }),
  () => route.params.batchCode,
)

const stages = [
  { key: 'inspection', title: '质检记录', note: '质检与人工复核' },
  { key: 'processing', title: '加工记录', note: '加工品控公开事件' },
  { key: 'coldchain', title: '冷链记录', note: '冷链与仓储公开事件' },
  { key: 'logistics', title: '流通记录', note: '包装、运输与出厂' },
]

function summary(key) {
  return data.value?.public_summary?.[key] || {
    record_count: 0,
    latest_event_time: null,
    state: 'no_public_record',
  }
}
</script>

<template>
  <div class="public-app">
    <header class="public-header">
      <span class="brand-mark"><Icon name="leaf" /></span>
      <div><strong>智检鲜达</strong><small>果蔬批次公开溯源</small></div>
      <span class="badge neutral">原型展示</span>
    </header>

    <main class="public-main">
      <StateNotice :loading="loading" :error="error" @retry="reload" />

      <template v-if="data">
        <section class="public-hero">
          <p class="eyebrow">BATCH TRACEABILITY</p>
          <h1>{{ data.product || '果蔬批次' }}</h1>
          <p class="mono">{{ data.batch_code }}</p>
          <div class="public-product">
            <div>
              <span>品种</span>
              <strong>{{ data.variety || '未录入' }}</strong>
            </div>
            <div>
              <span>产地</span>
              <strong>{{ data.origin || '未录入' }}</strong>
            </div>
          </div>
        </section>

        <section class="card">
          <div class="section-row">
            <div>
              <h2>公开摘要</h2>
              <p class="muted">仅统计已明确标记为公开的事件，不代表对应环节已经完成或通过。</p>
            </div>
            <span class="badge neutral">PUBLIC EVENTS ONLY</span>
          </div>

          <div class="public-summary-grid">
            <article
              v-for="stage in stages"
              :key="stage.key"
              class="public-summary-card"
            >
              <span class="public-summary-label">{{ stage.title }}</span>
              <strong v-if="summary(stage.key).record_count">
                {{ summary(stage.key).record_count }} 条公开记录
              </strong>
              <strong v-else>暂无公开记录</strong>
              <small>{{ stage.note }}</small>
              <time v-if="summary(stage.key).latest_event_time">
                最近记录：{{ formatTime(summary(stage.key).latest_event_time) }}
              </time>
            </article>
          </div>
        </section>

        <section class="card">
          <div class="section-row">
            <h2>公开记录</h2>
            <span class="badge ready">{{ (data.events || []).length }} 条已公开事件</span>
          </div>
          <EventTimeline :events="data.events || []" />
        </section>
      </template>

      <p class="public-disclaimer">
        仅展示已记录且明确允许公开的批次信息。暂无公开记录不代表对应环节未发生；
        已有公开记录也不代表食品安全、监管认证或整批质量合格。演示及模拟数据以事件来源标签为准。
      </p>
    </main>

    <footer class="public-footer">智检鲜达 · 每一条记录，都有来源</footer>
  </div>
</template>
