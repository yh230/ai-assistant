import api from './index'
import { forceToLogin, refreshAccessToken } from './index'

/**
 * 发送聊天消息（非流式，兼容旧接口）
 */
export function sendMessage(data) {
  return api.post('/chat/send', data)
}

async function fetchWithRefresh(url, options) {
  let response = await fetch(url, options)
  if (response.status === 401) {
    const token = await refreshAccessToken()
    response = await fetch(url, { ...options, headers: { ...options.headers, Authorization: 'Bearer ' + token } })
  }
  return response
}

/**
 * 流式发送聊天消息（SSE Server-Sent Events）
 * 逐 token 接收回复，通过回调函数实时推送
 *
 * @param {Object} data - { sessionId, message, title }
 * @param {Object} callbacks - { onSession, onToken, onDone, onError }
 * @returns {Promise<void>}
 */
export function sendMessageStream(data, { onSession, onToken, onDone, onError }) {
  const token = localStorage.getItem('ai_token')
  return fetchWithRefresh('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {})
    },
    body: JSON.stringify(data)
  }).then(async (response) => {
    // SSE 不走 axios 拦截器，这里手动处理 401/403
    if (response.status === 401 || response.status === 403) {
      forceToLogin()
      throw new Error(`HTTP ${response.status}: 未登录或会话已失效`)
    }
    if (!response.ok) {
      const text = await response.text()
      throw new Error(`HTTP ${response.status}: ${text}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      // SSE 事件以 \n\n 分隔
      const parts = buffer.split('\n\n')
      buffer = parts.pop() // 保留不完整的部分

      for (const part of parts) {
        if (!part.trim()) continue

        const lines = part.split('\n')
        let eventType = 'message'
        let dataStr = ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventType = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataStr += line.slice(5).replace(/^\s/, '')
          }
        }

        // 分发事件
        if (eventType === 'session') {
          try { onSession?.(JSON.parse(dataStr)) } catch { /* ignore */ }
        } else if (eventType === 'token') {
          onToken?.(dataStr)
        } else if (eventType === 'done') {
          try { onDone?.(JSON.parse(dataStr)) } catch { onDone?.({}) }
        } else if (eventType === 'error') {
          try {
            const err = JSON.parse(dataStr)
            onError?.(err.message || dataStr)
          } catch { onError?.(dataStr) }
        }
      }
    }
  })
}

/**
 * 获取会话消息历史
 */
export function getChatHistory(sessionId) {
  return api.get(`/chat/history/${sessionId}`)
}

/**
 * 获取所有会话列表
 */
export function getSessions() {
  return api.get('/chat/sessions')
}

/**
 * 删除会话
 */
export function deleteSession(sessionId) {
  return api.delete(`/chat/session/${sessionId}`)
}

/**
 * 获取可选员工角色（来自数据库）
 */
export function getRoles() {
  return api.get('/chat/roles')
}

/**
 * 获取可选模型列表（来自后端配置 ai.assistant.models，含可用状态）
 */
export function getModels() {
  return api.get('/chat/models')
}
