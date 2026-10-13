<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { getInspectionReady } from '../api/inspection.js'
import { getWorkspaceStatus, getBusinessStatus } from '../api/engineering.js'
const rows = ref([]), loading = ref(false), checked = ref('')
const abort = new AbortController()
async function refresh() {
  if (loading.value) return
  loading.value = true
  const result = await Promise.allSettled([getWorkspaceStatus({ signal: abort.signal }), getInspectionReady({ signal: abort.signal }), getBusinessStatus({ signal: abort.signal })])
  if (abort.signal.aborted) return
  const val = i => result[i].status === 'fulfilled' ? result[i].value : null
  const ws = val(0), ai = val(1), business = val(2)
  rows.value = [
    { name: '批次工作台 V4', ok: ws?.patch_version === 'batch-workbench-v4-20261013', text: ws?.patch_version || '未连接 / 未安装 V4 后端' },
    { name: '批次附加表', ok: ws?.batch_workbench_ready === true, text: ws?.batch_workbench_ready ? '增量表可用' : '需执行 Q4 增量迁移' },
    { name: '数据库', ok: ws?.database_ready === true, text: ws?.database_name || '未确认' },
    { name: '视觉模型', ok: ai?.model_ready === true, text: ai?.message || '检查 8001 与后端 inspection profile' },
    { name: '业务规则', ok: business?.ready === true, text: business?.ready ? 'B 规则服务连接可用' : '检查 8002 或业务数据表' },
  ]
  checked.value = new Date().toLocaleTimeString('zh-CN'); loading.value = false
}
onMounted(refresh)
onBeforeUnmount(() => abort.abort())
</script>
<template>
  <section class="e-services" aria-label="服务连接检查">
    <span class="e-services-label">连接检查</span>
    <span v-for="row in rows" :key="row.name" class="e-service" :class="row.ok ? 'ok' : 'fail'" :title="row.text"><i />{{ row.name }} {{ row.ok ? '可用' : '待检查' }}</span>
    <span v-if="!rows.length" class="muted">{{ loading ? '正在检测…' : '尚未检查' }}</span>
    <button class="e-text-button" :disabled="loading" @click="refresh">{{ loading ? '检查中…' : '重新检查' }}</button>
    <small v-if="checked">{{ checked }} · 连接可用不等于流程已验收</small>
  </section>
</template>
