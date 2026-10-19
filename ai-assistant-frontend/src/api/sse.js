import { parseSseEvents } from './sseParse.js'

const CONTENT_TYPE = 'Content-Type'

function hasHeader(headers, name) {
  const target = name.toLowerCase()
  return Object.keys(headers || {}).some(key => key.toLowerCase() === target)
}

function discardBody(response) {
  const pending = response?.body?.cancel?.()
  if (pending && typeof pending.catch === 'function') pending.catch(() => {})
}

function isAuthError(err) {
  return !!err && err.name === 'AuthExpiredError'
}

/**
 * 统一的 SSE 流式请求封装。
 *
 * - 401 时调用 refresh(staleToken) 续期并重试一次（仅一次）；刷新期间暂停空闲计时
 * - 403 / 刷新时认证失效 / 续期后仍 401 时调用 onAuthFailure（跳登录、清登录态）
 * - idleTimeoutMs 内无新数据则中止请求
 * - 支持外部 signal 取消（停止生成）
 * - 流结束时冲刷 decoder 与残留缓冲，未终止的尾事件也会派发；仍未收到 done 时补发 done 兜底
 *
 * @param {string} url
 * @param {Object} options
 * @param {string} [options.method='POST']
 * @param {BodyInit} [options.body] 为空时不发送 Content-Type
 * @param {Object} [options.headers]
 * @param {() => string|null} [options.getToken]
 * @param {(staleToken?: string|null) => Promise<string>} [options.refresh]
 * @param {number} [options.idleTimeoutMs=120000]
 * @param {(event: string, data: string) => void} [options.onEvent]
 * @param {() => void} [options.onAuthFailure] 认证失效回调（403 / 刷新失效 / 续期后仍 401）
 * @param {AbortSignal} [options.signal]
 * @returns {{ controller: AbortController, done: Promise<void> }}
 */
export function streamSse(url, {
  method = 'POST',
  body,
  headers,
  getToken,
  refresh,
  idleTimeoutMs = 120000,
  onEvent,
  onAuthFailure,
  signal
} = {}) {
  const controller = new AbortController()
  const abortFromOuter = () => controller.abort()
  if (signal) {
    if (signal.aborted) controller.abort()
    else signal.addEventListener('abort', abortFromOuter, { once: true })
  }

  const done = (async () => {
    let timer
    let timedOut = false
    let reader = null
    const reset = () => {
      clearTimeout(timer)
      timer = setTimeout(() => {
        timedOut = true
        controller.abort()
      }, idleTimeoutMs)
    }
    const request = token => {
      const requestHeaders = { ...headers }
      // 无 body 的请求（如 confirm 续答）不带 Content-Type，避免空 JSON 误判
      if (body !== undefined && body !== null && !hasHeader(requestHeaders, CONTENT_TYPE)) {
        requestHeaders[CONTENT_TYPE] = 'application/json'
      }
      if (token) requestHeaders.Authorization = 'Bearer ' + token
      return fetch(url, {
        method,
        headers: requestHeaders,
        body,
        signal: controller.signal
      })
    }
    const emit = (items) => {
      let sawDone = false
      for (const item of items) {
        if (item.event === 'done') sawDone = true
        onEvent?.(item.event, item.data)
      }
      return sawDone
    }

    try {
      reset()
      const staleToken = getToken?.() ?? null
      let response = await request(staleToken)
      if (response.status === 401 && refresh) {
        // 先丢弃第一次 401 的响应体，避免连接悬挂
        discardBody(response)
        // 刷新期间暂停空闲计时，避免刷新耗时被误判为流超时
        clearTimeout(timer)
        let freshToken
        try {
          freshToken = await refresh(staleToken)
        } catch (err) {
          if (isAuthError(err)) onAuthFailure?.()
          throw err
        }
        reset()
        response = await request(freshToken)
        if (response.status === 401) {
          discardBody(response)
          onAuthFailure?.()
          throw new Error('HTTP 401: 未登录或会话已失效')
        }
      }
      if (response.status === 401 || response.status === 403) {
        discardBody(response)
        onAuthFailure?.()
        throw new Error(`HTTP ${response.status}: 未登录或会话已失效`)
      }
      if (!response.ok) {
        const text = await response.text().catch(() => '')
        const detail = text ? ': ' + text.trim() : ''
        throw new Error(`HTTP ${response.status}${detail}`)
      }
      if (!response.body) throw new Error('响应流不可用')

      reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let doneSeen = false
      while (true) {
        reset()
        const { done: finished, value } = await reader.read()
        if (finished) break
        buffer += decoder.decode(value, { stream: true })
        const parsed = parseSseEvents(buffer)
        buffer = parsed.rest
        if (emit(parsed.events)) doneSeen = true
      }
      // 流结束：冲刷解码器与残留缓冲，未以空行结尾的最后一个事件也要派发
      buffer += decoder.decode()
      const flushed = parseSseEvents(buffer, { flush: true })
      if (emit(flushed.events)) doneSeen = true
      if (!doneSeen) onEvent?.('done', '{}')
    } catch (err) {
      try { await reader?.cancel?.() } catch { /* ignore */ }
      if (timedOut && err && err.name === 'AbortError') {
        throw new Error('响应超时，请重试')
      }
      throw err
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', abortFromOuter)
      try { reader?.releaseLock?.() } catch { /* ignore */ }
    }
  })()

  return { controller, done }
}
