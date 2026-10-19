import { parseSseEvents } from './sseParse.js'

/**
 * 统一的 SSE 流式请求封装。
 *
 * - 401 时调用 refresh() 续期并重试一次（仅一次）
 * - idleTimeoutMs 内无新数据则中止请求
 * - 支持外部 signal 取消（停止生成）
 * - 流结束未收到 done 事件时补发 done 兜底
 *
 * @param {string} url
 * @param {Object} options
 * @param {string} [options.method='POST']
 * @param {BodyInit} [options.body]
 * @param {Object} [options.headers]
 * @param {() => string|null} [options.getToken]
 * @param {() => Promise<string>} [options.refresh]
 * @param {number} [options.idleTimeoutMs=120000]
 * @param {(event: string, data: string) => void} [options.onEvent]
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
    const request = token => fetch(url, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
        ...headers
      },
      body,
      signal: controller.signal
    })

    try {
      reset()
      let response = await request(getToken?.())
      if (response.status === 401 && refresh) {
        const token = await refresh()
        response = await request(token)
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
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
        for (const item of parsed.events) {
          if (item.event === 'done') doneSeen = true
          onEvent?.(item.event, item.data)
        }
      }
      if (!doneSeen) onEvent?.('done', '{}')
    } catch (err) {
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
