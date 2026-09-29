<script setup>
import { computed } from 'vue'
import { eventStages } from '../utils/batch.js'
const props = defineProps({ events: { type: Array, default: () => [] } })
const stages = computed(() => eventStages(props.events))
</script>
<template><div class="stage-list"><div v-for="(stage, i) in stages" :key="stage.label" class="stage" :class="{ recorded: stage.count > 0 }"><span class="stage-dot">{{ String(i + 1).padStart(2, '0') }}</span><strong>{{ stage.label }}</strong><small>{{ stage.state }}</small></div></div><p class="footnote">根据已保存的事件分类展示；“有相关记录”不等于该环节全部完成。</p></template>
