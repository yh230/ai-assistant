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
 * - 跨 tab 串行：优先 navigator.locks，降级为 localStorage 租约锁（写入 owner + 随机沉降后回读确认）
 * - 锁内重读 token：若其它 tab 已完成刷新（当前 token 与 staleToken 不同），直接复用新 token
 * - 刷新请求带 15s 超时，避免某个 tab 持锁后长时间挂起拖死其它 tab
 * - 代际校验：登出（本地 forceLogin 或其它 tab 广播的 logout）会推进 generation，
 *   刷新请求返回后若代际已变化，则丢弃刷新结果、不写入 token
 * - 网络异常 / 5xx 不登出（抛 NetworkError），仅刷新接口明确返回 400/401/403，
 *   或 HTTP 200 但业务码 401 / 「登录已过期/无效/过期」文案时才登出
 * - 已取消的请求（AbortError / ERR_CANCELED）原样透传，不转换成 NetworkError
 * - 登出通过 BroadcastChannel 同步到其它 tab
 * - axios 拦截器对 401 只重试一次
 */

const BASE_URL = '/api'
const REFRESH_PATH = '/api/refresh'
const TOKEN_TYPE = 'Bearer'

const LOCK_WAIT = 10000
const LOCK_POLL = 50
const LOCK_SETTLE_MIN = 40
const LOCK_SETTLE_MAX = 80
const REFRESH_TIMEOUT = 15000
// 租约必须长于「刷新超时 + 观测到的沉降/轮询窗口」，否则慢刷新会在租约过期后被其它 tab 抢占，
// 导致双写 token / 令牌轮换冲突。30s = 15s 刷新超时 + 15s 余量（沉降、写盘、事件循环延迟）。
const LOCK_TTL = 30000

// 只匹配 `/api/refresh` 本身（可带 query），不匹配 `/refresh/revoke` 之类的子路径
const REFRESH_URL_RE = /\/refresh(\?|$)/

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
  return Math.random().toString(36).slice(2) + Date.now().toString(36) + Math.random().toString(36).slice(2)
}

function readLease(key) {
  try {
    return JSON.parse(localStorage.getItem(key) || 'null')
  } catch {
    return null
  }
}

/**
 * localStorage 租约锁（尽力而为的近似互斥）。
 *
 * 写入后并不立即认为成功：先等 40–80ms 随机沉降窗口，让并发写入互相覆盖并最终稳定，
 * 再回读确认 owner 仍是自己，才认为持有锁。两个 tab 同时写入时，最后写入者胜出，
 * 先写入者回读会看到别人的 owner 而放弃，避免“写完立刻回读总是成功”的双赢。
 *
 * 超时未获取到锁时抛 NetworkError，调用方绝不能在没有锁的情况下执行刷新。
 */
async function acquireLease(key, owner) {
  const deadline = Date.now() + LOCK_WAIT
  while (Date.now() < deadline) {
    const lease = readLease(key)
    const now = Date.now()
    if (!lease || !lease.expires || lease.expires <= now) {
      const expires = now + LOCK_TTL
      localStorage.setItem(key, JSON.stringify({ owner, expires }))
      await sleep(LOCK_SETTLE_MIN + Math.random() * (LOCK_SETTLE_MAX - LOCK_SETTLE_MIN))
      const check = readLease(key)
      if (check && check.owner === owner && check.expires > Date.now()) {
        return true
      }
    }
    await sleep(LOCK_POLL)
  }
  throw new NetworkError('刷新令牌等待超时')
}

async function withRefreshLock(name, fn) {
  if (typeof navigator !== 'undefined' && navigator.locks && navigator.locks.request) {
    return navigator.locks.request(name, { mode: 'exclusive' }, () => fn())
  }
  const key = name + ':lease'
  const owner = randomId()
  await acquireLease(key, owner)
  try {
    return await fn()
  } finally {
    const lease = readLease(key)
    if (lease && lease.owner === owner) {
      localStorage.removeItem(key)
    }
  }
}

function isAuthFailureCode(code) {
  return code === 400 || code === 401 || code === 403
}

// 刷新接口可能以 HTTP 200 + 业务码 500 返回「登录已过期」类文案（旧版后端），
// 只有同时命中「登录态主体（登录/令牌/凭证/认证）」与「失效语义（过期/失效/无效）」
// 的文案才允许登出，避免把「参数无效」这类普通业务错误误判为过期。
const AUTH_SUBJECT_RE = /登录|令牌|凭证|认证|refresh/i
const AUTH_INVALID_RE = /过期|失效|无效/

function isAuthExpiryMessage(msg) {
  return typeof msg === 'string' && AUTH_SUBJECT_RE.test(msg) && AUTH_INVALID_RE.test(msg)
}

function isRefreshUrl(url) {
  return REFRESH_URL_RE.test(String(url || ''))
}

function isAbortError(err) {
  if (!err) return false
  return err.name === 'AbortError' || err.name === 'TimeoutError' || err.code === 'ERR_CANCELED'
}

/**
 * 登出已经发生（本地 forceLogin 或其它 tab 广播）导致刷新结果作废时抛出的错误。
 * 带 superseded 标记：调用方只做 reject，不再重复 forceLogin，避免登出级联广播。
 */
function supersededError() {
  const err = new AuthExpiredError('登录态已变更，本次刷新结果已丢弃')
  err.superseded = true
  return err
}

