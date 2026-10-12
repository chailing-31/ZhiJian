<script setup>
import { computed, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { getPublicTrace } from '../../api/trace.js'
import { useAsyncData } from '../../composables/useAsyncData.js'
import { isLoopback, traceUrl } from '../../utils/batch.js'
import EventTimeline from '../../components/EventTimeline.vue'
import StateNotice from '../../components/StateNotice.vue'
const props = defineProps({ batch: { type: Object, required: true } })
const { data, loading, error, reload } = useAsyncData(signal => getPublicTrace(props.batch.batch_code, { signal }), () => props.batch.batch_code)
const origin = ref(window.location.origin), qr = ref(''), qrError = ref(''), copied = ref('')
const link = computed(() => { try { return traceUrl(origin.value, props.batch.batch_code) } catch { return '' } })
const localOnly = computed(() => isLoopback(origin.value))
let serial = 0
watch(link, async value => {
  const run = ++serial; qr.value = ''; qrError.value = ''; copied.value = ''
  if (!value) { qrError.value = '请填写 http(s)://主机:端口，不包含页面路径。'; return }
  try { const img = await QRCode.toDataURL(value, { width: 224, margin: 2, errorCorrectionLevel: 'M' }); if (run === serial) qr.value = img }
  catch { if (run === serial) qrError.value = '二维码生成失败，仍可手动复制链接。' }
}, { immediate: true })
async function copyLink() {
  try { await navigator.clipboard.writeText(link.value); copied.value = '链接已复制。' }
  catch { copied.value = '此浏览器不允许自动复制，请手动选择上方链接复制。' }
}
</script>
<template>
  <div class="notice subtle">公开时间线读取现有接口。包装、运输、出厂事件录入仍待接入；批次综合记录已接入只读聚合接口，这里不会自动创建这些事件。</div>
  <div class="trace-grid">
    <section class="card"><div class="section-row"><h2>公开事件时间线</h2><button class="btn small secondary" :disabled="loading" @click="reload">刷新</button></div><StateNotice :loading="loading" :error="error" @retry="reload" /><template v-if="data"><p class="muted">只读取公开溯源接口返回的事件，不把管理端全部事件直接发布。</p><EventTimeline :events="data.events || []" /></template></section>
    <section class="card qr-card"><h2>手机扫码入口</h2><img v-if="qr" :src="qr" :alt="`${batch.batch_code} 的公开溯源二维码`" class="qr-image" /><p v-if="qrError" class="notice danger">{{ qrError }}</p><label>手机可访问的前端地址<input v-model.trim="origin" placeholder="http://电脑局域网IP:5173" /></label><p v-if="localOnly" class="notice warning">当前地址是本机地址，手机不能通过它访问你的电脑。请改为电脑的局域网 IP 和前端端口。</p><p class="footnote">手机与电脑在同一可信网络，并启动 npm run dev:lan；本地二维码生成不依赖外部扫码服务。</p><label>公开链接<input :value="link" readonly aria-label="公开溯源链接" /></label><div class="button-row"><button class="btn secondary" :disabled="!link" @click="copyLink">复制链接</button><a v-if="qr" class="btn secondary" :href="qr" :download="`${batch.batch_code}-qr.png`">保存二维码</a></div><p v-if="copied" class="footnote" role="status">{{ copied }}</p><RouterLink class="btn full-width" :to="{ name: 'public-trace', params: { batchCode: batch.batch_code } }" target="_blank" rel="noopener">打开当前站点的公开页</RouterLink></section>
  </div>
  <section class="card"><div class="section-row"><div><h2>批次综合记录</h2><p class="muted">从数据库只读聚合已保存的质检/复核、加工、冷链、告警和事件，支持浏览器打印；不是食品安全或正式质检证明。</p></div><RouterLink class="btn secondary" :to="`/batches/${batch.batch_id}/report`">查看综合记录 →</RouterLink></div><p class="footnote">当前综合记录只聚合已经保存的数据；未发生或尚未接入的环节保持为空，不自动补造结果。</p></section>
</template>
