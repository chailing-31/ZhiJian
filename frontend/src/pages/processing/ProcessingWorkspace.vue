<script setup>
import { reactive } from 'vue'
import PendingNotice from '../../components/PendingNotice.vue'
import EmptyState from '../../components/EmptyState.vue'
defineProps({ batch: { type: Object, required: true } })
// Ephemeral form only. Nothing here is submitted or persisted.
const draft = reactive({ grade: '', materialTemperature: '', temperature: '', humidity: '', waterPressure: '' })
</script>
<template>
  <PendingNotice text="加工规则服务与采用记录保存尚未接入。下方输入只是页面草稿，切换批次或离开页面会清空，不会生成工艺结论或加工事件。" />
  <div class="two-column">
    <section class="card"><div class="section-row"><h2>01 工艺输入</h2><span class="badge neutral">本地草稿 · 不保存</span></div><div class="form-grid"><label>原料等级<input v-model="draft.grade" placeholder="等待确认后的原料等级" /></label><label>原料温度（℃）<input v-model="draft.materialTemperature" type="number" step="any" placeholder="未录入" /></label><label>环境温度（℃）<input v-model="draft.temperature" type="number" step="any" placeholder="未录入" /></label><label>环境湿度（%）<input v-model="draft.humidity" type="number" min="0" max="100" step="any" placeholder="未录入" /></label><label class="full">清洗水压 / 档位<input v-model="draft.waterPressure" placeholder="单位与允许范围由 B / C 冻结后接入" /></label></div><button class="btn full-width" disabled>生成规则建议（待接入）</button></section>
    <section class="card"><div class="section-row"><h2>02 建议与依据</h2><span class="badge pending">规则建议 · 待接入</span></div><EmptyState title="暂无规则输出" description="后续由业务模块返回建议参数、advice_type=rule 和可解释的生成依据。" icon="sliders" /><div class="notice subtle">建议不是产线控制指令，实际采用值须经人工确认。</div></section>
  </div>
  <section class="card"><h2>03 建议值与采用值</h2><div class="table-wrap"><table><thead><tr><th>工艺参数</th><th>规则建议值</th><th>最终采用值</th><th>采用 / 修改说明</th></tr></thead><tbody><tr v-for="label in ['清洗参数', '预冷参数', '加工备注']" :key="label"><td>{{ label }}</td><td class="muted">待接入</td><td class="muted">待接入</td><td class="muted">待接入</td></tr></tbody></table></div><fieldset disabled class="form-grid"><label>操作员<input placeholder="操作员姓名" /></label><label>采纳或修改说明<input placeholder="采用规则建议或说明修改原因" /></label><button class="btn" type="button">保存采用记录（待接入）</button></fieldset><p class="footnote">采用接口和单位枚举尚未冻结；不能仅修改页面显示就视为业务完成。</p></section>
</template>