/**
 * 生成刷新请求的超时信号，返回 { signal, cleanup, didTimeout }。
 * 优先 AbortSignal.timeout；不支持时退化为 AbortController + setTimeout。
 */
function createTimeoutSignal(ms) {
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
    const signal = AbortSignal.timeout(ms)
    return {
      signal,
      cleanup: () => {},
      // AbortSignal.timeout 的中止原因为 TimeoutError；其它原因的 abort 不计为超时
      didTimeout: () => !!signal.reason && signal.reason.name === 'TimeoutError'
    }
  }
  if (typeof AbortController !== 'undefined') {
    const controller = new AbortController()
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, ms)
    return {
      signal: controller.signal,
      cleanup: () => clearTimeout(timer),
      didTimeout: () => timedOut
    }
  }
  return { signal: undefined, cleanup: () => {}, didTimeout: () => false }
}

export function createAuthHttp({ tokenKey, refreshKey, userKey, loginPath }) {
  const lockName = `auth-refresh:${tokenKey}`
  const channelName = `auth-sync:${tokenKey}`

  let redirecting = false
  let refreshPromise = null
  // 认证代际：forceLogin 与远端 logout 都会自增；刷新请求跨代返回时结果作废
  let generation = 0

  let channel = null
  if (typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined') {
    try {
      channel = new BroadcastChannel(channelName)
      channel.onmessage = (event) => {
        const data = event && event.data
        if (!data || data.type !== 'logout') return
        generation += 1
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
   * 强制登出：推进代际、清理本地认证态，通知其它 tab，并跳转登录页（并发调用只跳一次）。
   */
  function forceLogin() {
    generation += 1
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
      // 锁内重读：其它 tab 可能已完成刷新，直接复用新 token，避免重复刷新/令牌轮换冲突
      const current = getToken()
      if (staleToken && current && current !== staleToken) {
        return current
      }

      const refreshToken = refreshKey ? localStorage.getItem(refreshKey) : null
      if (!refreshToken) {
        throw expire('无刷新令牌')
      }

      // 发起请求前记录代际，返回后若已登出则丢弃结果
      const requestGeneration = generation
      const { signal, cleanup, didTimeout } = createTimeoutSignal(REFRESH_TIMEOUT)

      let res
      try {
        res = await fetch(REFRESH_PATH, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken }),
          signal
        })
      } catch (err) {
        // 我们自己的超时中止视为网络层失败；外部取消的 AbortError 原样透传
        if (isAbortError(err)) {
          if (didTimeout()) {
            throw new NetworkError('刷新登录态失败：请求超时')
          }
          throw err
        }
        // 网络层失败不登出，保留登录态等待重试
        throw new NetworkError('刷新登录态失败：网络异常')
      } finally {
        cleanup()
      }

      if (generation !== requestGeneration) {
        throw supersededError()
      }

      if (isAuthFailureCode(res.status)) {
        throw expire('登录已过期')
      }
      if (!res.ok) {
        throw new NetworkError(`刷新登录态失败 (${res.status})`)
      }

      const data = await res.json().catch(() => null)
      // 业务码 401（后端过期码）/400/403 视为登录态失效，登出并抛 AuthExpiredError
      if (data && isAuthFailureCode(data.code)) {
        throw expire(data.msg || '登录已过期')
      }
      // 兼容旧后端：HTTP 200 + 业务码 >=500 但文案明确表示登录已过期/无效
      if (data && typeof data.code === 'number' && data.code >= 500 && isAuthExpiryMessage(data.msg)) {
        throw expire(data.msg)
      }
      if (!data || data.code !== 200 || !data.data || !data.data.token) {
        throw new NetworkError((data && data.msg) || '刷新登录态失败')
      }

      // 写盘前再次校验代际：解析响应期间可能收到远端登出
      if (generation !== requestGeneration) {
        throw supersededError()
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
   * @param {string} [staleToken] 触发本次刷新的旧 token；锁内若发现当前 token 已是新值则直接复用。
   *                             省略时保持旧行为（不做复用判断）。
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

  // 请求拦截器：自动携带 Token，并记录本次请求实际使用的 token（供 401 时判断是否已被刷新）
  http.interceptors.request.use(
    (config) => {
      const token = getToken()
      config._accessToken = token || null
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
        // 主动取消的请求原样透传，不伪装成网络异常
        if (isAbortError(error)) {
          return Promise.reject(error)
        }
        const timedOut = error.code === 'ECONNABORTED'
        return Promise.reject(new NetworkError(timedOut ? '请求超时，请稍后重试' : undefined))
      }

      const { status, data } = error.response
      const cfg = error.config || {}
      const isRefreshCall = isRefreshUrl(cfg.url) || isRefreshUrl(cfg.baseURL)

      if (status === 401 && !isRefreshCall && !cfg._retry) {
        cfg._retry = true
        // 使用该请求实际携带的 token，便于锁内判断其它 tab 是否已完成刷新
        const staleToken = cfg._accessToken !== undefined ? cfg._accessToken : getToken()
        try {
          await refreshAccessToken(staleToken)
          return http(cfg)
        } catch (err) {
          if (err instanceof AuthExpiredError) {
            // 登出已发生（本地或其它 tab）导致的刷新作废：只 reject，避免再次广播登出级联
            if (!err.superseded) {
              forceLogin()
            }
            return Promise.reject(err)
          }
          if (isAbortError(err)) {
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
