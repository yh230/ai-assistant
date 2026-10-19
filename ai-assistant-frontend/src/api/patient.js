import { createAuthHttp } from './http'
import { streamSse } from './sse'

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
 * 打开患者侧 SSE 流（主对话流与 confirm 续答流共用）
 * 事件：session/token/confirm_required/references/done/error
 * 返回 AbortController 供「停止生成」
 */
function openPatientStream(url, body, handlers = {}) {
  const { controller, done } = streamSse(url, {
    body: body === undefined ? undefined : JSON.stringify(body),
    getToken: () => localStorage.getItem('patient_token'),
    refresh: refreshPatientAccessToken,
    onAuthFailure: forceToPatientLogin,
    onEvent: (event, dataStr) => {
      if (event === 'session') {
        try { handlers.onSession?.(JSON.parse(dataStr)) } catch { /* ignore */ }
      } else if (event === 'token') {
        handlers.onToken?.(dataStr)
      } else if (event === 'confirm_required') {
        try { handlers.onConfirm?.(JSON.parse(dataStr)) } catch { /* ignore */ }
      } else if (event === 'references') {
        try { handlers.onReferences?.(JSON.parse(dataStr)) } catch { /* ignore */ }
      } else if (event === 'done') {
        try { handlers.onDone?.(JSON.parse(dataStr)) } catch { handlers.onDone?.({}) }
      } else if (event === 'error') {
        try { handlers.onError?.(JSON.parse(dataStr).message || dataStr) } catch { handlers.onError?.(dataStr) }
      }
    }
  })
  done.catch(err => {
    if (err.name !== 'AbortError') handlers.onError?.(err.message || '网络异常')
  })
  return controller
}

/**
 * 患者流式对话（SSE），返回 AbortController 供「停止生成」
 */
export function sendPatientStream(data, handlers) {
  return openPatientStream('/api/chat/patient/stream', data, handlers)
}

/**
 * 确认写操作（SSE 续答），返回 AbortController 供「停止生成」
 */
export function confirmPatientAction(actionId, handlers) {
  return openPatientStream(`/api/chat/patient/action/${actionId}/confirm`, undefined, handlers)
}
