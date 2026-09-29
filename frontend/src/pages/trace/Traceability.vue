<script setup>
import PageHeading from '../../components/PageHeading.vue'
import BatchWorkspace from '../../components/BatchWorkspace.vue'
import TraceWorkspace from './TraceWorkspace.vue'
</script>
<template><PageHeading eyebrow="证据记录 · 公开查询" title="溯源与报告" description="按公开批次编号查看可公开事件；管理端记录与公开端字段分开读取。" /><BatchWorkspace v-slot="{ batch }"><TraceWorkspace :key="batch.batch_id" :batch="batch" /></BatchWorkspace></template>
