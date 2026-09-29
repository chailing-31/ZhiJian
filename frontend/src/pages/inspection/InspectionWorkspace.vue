<script setup>
import { onBeforeUnmount, ref } from 'vue'
import PendingNotice from '../../components/PendingNotice.vue'
import EmptyState from '../../components/EmptyState.vue'
import Icon from '../../components/Icon.vue'
defineProps({ batch: { type: Object, required: true } })
const preview = ref(''), filename = ref(''), fileError = ref('')
function release() { if (preview.value) URL.revokeObjectURL(preview.value); preview.value = ''; filename.value = '' }
function choose(event) {
  release(); fileError.value = ''
  const file = event.target.files?.[0]
  if (!file) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) { fileError.value = '请选择 JPG、PNG 或 WebP 图片。'; event.target.value = ''; return }
  if (file.size > 10 * 1024 * 1024) { fileError.value = '图片超过 10 MiB，请先压缩。'; event.target.value = ''; return }
  preview.value = URL.createObjectURL(file); filename.value = file.name
}
onBeforeUnmount(release)
</script>
<template>
  <PendingNotice text="模型上传、推理和人工复核接口尚未接入。选图只在浏览器本地预览，不上传图片、不生成检测框、不写入数据库。" />
  <div class="two-column">
    <section class="card"><div class="section-row"><h2>01 原图预览</h2><span class="badge neutral">本地预览</span></div><div class="image-stage"><img v-if="preview" :src="preview" alt="本地选择的待检原图" /><EmptyState v-else title="选择一张苹果图片" description="JPG / PNG / WebP · 不超过 10 MiB" icon="scan" /></div><label class="file-control"><span class="btn secondary"><Icon name="plus" />选择图片</span><input type="file" accept="image/jpeg,image/png,image/webp" aria-label="选择待检图片" @change="choose" /></label><p v-if="filename" class="footnote break-word">{{ filename }} · 尚未上传</p><p v-if="fileError" class="notice danger" role="alert">{{ fileError }}</p><button class="btn full-width" disabled title="模型推理服务待接入">开始检测（待接入）</button></section>
    <section class="card"><div class="section-row"><h2>02 模型原始结果</h2><span class="badge pending">待接入</span></div><div class="image-stage"><EmptyState title="尚无模型推理结果" description="后续显示标注图、缺陷位置、类别、置信度及模型版本。" icon="scan" /></div><div class="result-summary"><div><span>模型版本</span><strong>未接入</strong></div><div><span>建议等级</span><strong>—</strong></div><div><span>推理耗时</span><strong>—</strong></div></div><div class="table-wrap"><table><thead><tr><th>缺陷类别</th><th>置信度</th><th>位置坐标</th></tr></thead><tbody><tr><td colspan="3" class="empty-cell">待真实推理接口返回，不使用示例结果填充</td></tr></tbody></table></div></section>
  </div>
  <section class="card"><div class="section-row"><h2>03 人工复核</h2><span class="badge pending">待接入</span></div><fieldset disabled class="form-grid"><label>最终等级<select><option>等待模型结果与复核接口</option></select></label><label>复核人<input placeholder="复核人" /></label><label class="full">改判原因 / 备注<textarea rows="3" placeholder="原始模型结果应保留，人工修改单独记录。" /></label><button class="btn" type="button">保存复核结果（待接入）</button></fieldset><p class="footnote">该区不提交数据；后续接入后记录修改人、修改时间和最终结论。</p></section>
</template>
