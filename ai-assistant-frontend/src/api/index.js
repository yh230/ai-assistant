import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 防止并发 401 跳登录导致多次 location.href
let redirecting = false
const REFRESH_KEY = 'ai_refresh_token'
function forceToLogin() {
  if (redirecting) return
  redirecting = true
  localStorage.removeItem('ai_token')
  localStorage.removeItem('ai_user')
  localStorage.removeItem(REFRESH_KEY)
  if (!window.location.pathname.includes('/login')) {
    window.location.href = '/login'
  }
  // 给后续可能仍在路上的请求留一次机会重置
  setTimeout(() => { redirecting = false }, 500)
}

// 单飞刷新：并发 401 共用同一次刷新
let refreshPromise = null
export function refreshAccessToken() {
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
      localStorage.setItem('ai_token', data.data.token)
      localStorage.setItem(REFRESH_KEY, data.data.refreshToken)
      return data.data.token
    })().finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

// 请求拦截器：自动携带 Token
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('ai_token')
    if (token) {
      config.headers.Authorization = 'Bearer ' + token
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器
api.interceptors.response.use(
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
        return refreshAccessToken()
          .then(() => api(cfg))
          .catch(() => {
            forceToLogin()
            return Promise.reject(new Error('登录已过期，请重新登录'))
          })
      }
      if (status === 401) {
        forceToLogin()
      }
      const msg = status === 429 ? '操作过于频繁，请稍后再试' : ((data && data.msg) || `请求错误 (${status})`)
      return Promise.reject(new Error(msg))
    }
    return Promise.reject(new Error('网络异常或服务不可用'))
  }
)

export default api
export { forceToLogin }