<script setup>
import { computed, onMounted, ref } from 'vue'

const path = ref(location.pathname)
const batches = ref([])
const selected = ref(null)
const trace = ref(null)
const busy = ref(false)
const error = ref('')
const form = ref({ batch_code: '', product: '苹果', variety: '', origin: '', supplier: '' })
const isTrace = computed(() => path.value.startsWith('/trace/'))
const traceCode = computed(() => decodeURIComponent(path.value.slice('/trace/'.length)))

window.addEventListener('popstate', () => { path.value = location.pathname; refresh() })
function navigate(next) { history.pushState({}, '', next); path.value = next; error.value = ''; refresh() }
async function api(url, options) {
  const response = await fetch('/api' + url, options)
  if (!response.ok) throw new Error(response.status === 404 ? '未找到该批次' : response.status === 409 ? '批次编号已存在' : `请求失败（${response.status}）`)
  return response.json()
}
async function refresh() {
  busy.value = true; error.value = ''
  try {
    if (isTrace.value) trace.value = await api('/trace/' + encodeURIComponent(traceCode.value))
    else {
      batches.value = await api('/batches')
      const id = Number(path.value.match(/^\/batches\/(\d+)$/)?.[1])
      selected.value = id ? await api('/batches/' + id) : null
    }
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function createBatch() {
  busy.value = true; error.value = ''
  try {
    const item = await api('/batches', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(form.value) })
    form.value = { batch_code: '', product: '苹果', variety: '', origin: '', supplier: '' }
    navigate('/batches/' + item.batch_id)
  } catch (e) { error.value = e.message; busy.value = false }
}
function time(value) { return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '暂无记录' }
onMounted(refresh)
</script>

<template>
  <div class="shell">
    <header><div class="brand">智检鲜达 <small>果蔬质量与溯源 Demo</small></div><nav v-if="!isTrace"><a href="/" @click.prevent="navigate('/')">首页</a><a href="/batches" @click.prevent="navigate('/batches')">批次管理</a><a href="/inspection" @click.prevent="navigate('/inspection')">AI质检</a></nav></header>
    <main>
      <p v-if="busy" class="message">正在读取批次数据…</p>
      <p v-if="error" class="message error" role="alert">{{ error }}</p>
      <template v-if="isTrace && trace">
        <div class="eyebrow">公开溯源页面</div><h1>{{ trace.batch_code }}</h1>
        <section class="card"><h2>产品信息</h2><p>{{ trace.product }} · {{ trace.variety || '品种未录入' }} · {{ trace.origin || '产地未录入' }}</p></section>
        <section class="card"><h2>批次时间线</h2><p v-if="!trace.events.length">暂无记录</p><ol v-else class="timeline"><li v-for="(event, index) in trace.events" :key="index"><time>{{ time(event.event_time) }}</time><strong>{{ event.event_type }}</strong><span>{{ event.summary }}</span></li></ol></section>
        <button class="text-button" @click="navigate('/')">返回管理端</button>
      </template>
      <template v-else-if="path === '/'">
        <div class="eyebrow">系统概览</div><h1>批次总览</h1>
        <section class="stat"><span>已登记批次</span><strong>{{ batches.length }}</strong></section>
        <section class="card"><h2>最近批次</h2><p v-if="!batches.length">暂无记录</p><button v-for="item in batches.slice(0, 5)" :key="item.batch_id" class="row" @click="navigate('/batches/' + item.batch_id)"><strong>{{ item.batch_code }}</strong><span>{{ item.product }} · {{ item.status }}</span></button></section>
      </template>
      <template v-else-if="path === '/batches'">
        <div class="eyebrow">批次管理</div><h1>批次列表</h1>
        <section class="card"><h2>新建批次</h2><form @submit.prevent="createBatch"><label>批次编号 <input v-model.trim="form.batch_code" required pattern="[A-Za-z0-9-]{1,64}" /></label><label>产品 <input v-model.trim="form.product" required /></label><label>品种 <input v-model.trim="form.variety" /></label><label>产地 <input v-model.trim="form.origin" /></label><label>供应商 <input v-model.trim="form.supplier" /></label><button type="submit" :disabled="busy">创建</button></form></section>
        <section class="card"><h2>全部批次</h2><p v-if="!batches.length">暂无记录</p><button v-for="item in batches" :key="item.batch_id" class="row" @click="navigate('/batches/' + item.batch_id)"><strong>{{ item.batch_code }}</strong><span>{{ item.product }} · {{ item.status }}</span></button></section>
      </template>
      <template v-else-if="selected">
        <div class="eyebrow">批次详情</div><h1>{{ selected.batch_code }}</h1>
        <section class="card"><h2>基础信息</h2><dl><dt>产品</dt><dd>{{ selected.product || '未录入' }}</dd><dt>品种</dt><dd>{{ selected.variety || '未录入' }}</dd><dt>产地</dt><dd>{{ selected.origin || '未录入' }}</dd><dt>供应商</dt><dd>{{ selected.supplier || '未录入' }}</dd><dt>状态</dt><dd>{{ selected.status }}</dd></dl></section>
        <section class="card"><h2>批次事件</h2><p v-if="!selected.events.length">暂无记录</p><ol v-else class="timeline"><li v-for="(event, index) in selected.events" :key="index"><time>{{ time(event.event_time) }}</time><strong>{{ event.event_type }}</strong><span>{{ event.summary }}</span></li></ol></section>
        <button @click="navigate('/trace/' + selected.batch_code)">查看手机溯源页</button>
      </template>
      <template v-else-if="path === '/inspection'"><div class="eyebrow">AI视觉质检</div><h1>质检记录</h1><section class="card"><p>暂无记录。图片上传和模型推理将在下一阶段接入。</p></section></template>
      <template v-else-if="!busy && !error"><h1>页面不存在</h1><button @click="navigate('/')">返回首页</button></template>
    </main>
  </div>
</template>
