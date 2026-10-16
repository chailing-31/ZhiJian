<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listBatches } from '../api/batch.js'
import { useAsyncData } from '../composables/useAsyncData.js'
import { searchBatches, validId } from '../utils/batch.js'
import PageHeading from './PageHeading.vue'
import StateNotice from './StateNotice.vue'
import EmptyState from './EmptyState.vue'
import Icon from './Icon.vue'
const props = defineProps({ tab: { type: String, required: true }, title: { type: String, required: true } })
const route = useRoute(), router = useRouter(), query = ref('')
const list = useAsyncData(signal => listBatches({ signal }))
const filtered = computed(() => searchBatches(list.data.value || [], query.value))
function open(id) { if (validId(id)) router.replace({ path: `/batches/${validId(id)}`, query: { tab: props.tab } }) }
watch(() => route.query.batch_id, value => open(value), { immediate: true })
</script>
<template>
  <PageHeading :title="title" description="选择要处理的批次。"><RouterLink class="btn secondary" :to="{ path: '/batches', query: { new: '1' } }"><Icon name="plus" />新建批次</RouterLink></PageHeading>
  <section class="card">
    <div class="ui-list-toolbar"><h2>选择批次 <span class="count">{{ list.data.value?.length ?? '—' }}</span></h2><label class="search-field"><span class="sr-only">搜索业务批次</span><input v-model="query" type="search" placeholder="搜索批次编号、品种或产地" /></label></div>
    <StateNotice :loading="list.loading.value" :error="list.error.value" @retry="list.reload" />
    <div v-if="!list.error.value && filtered.length" class="ui-module-batch-list"><article v-for="b in filtered" :key="b.batch_id" class="ui-module-batch"><div><strong>{{ b.batch_code }}</strong><p>{{ b.product }} · {{ b.variety || '品种未填写' }}</p></div><p>{{ b.origin || '产地未填写' }}</p><button class="q4-inline text-link" @click="open(b.batch_id)">打开 <Icon name="arrow" /></button></article></div>
    <EmptyState v-else-if="!list.loading.value && !list.error.value" :title="list.data.value?.length ? '没有匹配的批次' : '暂无批次'" :description="list.data.value?.length ? '换个关键词再试试。' : '登记批次后，即可开始本项业务。'" icon="box"><button v-if="query" class="btn secondary" @click="query = ''">清空搜索</button><RouterLink v-else class="btn" :to="{ path: '/batches', query: { new: '1' } }">新建批次</RouterLink></EmptyState>
  </section>
</template>
