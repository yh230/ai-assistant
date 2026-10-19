import { createAuthHttp } from './http'

/**
 * 患者侧 HTTP 客户端
 * 与 api/index.js 隔离：只注入患者 token（patient_token），401 清患者态跳患者登录，
 * 不会误带员工 token（ai_token）或跳员工登录。
 * 续期/登出逻辑统一由 createAuthHttp 提供（跨 tab 串行刷新、网络异常不登出）。
 */
const auth = createAuthHttp({
  tokenKey: 'patient_token',
  refreshKey: 'patient_refresh_token',
  userKey: 'patient_user',
  loginPath: '/patient/login'
})

const patientApi = auth.http

export const refreshPatientAccessToken = auth.refreshAccessToken
export const forceToPatientLogin = auth.forceLogin

/**
 * 撤销患者刷新令牌（登出时调用，幂等）
 */
export function revokePatientRefresh(refreshToken) {
  return patientApi.post('/refresh/revoke', { refreshToken })
}

/**
 * 患者注册（手机号+密码+姓名+医院）
 */
export function patientRegister(data) {
  return patientApi.post('/patient/register', data)
}

/**
 * 患者登录
 */
export function patientLogin(data) {
  return patientApi.post('/patient/login', data)
}

/**
 * 发送手机号验证码（identity: patient）
 */
export function sendSmsCode(data) {
  return patientApi.post('/login/sms/code', data)
}

/**
 * 手机号验证码登录（identity: patient）
 */
export function loginBySms(data) {
  return patientApi.post('/login/sms', data)
}

/**
 * 创建扫码登录场景（患者端入口）
 */
export function qrCreate() {
  return patientApi.post('/qr/create')
}

/**
 * 轮询扫码结果
 */
export function qrPoll(sceneId) {
  return patientApi.get('/qr/poll', { params: { sceneId } })
}

/**
 * 手机端确认扫码登录（患者实例）
 */
export function qrConfirm(data) {
  return patientApi.post('/qr/confirm', data)
}

/**
 * 公开医院列表（注册选院）
 */
export function publicHospitals() {
  return patientApi.get('/hospital/public/list')
}

/**
 * 患者会话列表（仅本人）
 */
export function patientSessions() {
  return patientApi.get('/chat/patient/sessions')
}

/**
 * 患者会话历史
 */
export function patientHistory(sessionId) {
  return patientApi.get(`/chat/patient/history/${sessionId}`)
}

/**
 * 新建患者会话（type: CHAT/TRIAGE/RAG）
 */
export function createPatientSession(type) {
  return patientApi.post(`/chat/patient/session?type=${type}`)
}

/**
 * 删除患者会话
 */
export function deletePatientSession(sessionId) {
  return patientApi.delete(`/chat/patient/session/${sessionId}`)
}

/**
 * 取消待确认写操作
 */
export function cancelPatientAction(actionId) {
  return patientApi.post(`/chat/patient/action/${actionId}/cancel`)
}

/**
 * 患者通知分页（仅本人）
 */
export function patientNotifications(params) {
  return patientApi.get('/patient/notifications', { params })
}

/**
 * 患者未读通知数
 */
export function patientUnreadCount() {
  return patientApi.get('/patient/notifications/unread-count')
}

/**
 * 标记通知已读
 */
export function readPatientNotification(id) {
  return patientApi.post(`/patient/notifications/${id}/read`)
}

/**
 * 全部标记已读
 */
export function readAllPatientNotifications() {
  return patientApi.post('/patient/notifications/read-all')
}

/**
 * 解析 SSE 事件流（患者侧，事件：session/token/done/error/confirm_required）
 */
async function consumeSse(response, handlers, signal) {
  if (response.status === 401 || response.status === 403) {
    forceToPatientLogin()
    throw new Error(`HTTP ${response.status}: 未登录或会话已失效`)
  }
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let doneSeen = false
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    // SSE 事件以 \n\n 分隔
    const parts = buffer.split('\n\n')
    buffer = parts.pop() // 保留不完整的部分
    for (const part of parts) {
      if (!part.trim()) continue
      let eventType = 'message'
      let dataStr = ''
      for (const line of part.split('\n')) {
        if (line.startsWith('event:')) eventType = line.slice(6).trim()
        else if (line.startsWith('data:')) dataStr += line.slice(5).replace(/^\s/, '')
      }
      if (eventType === 'session') {
        try { handlers.onSession?.(JSON.parse(dataStr)) } catch { /* ignore */ }
      } else if (eventType === 'token') {
        handlers.onToken?.(dataStr)
      } else if (eventType === 'confirm_required') {
        try { handlers.onConfirm?.(JSON.parse(dataStr)) } catch { /* ignore */ }
      } else if (eventType === 'references') {
        try { handlers.onReferences?.(JSON.parse(dataStr)) } catch { /* ignore */ }
      } else if (eventType === 'done') {
        doneSeen = true
        try { handlers.onDone?.(JSON.parse(dataStr)) } catch { handlers.onDone?.({}) }
      } else if (eventType === 'error') {
        try { handlers.onError?.(JSON.parse(dataStr).message || dataStr) } catch { handlers.onError?.(dataStr) }
      }
    }
  }
  // 流结束兜底：未收到 done 事件也收尾，避免 loading 悬挂（主对话流与 confirm 续答流共用）
  if (!doneSeen) handlers.onDone?.({})
}

async function fetchWithPatientRefresh(url, options) {
  let response = await fetch(url, options)
  if (response.status === 401) {
    const token = await refreshPatientAccessToken()
    response = await fetch(url, { ...options, headers: { ...options.headers, Authorization: 'Bearer ' + token } })
  }
  return response
}

/**
 * 患者流式对话（SSE），返回 AbortController 供「停止生成」
 */
export function sendPatientStream(data, handlers) {
  const token = localStorage.getItem('patient_token')
  const controller = new AbortController()
  fetchWithPatientRefresh('/api/chat/patient/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {})
    },
    body: JSON.stringify(data),
    signal: controller.signal
  })
    .then((response) => consumeSse(response, handlers))
    .catch((err) => {
      if (err.name !== 'AbortError') handlers.onError?.(err.message || '网络异常')
    })
  return controller
}

/**
 * 确认写操作（SSE 续答），返回 AbortController 供「停止生成」
 */
export function confirmPatientAction(actionId, handlers) {
  const token = localStorage.getItem('patient_token')
  const controller = new AbortController()
  fetchWithPatientRefresh(`/api/chat/patient/action/${actionId}/confirm`, {
    method: 'POST',
    headers: token ? { Authorization: 'Bearer ' + token } : {},
    signal: controller.signal
  })
    .then((response) => consumeSse(response, handlers))
    .catch((err) => {
      if (err.name !== 'AbortError') handlers.onError?.(err.message || '网络异常')
    })
  return controller
}
