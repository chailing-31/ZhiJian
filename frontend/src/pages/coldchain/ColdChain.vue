<script setup>
import PageHeading from '../../components/PageHeading.vue'
import BatchWorkspace from '../../components/BatchWorkspace.vue'
import ColdChainWorkspace from './ColdChainWorkspace.vue'
</script>
<template><PageHeading eyebrow="冷链监测 · 异常处置" title="冷链监测" description="按批次关联温湿度数据与告警处置，模拟数据与真实采集数据必须分开标识。" /><BatchWorkspace v-slot="{ batch }"><ColdChainWorkspace :key="batch.batch_id" :batch="batch" /></BatchWorkspace></template>
