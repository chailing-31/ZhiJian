export function statusLabel(value) {
  return { created: '已登记' }[value] || value || '未录入'
}
export function sourceLabel(source) {
  return { demo: '演示数据', simulation: '模拟数据', sensor: '传感器数据', manual: '人工记录' }[source] || source || '来源未标注'
}
export function formatTime(value) {
  if (!value) return '时间未录入'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? '时间格式待核对' : d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}
export function validId(value) {
  const s = typeof value === 'number' || typeof value === 'string' ? String(value) : ''
  return /^[1-9]\d*$/.test(s) && Number.isSafeInteger(Number(s)) ? s : ''
}
export function moduleLocation(path, id) {
  return { path, query: validId(id) ? { batch_id: validId(id) } : {} }
}
export function searchBatches(items, query) {
  const q = String(query || '').trim().toLocaleLowerCase()
  return items.filter(b => !q || [b.batch_code, b.product, b.variety, b.origin, b.supplier].some(v => String(v || '').toLocaleLowerCase().includes(q)))
}
// Presence of an event is NOT proof that a whole production stage is complete.
export function eventStages(events = []) {
  const groups = [
    ['入厂', ['入厂']], ['质检 / 复核', ['AI 质检', 'AI质检', '质检', '人工复核']],
    ['加工', ['加工']], ['冷链 / 仓储', ['冷链', '仓储', '冷链运输']],
    ['包装 / 运输', ['包装', '运输']], ['出厂', ['出厂']],
  ]
  return groups.map(([label, names]) => {
    const count = events.filter(e => names.includes(e.event_type)).length
    return { label, count, state: count ? '有相关记录' : '暂无记录' }
  })
}
export function validateTraceOrigin(value) {
  let u
  try { u = new URL(String(value).trim()) } catch { throw new Error('请填写完整访问地址，例如 http://192.168.1.6:5173') }
  if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password || u.search || u.hash || u.pathname !== '/') {
    throw new Error('只填写 http(s)://主机:端口，不包含用户名、密码、页面路径或查询参数。')
  }
  return u.origin
}
export function traceUrl(origin, code) {
  if (typeof code !== 'string' || !/^[A-Za-z0-9-]{1,64}$/.test(code)) throw new Error('批次编号格式不正确。')
  return `${validateTraceOrigin(origin)}/trace/${encodeURIComponent(code)}`
}
export function isLoopback(origin) {
  try { const h = new URL(origin).hostname; return h === 'localhost' || h.endsWith('.localhost') || h.startsWith('127.') || h === '[::1]' || h === '0.0.0.0' } catch { return false }
}
