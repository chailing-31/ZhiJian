// Module availability is explicit. Never turn an unconnected API into fake success.
export const modules = Object.freeze([
  { key: 'dashboard', name: '工作台', path: '/', icon: 'dashboard', status: '真实批次数据', tone: 'ready', description: '批次总览、最近登记和模块入口。' },
  { key: 'batches', name: '批次管理', path: '/batches', icon: 'box', status: '已接入', tone: 'ready', description: '创建、查询批次，查看已保存事件。' },
  { key: 'inspection', name: '照片质检', path: '/inspection', icon: 'scan', status: '已接入', tone: 'ready', description: '上传自动初评，可选检查修正；运行需本地 AI 服务。' },
  { key: 'processing', name: '加工品控', path: '/processing', icon: 'sliders', status: '已接入', tone: 'ready', description: '工艺输入、规则建议与人工采用记录。' },
  { key: 'coldchain', name: '冷链监测', path: '/coldchain', icon: 'thermometer', status: '已接入', tone: 'ready', description: '读数保存、持续超温告警与人工处置。' },
  { key: 'traceability', name: '溯源与报告', path: '/traceability', icon: 'trace', status: '已接入', tone: 'ready', description: '公开时间线、二维码和记录预览。' },
])
