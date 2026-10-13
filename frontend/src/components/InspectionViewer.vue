<script setup>
import { computed, ref, watch } from 'vue'
import { candidateCaption, validPredictionGeometry } from '../utils/engineering.js'
const props = defineProps({ record: { type: Object, required: true }, selected: { type: Number, default: null } })
const emit = defineEmits(['select'])
const mode = ref('overlay'), zoom = ref(1), loaded = ref(false), failed = ref(false)
const p = computed(() => props.record.prediction)
const valid = computed(() => validPredictionGeometry(p.value))
const unit = computed(() => Math.max(p.value?.image?.width || 1, p.value?.image?.height || 1) / 65)
watch(() => props.record.inspection_id, () => { loaded.value = false; failed.value = false; mode.value = 'overlay'; zoom.value = 1 })
</script>
<template>
  <div class="e-viewer">
    <div class="e-viewer-toolbar"><div class="e-tabs" role="group" aria-label="图像显示方式"><button :class="{ active: mode === 'overlay' }" @click="mode = 'overlay'">交互标注</button><button :class="{ active: mode === 'input' }" @click="mode = 'input'">原始输入</button><button :class="{ active: mode === 'saved' }" @click="mode = 'saved'">服务端标注</button></div><label class="e-zoom">缩放<select v-model.number="zoom"><option :value="1">100%</option><option :value="1.5">150%</option><option :value="2">200%</option></select></label></div>
    <p v-if="!valid" class="notice danger">检测框坐标或图片尺寸异常，已停止画框；请导出诊断记录，不显示猜测位置。</p>
    <div v-else class="e-canvas-scroll">
      <svg v-if="mode === 'overlay'" :viewBox="`0 0 ${p.image.width} ${p.image.height}`" :style="{ width: `${zoom * 100}%` }" class="e-canvas" role="img" aria-label="模型输入图及真实检测框">
        <image :href="record.artifact_urls.input" :width="p.image.width" :height="p.image.height" @load="loaded = true; failed = false" @error="failed = true" />
        <g v-if="loaded && !failed" v-for="(d, index) in p.detections" :key="index" role="button" tabindex="0" :aria-label="candidateCaption(d, index)" @click="emit('select', index + 1)" @keydown.enter.prevent="emit('select', index + 1)" @keydown.space.prevent="emit('select', index + 1)">
          <title>{{ candidateCaption(d, index) }}</title>
          <rect :x="d.bbox_xyxy[0]" :y="d.bbox_xyxy[1]" :width="d.bbox_xyxy[2] - d.bbox_xyxy[0]" :height="d.bbox_xyxy[3] - d.bbox_xyxy[1]" class="e-box" :class="{ selected: selected === index + 1, pest: d.class_id === 1 }" />
          <text :x="Math.min(d.bbox_xyxy[0] + unit / 2, p.image.width - unit * 2)" :y="Math.min(p.image.height - unit / 2, Math.max(unit * 2, d.bbox_xyxy[1] - unit / 2))" :font-size="unit * 1.7" class="e-box-label">#{{ index + 1 }}</text>
        </g>
      </svg>
      <img v-else :src="mode === 'saved' ? record.artifact_urls.result : record.artifact_urls.input" :style="{ width: `${zoom * 100}%` }" alt="已保存的模型输入或服务端标注图" @load="loaded = true; failed = false" @error="failed = true" />
    </div>
    <p v-if="failed" class="notice danger">证据图片加载失败。请检查后端图片存储目录；此时不能判断框的位置。</p>
    <div class="e-viewer-footer"><span>{{ p.image.width }} × {{ p.image.height }} px</span><span>{{ p.detections.length }} 个候选 · 数字为候选序号，不是类别 ID</span><a :href="record.artifact_urls.input" target="_blank" rel="noopener">打开大图 ↗</a></div>
  </div>
</template>
