<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listBatches, createBatch } from '../../api/batch.js'
import { useAsyncData } from '../../composables/useAsyncData.js'
import { searchBatches, formatTime, statusLabel } from '../../utils/batch.js'
import PageHeading from '../../components/PageHeading.vue'
import Icon from '../../components/Icon.vue'
import StateNotice from '../../components/StateNotice.vue'
import EmptyState from '../../components/EmptyState.vue'
const router = useRouter()
const { data, loading, error, reload } = useAsyncData(signal => listBatches({ signal }))
const query = ref(''), showForm = ref(false), submitting = ref(false), submitError = ref('')
const form = reactive({ batch_code: '', product: '苹果', variety: '', origin: '', supplier: '' })
const filtered = computed(() => searchBatches(data.value || [], query.value))
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
  <PageHeading eyebrow="原料入厂 · 批次台账" title="批次管理" description="批次编号用于公开查询，内部 batch_id 由数据库生成。"><button class="btn secondary" :disabled="loading" @click="reload"><Icon name="refresh" />刷新</button><button class="btn" :aria-expanded="showForm" @click="showForm = !showForm"><Icon name="plus" />{{ showForm ? '收起表单' : '新建批次' }}</button></PageHeading>
  <section v-if="showForm" class="card"><h2>新建业务批次</h2><p class="muted">这里只登记批次信息，不自动生成后续质检、加工或出厂记录。</p><form class="form-grid" @submit.prevent="submit">
    <label>批次编号 <span class="required">*</span><input v-model.trim="form.batch_code" required maxlength="64" pattern="[A-Za-z0-9-]{1,64}" placeholder="例如 APPLE-2026-002" /><small>英文、数字或连字符，最多 64 位</small></label>
    <label>产品名称 <span class="required">*</span><input v-model.trim="form.product" required maxlength="50" /></label>
    <label>品种<input v-model.trim="form.variety" maxlength="50" placeholder="例如 红富士" /></label><label>产地<input v-model.trim="form.origin" maxlength="100" placeholder="例如 山东烟台" /></label><label>供应商<input v-model.trim="form.supplier" maxlength="100" placeholder="不在公开溯源页展示" /></label>
    <div class="form-actions"><button class="btn" type="submit" :disabled="submitting">{{ submitting ? '正在保存…' : '保存批次' }}</button><span class="muted">保存到当前后端连接的数据库</span></div><p v-if="submitError" class="notice danger full" role="alert">{{ submitError }}</p>
  </form></section>
  <StateNotice :loading="loading" :error="error" @retry="reload" />
  <section v-if="data" class="card"><div class="section-row"><h2>批次列表 <small class="count">{{ data.length }}</small></h2><label class="search-field"><span class="sr-only">搜索批次</span><input v-model="query" type="search" placeholder="搜索编号、产品、产地或供应商" /></label></div>
    <EmptyState v-if="!filtered.length" :title="data.length ? '没有匹配的批次' : '暂无批次'" description="可调整搜索条件或新建一个批次。" />
    <div v-else class="table-wrap"><table><thead><tr><th>批次编号</th><th>产品 / 品种</th><th>产地</th><th>供应商</th><th>登记时间</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="b in filtered" :key="b.batch_id"><td><RouterLink class="text-link mono" :to="`/batches/${b.batch_id}`">{{ b.batch_code }}</RouterLink></td><td>{{ b.product }}<small class="cell-sub">{{ b.variety || '未录入' }}</small></td><td>{{ b.origin || '未录入' }}</td><td>{{ b.supplier || '未录入' }}</td><td class="muted">{{ formatTime(b.created_at) }}</td><td><span class="badge ready">{{ statusLabel(b.status) }}</span></td><td><RouterLink class="text-link" :to="`/batches/${b.batch_id}`">详情 →</RouterLink></td></tr></tbody></table></div>
  </section>
</template>
