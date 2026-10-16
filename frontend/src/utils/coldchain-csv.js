// Parse UTF-8 CSV including quoted fields, BOM and Windows line endings.
export function parseColdchainCsv(text) {
  const rows = [], row = []; let field = '', quoted = false, closed = false
  const input = text.replace(/^\uFEFF/, '')
  function finishField() { row.push(field.trim()); field = ''; closed = false }
  function finishRow() { finishField(); if (row.some(value => value !== '')) rows.push([...row]); row.length = 0 }
  for (let i = 0; i < input.length; i++) {
    const char = input[i]
    if (quoted) {
      if (char === '"') { if (input[i + 1] === '"') { field += '"'; i++ } else { quoted = false; closed = true } }
      else field += char
    } else if (char === '"') {
      if (field.trim() || closed) throw new Error('CSV 引号格式不正确。')
      field = ''; quoted = true
    } else if (char === ',') finishField()
    else if (char === '\n' || char === '\r') { finishRow(); if (char === '\r' && input[i + 1] === '\n') i++ }
    else { if (closed && char.trim()) throw new Error('CSV 引号后包含无效内容。'); field += char }
  }
  if (quoted) throw new Error('CSV 引号未闭合。')
  if (field || row.length || closed) finishRow()
  if (rows.length < 2) throw new Error('CSV 必须包含表头和至少一条读数。')
  const aliases = new Map([
    ['读数时间', 'timestamp'], ['温度（℃）', 'temperature'], ['湿度（%）', 'humidity'],
    ['数据来源', 'source'], ['门开启状态', 'door_open'], ['设备电流（A）', 'equipment_current'],
  ])
  const headers = rows.shift().map(header => aliases.get(header) || header)
  const allowed = ['timestamp', 'temperature', 'humidity', 'source', 'door_open', 'equipment_current']
  if (new Set(headers).size !== headers.length || headers.some(h => !allowed.includes(h))) throw new Error('CSV 包含重复或未知列。')
  if (!allowed.slice(0, 4).every(h => headers.includes(h))) throw new Error('CSV 必须包含读数时间、温度（℃）、湿度（%）、数据来源列（也支持原英文列名）。')
  if (rows.length > 10000) throw new Error('单次 CSV 最多 10000 条读数。')
  const previous = new Map()
  return rows.map((values, index) => {
    const fail = message => { throw new Error(`CSV 第 ${index + 2} 行：${message}`) }
    if (values.length !== headers.length) fail('列数不匹配。')
    const item = Object.fromEntries(headers.map((h, i) => [h, values[i]]))
    item.source = new Map([['模拟数据', 'simulation'], ['传感器采集', 'sensor']]).get(item.source) || item.source
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})$/.test(item.timestamp) || !Number.isFinite(Date.parse(item.timestamp))) fail('时间须为带时区、精确到秒的 ISO 8601。')
    if (!['simulation', 'sensor'].includes(item.source)) fail('数据来源须为模拟数据或传感器采集（simulation / sensor）。')
    const time = Date.parse(item.timestamp)
    if (previous.has(item.source) && time <= previous.get(item.source)) fail('同来源时间必须严格递增，不能重复。')
    previous.set(item.source, time)
    for (const key of ['temperature', 'humidity', 'equipment_current']) {
      if (key === 'equipment_current' && !item[key]) { delete item[key]; continue }
      if (!item[key] || !Number.isFinite(Number(item[key]))) fail(`${key} 必须是有限数值。`)
      item[key] = Number(item[key])
    }
    if (item.humidity < 0 || item.humidity > 100) fail('湿度必须在 0—100 之间。')
    if (item.equipment_current < 0) fail('电流不能为负数。')
    if (item.door_open) {
      const door = new Map([['开启', 'true'], ['关闭', 'false']]).get(item.door_open) || item.door_open.toLowerCase()
      if (!['true', 'false', '1', '0'].includes(door)) fail('door_open 须为 true/false/1/0。')
      item.door_open = ['true', '1'].includes(door)
    } else delete item.door_open
    return item
  })
}
