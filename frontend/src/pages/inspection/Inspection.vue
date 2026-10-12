<script setup>
import PageHeading from '../../components/PageHeading.vue'
import BatchWorkspace from '../../components/BatchWorkspace.vue'
import InspectionWorkspace from './InspectionWorkspace.vue'
</script>
<template><PageHeading eyebrow="单图检测 · 人工复核" title="AI 视觉质检" description="上传后由业务后端调用本地模型；候选框与人工复核分别保存。内部联调，不代表食品安全或批次合格认证。" /><BatchWorkspace v-slot="{ batch }"><InspectionWorkspace :key="batch.batch_id" :batch="batch" /></BatchWorkspace></template>
