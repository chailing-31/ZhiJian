<script setup>
defineProps({ loading: Boolean, error: { type: String, default: '' } })
defineEmits(['retry'])
</script>
<template><div v-if="loading" class="notice" role="status"><span class="spinner" />正在读取批次数据…</div><div v-else-if="error" class="notice danger" role="alert"><span>{{ error }}</span><button class="btn small secondary" @click="$emit('retry')">重试</button></div></template>
