<script setup>
import { gradeText } from '../utils/presentation.js'
defineProps({ grade: { type: String, default: '' }, source: { type: String, default: '' }, label: { type: String, default: '' }, compact: Boolean })
</script>
<template>
  <span class="ui-grade" :class="{ compact }" :data-grade="grade" :aria-label="`${gradeText(grade)}${source ? ' · ' + source : ''}`">
    <span class="ui-grade-value">{{ gradeText(grade) }}</span>
    <span v-if="source || label" class="ui-grade-meta"><strong v-if="label">{{ label }}</strong><small v-if="source">{{ source }}</small></span>
  </span>
</template>
