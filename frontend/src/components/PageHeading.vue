<script setup>
defineProps({ eyebrow: { type: String, default: '批次品控平台' }, title: { type: String, required: true }, description: { type: String, default: '' } })
</script>
<template><div class="page-heading"><div><p class="eyebrow">{{ eyebrow }}</p><h1>{{ title }}</h1><p v-if="description" class="lead">{{ description }}</p></div><div class="heading-actions"><slot /></div></div></template>
