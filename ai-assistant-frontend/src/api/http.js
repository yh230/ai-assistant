import axios from 'axios'

/**
 * 统一认证 HTTP 客户端工厂
 *
 * 员工端与患者端共用同一套 401 静默续期逻辑，只通过 token/user 存储键与登录路径区分：
 *   createAuthHttp({ tokenKey, refreshKey, userKey, loginPath })
 *     -> { http, refreshAccessToken, forceLogin }
 *
 * 关键行为：
 * - 单飞刷新：同一 tab 并发 401 共用同一次刷新请求
 * - 跨 tab 串行：优先 navigator.locks，降级为 localStorage 租约锁
 * - 锁内重读 token：若其它 tab 已完成刷新，直接复用新 token，避免重复刷新/令牌轮换冲突
 * - 网络异常 / 5xx 不登出（抛 NetworkError），仅刷新接口明确返回 400/401/403 才登出
 * - 登出通过 BroadcastChannel 同步到其它 tab
 * - axios 拦截器对 401 只重试一次
 */

const BASE_URL = '/api'
const REFRESH_PATH = '/api/refresh'
const TOKEN_TYPE = 'Bearer'

const LOCK_TTL = 8000
const LOCK_WAIT = 10000
const LOCK_POLL = 50

export class AuthExpiredError extends Error {
  constructor(message = '登录已过期，请重新登录') {
    super(message)
    this.name = 'AuthExpiredError'
  }
}

