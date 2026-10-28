import api, { forceToLogin, refreshAccessToken } from './index'
import { streamSse } from './sse'

/**
 * 流式发送聊天消息（SSE Server-Sent Events）
 * 逐 token 接收回复，通过回调函数实时推送
 *
 * @param {Object} data - { sessionId, message, title }
 * @param {Object} callbacks - { onSession, onToken, onDone, onError }
 * @returns {AbortController} 调用 abort() 可停止生成
 */
export function sendMessageStream(data, { onSession, onToken, onDone, onError } = {}) {
  const { controller, done } = streamSse('/api/chat/stream', {
    body: JSON.stringify(data),
    getToken: () => localStorage.getItem('ai_token'),
    refresh: refreshAccessToken,
    onAuthFailure: forceToLogin,
    onEvent: (event, dataStr) => {
      if (event === 'session') {
        try { onSession?.(JSON.parse(dataStr)) } catch { /* ignore */ }
      } else if (event === 'token') {
        onToken?.(dataStr)
      } else if (event === 'done') {
        try { onDone?.(JSON.parse(dataStr)) } catch { onDone?.({}) }
      } else if (event === 'error') {
        try { onError?.(JSON.parse(dataStr).message || dataStr) } catch { onError?.(dataStr) }
      }
    }
  })
  done.catch(err => {
    if (err.name !== 'AbortError') onError?.(err.message || '网络异常')
  })
  return controller
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
