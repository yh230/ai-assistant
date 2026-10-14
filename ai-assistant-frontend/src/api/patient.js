import axios from 'axios'

/**
 * 患者侧独立 axios 实例
 * 与 api/index.js 隔离：只注入患者 token（patient_token），401 清患者态跳患者登录，
 * 不会误带员工 token（ai_token）或跳员工登录
 */
const patientApi = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 防止并发 401 跳登录导致多次 location.href
let redirecting = false
const REFRESH_KEY = 'patient_refresh_token'
function forceToPatientLogin() {
  if (redirecting) return
  redirecting = true
  localStorage.removeItem('patient_token')
  localStorage.removeItem('patient_user')
  localStorage.removeItem(REFRESH_KEY)
  if (!window.location.pathname.includes('/patient/login')) {
    window.location.href = '/patient/login'
  }
  // 给后续可能仍在路上的请求留一次机会重置
  setTimeout(() => { redirecting = false }, 500)
}

let refreshPromise = null
function refreshPatientAccessToken() {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      const refreshToken = localStorage.getItem(REFRESH_KEY)
      if (!refreshToken) throw new Error('无刷新令牌')
      const res = await fetch('/api/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken })
      })
      const data = await res.json().catch(() => null)
      if (!res.ok || !data || data.code !== 200 || !data.data) {
        throw new Error((data && data.msg) || '登录已过期')
      }
      localStorage.setItem('patient_token', data.data.token)
      localStorage.setItem(REFRESH_KEY, data.data.refreshToken)
      return data.data.token
    })().finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

/**
 * 撤销患者刷新令牌（登出时调用，幂等）
 */
export function revokePatientRefresh(refreshToken) {
  return patientApi.post('/refresh/revoke', { refreshToken })
}

// 请求拦截器：自动携带患者 Token
patientApi.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('patient_token')
    if (token) {
      config.headers.Authorization = 'Bearer ' + token
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器：约定与员工侧一致（code===200 解包 data；401 跳患者登录）
patientApi.interceptors.response.use(
  (response) => {
    const data = response.data
    if (data && data.code === 200) {
      return data.data
    }
    const msg = (data && data.msg) || ''
    return Promise.reject(new Error(msg || '请求失败'))
  },
  (error) => {
    if (error.response) {
      const { status, data } = error.response
      const cfg = error.config || {}
      const isRefreshCall = (cfg.url || '').includes('/refresh')
      if (status === 401 && !isRefreshCall && !cfg._retry) {
        cfg._retry = true
        return refreshPatientAccessToken()
          .then(() => patientApi(cfg))
          .catch(() => {
            forceToPatientLogin()
            return Promise.reject(new Error('登录已过期，请重新登录'))
          })
      }
      if (status === 401) {
        forceToPatientLogin()
      }
      const msg = status === 429 ? '操作过于频繁，请稍后再试' : ((data && data.msg) || `请求错误 (${status})`)
      return Promise.reject(new Error(msg))
    }
    return Promise.reject(new Error('网络异常或服务不可用'))
  }
)

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
