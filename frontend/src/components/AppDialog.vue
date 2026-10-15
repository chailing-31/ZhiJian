<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from './Icon.vue'
const props = defineProps({ open: Boolean, busy: Boolean, title: { type: String, required: true }, description: { type: String, default: '' }, id: { type: String, default: 'app-dialog' } })
const emit = defineEmits(['close'])
const dialog = ref(null)
async function sync() {
  await nextTick()
  if (!dialog.value) return
  if (props.open && !dialog.value.open) dialog.value.showModal()
  if (!props.open && dialog.value.open) dialog.value.close()
}
function outside(event) {
  if (props.busy) return
  if (event.target !== dialog.value) return
  const r = dialog.value.getBoundingClientRect()
  if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) emit('close')
}
watch(() => props.open, sync)
onMounted(sync)
onBeforeUnmount(() => dialog.value?.close())
</script>
<template>
  <dialog :id="id" ref="dialog" class="ui-dialog" :aria-labelledby="`${id}-title`" :aria-describedby="description ? `${id}-description` : undefined" :aria-busy="busy" @cancel.prevent="!busy && emit('close')" @click="outside">
    <header><div><h2 :id="`${id}-title`">{{ title }}</h2><p v-if="description" :id="`${id}-description`">{{ description }}</p></div><button class="ui-icon-button" type="button" aria-label="关闭弹窗" :disabled="busy" @click="emit('close')"><Icon name="close" /></button></header>
    <slot />
  </dialog>
</template>
