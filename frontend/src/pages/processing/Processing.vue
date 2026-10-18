<script setup>
import PageHeading from '../../components/PageHeading.vue'
import BatchWorkspace from '../../components/BatchWorkspace.vue'
import ProcessingWorkspace from './ProcessingWorkspace.vue'
</script>
<template><PageHeading eyebrow="加工品控 · 规则建议" title="加工品控" description="展示工艺输入、建议依据与最终采用值；原型阶段不声称自动优化或控制产线。" /><BatchWorkspace v-slot="{ batch }"><ProcessingWorkspace :key="batch.batch_id" :batch="batch" /></BatchWorkspace></template>
