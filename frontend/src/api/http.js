export class ApiError extends Error {
  constructor(message, status = 0) { super(message); this.name = 'ApiError'; this.status = status }
}
export async function request(path, { method = 'GET', json, signal } = {}) {
  const controller = new AbortController()
  const onAbort = () => controller.abort()
  if (signal?.aborted) controller.abort()
  signal?.addEventListener('abort', onAbort, { once: true })
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; controller.abort() }, 12000)
  try {
    const response = await fetch('/api' + path, {
      method, signal: controller.signal,
      headers: json === undefined ? { Accept: 'application/json' } : { Accept: 'application/json', 'Content-Type': 'application/json' },
      ...(json === undefined ? {} : { body: JSON.stringify(json) }),
    })
    if (!response.ok) {
      const messages = { 400: '提交内容不符合要求，请核对批次编号和必填字段。', 401: '请先登录。', 403: '当前账号没有访问权限。', 404: '未找到该批次或记录。', 409: '批次编号已存在，请更换编号。', 500: '后端处理失败，请查看 Spring Boot 控制台日志。', 502: '无法连接后端，请确认 8080 端口的服务正在运行。', 503: '服务暂时不可用，请稍后重试。' }
      throw new ApiError(messages[response.status] || `请求失败（HTTP ${response.status}）。`, response.status)
    }
    if (response.status === 204) return null
    try { return await response.json() } catch { throw new ApiError('接口没有返回有效 JSON，请检查 /api 代理配置。', response.status) }
  } catch (error) {
    if (timedOut) throw new ApiError('请求超时，请确认后端和数据库正常运行。')
    if (error instanceof ApiError || signal?.aborted) throw error
    throw new ApiError('网络请求失败，请确认后端已启动，并使用 npm run dev 打开页面。')
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', onAbort)
  }
}
