<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listBatches } from '../api/batch.js'
import { useAsyncData } from '../composables/useAsyncData.js'
import { validId } from '../utils/batch.js'
import StateNotice from './StateNotice.vue'
const props = defineProps({ tab: { type: String, required: true }, title: { type: String, required: true } })
const route = useRoute(), router = useRouter(), selected = ref('')
const list = useAsyncData(signal => listBatches({ signal }))
function open(id) { if (validId(id)) router.replace({ path: `/batches/${validId(id)}`, query: { tab: props.tab } }) }
watch(() => route.query.batch_id, value => open(value), { immediate: true })
</script>
<template><section class="card zj-module-entry"><p class="eyebrow">批次业务</p><h1>{{ title }}</h1><p>选择一个批次，在统一工作台中继续本次业务。</p><StateNotice :loading="list.loading.value" :error="list.error.value" @retry="list.reload" /><label>业务批次<select v-model="selected"><option value="">请选择</option><option v-for="b in list.data.value || []" :key="b.batch_id" :value="String(b.batch_id)">{{ b.batch_code }} · {{ b.product }}</option></select></label><div class="button-row"><button class="btn" :disabled="!selected" @click="open(selected)">打开批次工作台 →</button><RouterLink class="btn secondary" to="/batches">登记新批次</RouterLink></div></section></template>
