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
function forceToLogin() {
  if (redirecting) return
  redirecting = true
  localStorage.removeItem('ai_token')
  localStorage.removeItem('ai_user')
  if (!window.location.pathname.includes('/login')) {
    window.location.href = '/login'
  }
  // 给后续可能仍在路上的请求留一次机会重置
  setTimeout(() => { redirecting = false }, 500)
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
      if (status === 401) {
        forceToLogin()
      }
      const msg = (data && data.msg) || `请求错误 (${status})`
      return Promise.reject(new Error(msg))
    }
    return Promise.reject(new Error('网络异常或服务不可用'))
  }
)

export default api
export { forceToLogin }