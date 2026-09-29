<script setup>
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getBatch, listBatches } from '../api/batch.js'
import { useAsyncData } from '../composables/useAsyncData.js'
import { validId, statusLabel } from '../utils/batch.js'
import StateNotice from './StateNotice.vue'
import EmptyState from './EmptyState.vue'
const route = useRoute(), router = useRouter()
const list = useAsyncData(signal => listBatches({ signal }))
const selectedId = computed(() => validId(route.query.batch_id))
watch(() => [list.data.value, route.query.batch_id], ([items, query]) => {
  if (query !== undefined || !items?.length) return
  let saved = ''
  try { saved = sessionStorage.getItem('zhijian.activeBatch') || '' } catch { /* optional storage */ }
  const candidate = items.find(b => String(b.batch_id) === saved) || items[0]
  router.replace({ query: { ...route.query, batch_id: String(candidate.batch_id) } })
}, { immediate: true })
watch(selectedId, id => { if (id) { try { sessionStorage.setItem('zhijian.activeBatch', id) } catch { /* optional storage */ } } })
const detail = useAsyncData(signal => {
  if (route.query.batch_id === undefined) return null
  if (!selectedId.value) throw new Error('批次 ID 格式不正确，请在下拉框中重新选择。')
  return getBatch(selectedId.value, { signal })
}, () => route.query.batch_id)
function selectBatch(event) { router.replace({ query: { ...route.query, batch_id: event.target.value } }) }
</script>
<template>
  <section class="card batch-picker">
    <label for="active-batch"><span class="field-label">当前业务批次</span><select id="active-batch" :value="selectedId" :disabled="list.loading.value || !list.data.value?.length" @change="selectBatch"><option value="" disabled>请选择批次</option><option v-for="item in list.data.value || []" :key="item.batch_id" :value="String(item.batch_id)">{{ item.batch_code }} · {{ item.product }}</option></select></label>
    <div v-if="detail.data.value" class="batch-context"><span>{{ detail.data.value.variety || '品种未录入' }} · {{ detail.data.value.origin || '产地未录入' }}</span><span class="badge ready">{{ statusLabel(detail.data.value.status) }}</span><RouterLink class="text-link" :to="`/batches/${detail.data.value.batch_id}`">批次详情 →</RouterLink></div>
  </section>
  <StateNotice :loading="list.loading.value" :error="list.error.value" @retry="list.reload" />
  <EmptyState v-if="!list.loading.value && !list.error.value && list.data.value?.length === 0" title="请先创建业务批次" description="所有模块围绕数据库生成的 batch_id 关联。"><RouterLink class="btn" to="/batches">去创建批次</RouterLink></EmptyState>
  <StateNotice v-if="!list.error.value" :loading="detail.loading.value" :error="detail.error.value" @retry="detail.reload" />
  <slot v-if="detail.data.value && !detail.loading.value && !list.error.value" :batch="detail.data.value" :key="String(detail.data.value.batch_id)" />
</template>
