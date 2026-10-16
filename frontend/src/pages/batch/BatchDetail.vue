<script setup>
import { useRoute } from 'vue-router'
import BatchWorkbench from './BatchWorkbench.vue'
const route = useRoute()
</script>
<template><BatchWorkbench :key="route.params.id" :batch-id="route.params.id" /></template>
