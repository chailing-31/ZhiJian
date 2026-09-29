<script setup>
import { ref } from 'vue'
import PendingNotice from '../../components/PendingNotice.vue'
import EmptyState from '../../components/EmptyState.vue'
defineProps({ batch: { type: Object, required: true } })
const fileName = ref(''), error = ref('')
function choose(event) {
  fileName.value = ''; error.value = ''
  const f = event.target.files?.[0]
  if (!f) return
  if (!f.name.toLowerCase().endsWith('.csv')) { error.value = '请选择 CSV 文件。'; event.target.value = ''; return }
  if (f.size > 5 * 1024 * 1024) { error.value = '文件超过本地选择限制 5 MiB。'; event.target.value = ''; return }
  fileName.value = f.name
}
</script>
<template>
  <PendingNotice text="传感器数据读取、CSV 导入、异常检测及告警处置尚未接入。当前不画模拟曲线、不显示“零告警”，也不保存处置结果。" />
  <section class="card"><div class="section-row"><h2>01 数据输入</h2><span class="badge pending">导入服务待接入</span></div><div class="input-strip"><label class="file-control"><span class="btn secondary">选择 CSV（仅显示文件名）</span><input type="file" accept=".csv,text/csv" aria-label="选择冷链 CSV 文件" @change="choose" /></label><span class="muted break-word">{{ fileName || '尚未选择文件' }}</span><button class="btn" disabled>导入并检测（待接入）</button><button class="btn secondary" disabled>定速播放（待接入）</button></div><p v-if="error" class="notice danger" role="alert">{{ error }}</p><p class="footnote">任务书字段包括 timestamp、temperature、humidity 及实际具备的设备字段。source=simulation 表示模拟数据，source=sensor 表示真实采集数据；本次未导入任何数据。</p></section>
  <section class="card"><div class="section-row"><h2>02 温湿度时序</h2><span class="badge neutral">曲线待数据接口接入</span></div><div class="chart-empty"><span class="axis-label">温度 / 湿度</span><EmptyState title="时序数据尚未接入" description="未来在这里展示真实返回的曲线、异常区段和触发依据。" icon="thermometer" /><span class="axis-time">时间 →</span></div><div class="result-summary"><div><span>最近温度</span><strong>—</strong></div><div><span>最近湿度</span><strong>—</strong></div><div><span>数据来源</span><strong>未接入</strong></div></div></section>
  <section class="card"><div class="section-row"><h2>03 告警与处置</h2><span class="badge pending">待接入</span></div><div class="table-wrap"><table><thead><tr><th>触发时间</th><th>告警级别</th><th>触发值 / 原因</th><th>状态</th><th>操作</th></tr></thead><tbody><tr><td colspan="5" class="empty-cell">告警列表尚未接入，不能判断当前是否有告警</td></tr></tbody></table></div><fieldset class="form-grid" disabled><label>处置人<input placeholder="选择告警后填写" /></label><label>处置说明<input placeholder="处理措施及结果" /></label><button class="btn" type="button">保存处置记录（待接入）</button></fieldset></section>
</template>
