<script setup>
import { formatTime, sourceLabel } from '../utils/batch.js'
import EmptyState from './EmptyState.vue'
defineProps({ events: { type: Array, default: () => [] } })
</script>
<template>
  <EmptyState v-if="!events.length" title="暂无事件记录" description="只展示已经保存的事件，不自动补全质检、加工或出厂环节。" icon="clock" />
  <ol v-else class="timeline">
    <li v-for="(event, index) in events" :key="`${event.event_time}-${index}`">
      <div class="timeline-dot" /><div class="timeline-content"><div class="section-row"><h3>{{ event.event_type || '未命名事件' }}</h3><span class="badge neutral">{{ sourceLabel(event.source) }}</span></div><p>{{ event.summary || '暂无摘要' }}</p><time>{{ formatTime(event.event_time) }}</time></div>
    </li>
  </ol>
</template>
