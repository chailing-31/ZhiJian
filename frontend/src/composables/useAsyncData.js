import { onScopeDispose, ref, watch } from 'vue'

// A new key cancels the preceding request. Old-batch responses cannot overwrite the new view.
export function useAsyncData(loader, key = () => 'once') {
  const data = ref(null), loading = ref(false), error = ref('')
  let controller, serial = 0
  async function reload() {
    const run = ++serial
    controller?.abort()
    controller = new AbortController()
    const signal = controller.signal
    data.value = null; error.value = ''; loading.value = true
    try {
      const value = await loader(signal)
      if (run === serial) data.value = value
    } catch (e) {
      if (run === serial && !signal.aborted) error.value = e.message || '读取失败，请重试。'
    } finally { if (run === serial) loading.value = false }
  }
  watch(key, reload, { immediate: true })
  onScopeDispose(() => { serial++; controller?.abort() })
  return { data, loading, error, reload }
}
