<script setup>
import Icon from './Icon.vue'
defineProps({ title: { type: String, default: '暂无记录' }, description: { type: String, default: '' }, icon: { type: String, default: 'box' } })
</script>
<template><div class="empty-state"><Icon :name="icon" /><h3>{{ title }}</h3><p v-if="description">{{ description }}</p><slot /></div></template>
