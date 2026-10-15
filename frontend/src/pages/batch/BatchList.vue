<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listBatches, createBatch } from '../../api/batch.js'
import { useAsyncData } from '../../composables/useAsyncData.js'
import { searchBatches, formatTime, statusLabel } from '../../utils/batch.js'
import PageHeading from '../../components/PageHeading.vue'
import AppDialog from '../../components/AppDialog.vue'
import Icon from '../../components/Icon.vue'
import StateNotice from '../../components/StateNotice.vue'
import EmptyState from '../../components/EmptyState.vue'
const route = useRoute(), router = useRouter()
const { data, loading, error, reload } = useAsyncData(signal => listBatches({ signal }))
const query = ref(typeof route.query.q === 'string' ? route.query.q : ''), showForm = ref(route.query.new === '1'), submitting = ref(false), submitError = ref('')
const form = reactive({ batch_code: '', product: '苹果', variety: '', origin: '', supplier: '' })
const filtered = computed(() => searchBatches(data.value || [], query.value))
watch(() => route.query.q, value => { query.value = typeof value === 'string' ? value : '' })
watch(() => route.query.new, value => { if (value === '1') showForm.value = true })
function closeForm() {
  if (submitting.value) return
  showForm.value = false
  if (route.query.new) { const next = { ...route.query }; delete next.new; router.replace({ query: next }) }
}
async function submit() {
  if (submitting.value) return
  submitError.value = ''; submitting.value = true
  try {
    const input = Object.fromEntries(Object.entries(form).map(([key, val]) => [key, val.trim()]))
    if (!/^[A-Za-z0-9-]{1,64}$/.test(input.batch_code) || !input.product) throw new Error('请填写合法批次编号和产品名称。')
    const result = await createBatch(input)
    await router.push(`/batches/${result.batch_id}`)
  } catch (e) { submitError.value = e.message }
  finally { submitting.value = false }
}
</script>
<template>
  <PageHeading title="批次管理" description="登记、查找和继续处理业务批次。"><button class="btn secondary" :disabled="loading" @click="reload"><Icon name="refresh" />刷新</button><button class="btn" :aria-expanded="showForm" aria-controls="new-batch-dialog" @click="showForm = true"><Icon name="plus" />新建批次</button></PageHeading>
  <AppDialog id="new-batch-dialog" :open="showForm" :busy="submitting" title="新建批次" description="填写批次信息，保存后进入该批次工作台。" @close="closeForm">
    <form class="ui-dialog-body" @submit.prevent="submit">
      <fieldset :disabled="submitting"><div class="form-grid">
        <label>批次编号 <span class="required" aria-hidden="true">*</span><input v-model.trim="form.batch_code" autofocus required maxlength="64" pattern="[A-Za-z0-9-]{1,64}" placeholder="例如 APPLE-2026-002" aria-describedby="batch-code-help" /><small id="batch-code-help">英文、数字或连字符，最多 64 位</small></label>
        <label>产品名称 <span class="required" aria-hidden="true">*</span><input v-model.trim="form.product" required maxlength="50" /></label>
        <label>品种<input v-model.trim="form.variety" maxlength="50" placeholder="例如 红富士" /></label><label>产地<input v-model.trim="form.origin" maxlength="100" placeholder="例如 山东烟台" /></label><label class="full">供应商<input v-model.trim="form.supplier" maxlength="100" placeholder="填写供应商名称" /><small>仅用于内部记录</small></label>
      </div></fieldset>
      <p v-if="submitError" class="notice danger" role="alert">{{ submitError }}</p>
      <footer class="ui-dialog-footer"><button class="btn secondary" type="button" :disabled="submitting" @click="closeForm">取消</button><button class="btn" type="submit" :disabled="submitting">{{ submitting ? '正在保存…' : '保存并打开批次' }}</button></footer>
    </form>
  </AppDialog>
  <StateNotice :loading="loading" :error="error" @retry="reload" />
  <section v-if="data" class="card">
    <div class="ui-list-toolbar"><h2>批次台账 <small class="count">{{ data.length }}</small></h2><label class="search-field"><span class="sr-only">搜索批次</span><input v-model="query" type="search" placeholder="搜索编号、产品、产地或供应商" /></label></div>
    <p v-if="query" class="ui-list-result">找到 {{ filtered.length }} 个批次 <button class="q4-inline" @click="query = ''">清空搜索</button></p>
    <EmptyState v-if="!filtered.length" :title="data.length ? '没有匹配的批次' : '暂无批次'" :description="data.length ? '换个关键词，或清空搜索查看全部批次。' : '登记第一个批次，开始记录质检与后续作业。'" icon="box"><button v-if="!data.length" class="btn" @click="showForm = true">新建批次</button></EmptyState>
    <template v-else><div class="table-wrap ui-desktop-batches" tabindex="0" aria-label="批次台账，可横向滚动"><table class="ui-batch-table"><thead><tr><th scope="col">批次编号</th><th scope="col">产品 / 品种</th><th scope="col">产地</th><th scope="col">供应商</th><th scope="col">登记时间</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="b in filtered" :key="b.batch_id"><td><RouterLink class="ui-batch-name" :to="`/batches/${b.batch_id}`">{{ b.batch_code }}</RouterLink></td><td>{{ b.product }}<small class="cell-sub">{{ b.variety || '未填写' }}</small></td><td>{{ b.origin || '未填写' }}</td><td>{{ b.supplier || '未填写' }}</td><td class="muted">{{ formatTime(b.created_at) }}</td><td><span class="badge neutral">{{ statusLabel(b.status) }}</span></td><td><RouterLink class="text-link" :to="`/batches/${b.batch_id}`">打开工作台</RouterLink></td></tr></tbody></table></div>
      <div class="ui-mobile-batches"><article v-for="b in filtered" :key="b.batch_id"><header><RouterLink :to="`/batches/${b.batch_id}`">{{ b.batch_code }}</RouterLink><span class="badge neutral">{{ statusLabel(b.status) }}</span></header><p>{{ b.product }} · {{ b.variety || '品种未填写' }}</p><dl><div><dt>产地</dt><dd>{{ b.origin || '未填写' }}</dd></div><div><dt>供应商</dt><dd>{{ b.supplier || '未填写' }}</dd></div><div class="full"><dt>登记时间</dt><dd>{{ formatTime(b.created_at) }}</dd></div></dl><RouterLink class="text-link" :to="`/batches/${b.batch_id}`">打开工作台 <Icon name="arrow" /></RouterLink></article></div>
    </template>
  </section>
</template>