export class NetworkError extends Error {
  constructor(message = '网络异常或服务不可用') {
    super(message)
    this.name = 'NetworkError'
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function randomId() {
  return Math.random().toString(36).slice(2) + Date.now().toString(36)
}

function readLease(key) {
  try {
    return JSON.parse(localStorage.getItem(key) || 'null')
  } catch {
    return null
  }
}

/**
 * localStorage 租约锁：获取成功返回 owner，失败（超时）返回 null。
 * 租约带过期时间，持有者崩溃后可自动被抢占。
 */
async function acquireLease(key, owner) {
  const deadline = Date.now() + LOCK_WAIT
  while (Date.now() < deadline) {
    const lease = readLease(key)
    if (!lease || !lease.expires || lease.expires <= Date.now()) {
      localStorage.setItem(key, JSON.stringify({ owner, expires: Date.now() + LOCK_TTL }))
      const check = readLease(key)
      if (check && check.owner === owner) return owner
    }
    await sleep(LOCK_POLL)
  }
  return null
}

async function withRefreshLock(name, fn) {
  if (typeof navigator !== 'undefined' && navigator.locks && navigator.locks.request) {
    return navigator.locks.request(name, { mode: 'exclusive' }, () => fn())
  }
  const key = name + ':lease'
  const owner = randomId()
  const acquired = await acquireLease(key, owner)
  try {
    return await fn()
  } finally {
    if (acquired) {
      const lease = readLease(key)
      if (lease && lease.owner === owner) {
        localStorage.removeItem(key)
      }
    }
  }
}

function isAuthFailureCode(code) {
  return code === 400 || code === 401 || code === 403
}

export function createAuthHttp({ tokenKey, refreshKey, userKey, loginPath }) {
  const lockName = `auth-refresh:${tokenKey}`
  const channelName = `auth-sync:${tokenKey}`

  let redirecting = false
  let refreshPromise = null

  let channel = null
  if (typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined') {
    try {
      channel = new BroadcastChannel(channelName)
      channel.onmessage = (event) => {
        const data = event && event.data
        if (!data || data.type !== 'logout') return
        clearAuthStorage()
        redirectToLogin()
      }
    } catch {
      channel = null
    }
  }

  function getToken() {
    return localStorage.getItem(tokenKey)
  }

  function clearAuthStorage() {
    localStorage.removeItem(tokenKey)
    if (refreshKey) localStorage.removeItem(refreshKey)
    if (userKey) localStorage.removeItem(userKey)
  }

  function broadcast(message) {
    if (!channel) return
    try {
      channel.postMessage(message)
    } catch {
      // 通道关闭或序列化失败不影响主流程
    }
  }

  function redirectToLogin() {
    if (typeof window === 'undefined') return
    if (window.location.pathname.includes(loginPath)) return
    window.location.href = loginPath
  }

  /**
   * 强制登出：清理本地认证态，通知其它 tab，并跳转登录页（并发调用只跳一次）。
   */
  function forceLogin() {
    if (redirecting) return
    redirecting = true
    clearAuthStorage()
    broadcast({ type: 'logout' })
    redirectToLogin()
    // 给后续可能仍在路上的请求留一次机会重置
    setTimeout(() => { redirecting = false }, 500)
  }

  // 明确的认证失效（刷新接口 400/401/403 或无刷新令牌）：登出并抛 AuthExpiredError
  function expire(message) {
    forceLogin()
    return new AuthExpiredError(message)
  }

  async function performRefresh(staleToken) {
    return withRefreshLock(lockName, async () => {
      // 锁内重读：其它 tab 可能已完成刷新，直接复用新 token
      const current = getToken()
      if (staleToken && current && current !== staleToken) {
        return current
      }

      const refreshToken = refreshKey ? localStorage.getItem(refreshKey) : null
      if (!refreshToken) {
        throw expire('无刷新令牌')
      }

      let res
      try {
        res = await fetch(REFRESH_PATH, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken })
        })
      } catch {
        // 网络层失败不登出，保留登录态等待重试
        throw new NetworkError('刷新登录态失败：网络异常')
      }

      if (isAuthFailureCode(res.status)) {
        throw expire('登录已过期')
      }
      if (!res.ok) {
        throw new NetworkError(`刷新登录态失败 (${res.status})`)
      }

      const data = await res.json().catch(() => null)
      if (data && isAuthFailureCode(data.code)) {
        throw expire(data.msg || '登录已过期')
      }
      if (!data || data.code !== 200 || !data.data || !data.data.token) {
        throw new NetworkError((data && data.msg) || '刷新登录态失败')
      }

      localStorage.setItem(tokenKey, data.data.token)
      if (refreshKey && data.data.refreshToken) {
        localStorage.setItem(refreshKey, data.data.refreshToken)
      }
      broadcast({ type: 'refresh' })
      return data.data.token
    })
  }

  /**
   * 刷新 access token（tab 内单飞）。
   * @param {string} [staleToken] 触发本次刷新的旧 token，用于锁内判断是否已有新 token
   */
  function refreshAccessToken(staleToken) {
    if (!refreshPromise) {
      refreshPromise = performRefresh(staleToken).finally(() => { refreshPromise = null })
    }
    return refreshPromise
  }

  const http = axios.create({
    baseURL: BASE_URL,
    timeout: 30000,
    headers: {
      'Content-Type': 'application/json'
    }
  })

  // 请求拦截器：自动携带 Token
  http.interceptors.request.use(
    (config) => {
      const token = getToken()
      if (token) {
        config.headers.Authorization = `${TOKEN_TYPE} ${token}`
      }
      return config
    },
    (error) => Promise.reject(error)
  )

  // 响应拦截器：约定 code===200 解包 data；401 单次静默续期
  http.interceptors.response.use(
    (response) => {
      const data = response.data
      if (data && data.code === 200) {
        return data.data
      }
      return Promise.reject(new Error((data && data.msg) || '请求失败'))
    },
    async (error) => {
      if (!error.response) {
        const timedOut = error.code === 'ECONNABORTED'
        return Promise.reject(new NetworkError(timedOut ? '请求超时，请稍后重试' : undefined))
      }

      const { status, data } = error.response
      const cfg = error.config || {}
      const isRefreshCall = (cfg.url || '').includes('/refresh')

      if (status === 401 && !isRefreshCall && !cfg._retry) {
        cfg._retry = true
        const staleToken = getToken()
        try {
          await refreshAccessToken(staleToken)
          return http(cfg)
        } catch (err) {
          if (err instanceof AuthExpiredError) {
            forceLogin()
            return Promise.reject(err)
          }
          // 网络/5xx：不登出，保留登录态交由上层提示重试
          return Promise.reject(err instanceof NetworkError ? err : new NetworkError())
        }
      }

      if (status === 401) {
        forceLogin()
        return Promise.reject(new AuthExpiredError())
      }

      const msg = status === 429
        ? '操作过于频繁，请稍后再试'
        : ((data && data.msg) || `请求错误 (${status})`)
      return Promise.reject(new Error(msg))
    }
  )

  return { http, refreshAccessToken, forceLogin }
}
