<script setup>
import PageHeading from '../../components/PageHeading.vue'
import BatchWorkspace from '../../components/BatchWorkspace.vue'
import InspectionWorkspace from './InspectionWorkspace.vue'
</script>
<template><PageHeading eyebrow="入厂分级 · 人工复核" title="AI 视觉质检" description="模型原始结果与人工最终结论分别保存；当前仅提供本地图片预览。" /><BatchWorkspace v-slot="{ batch }"><InspectionWorkspace :key="batch.batch_id" :batch="batch" /></BatchWorkspace></template>
