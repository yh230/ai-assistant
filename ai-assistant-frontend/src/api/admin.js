import api from './index'

/**
 * 患者会话分页（管理端，后端按本院过滤）
 */
export function adminSessions(params) {
  return api.get('/chat/admin/sessions', { params })
}

/**
 * 会话消息详情
 */
export function adminSessionHistory(sessionId) {
  return api.get(`/chat/admin/sessions/${sessionId}/history`)
}

/**
 * 知识库文档列表（仅本院）
 */
export function knowledgeList(params) {
  return api.get('/chat/admin/knowledge', { params })
}

/**
 * 上传知识库文档（md/txt ≤2MB）
 */
export function knowledgeUpload(file) {
  const form = new FormData()
  form.append('file', file)
  return api.post('/chat/admin/knowledge/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 删除知识库文档（同步清理向量分片）
 */
export function knowledgeDelete(id) {
  return api.delete(`/chat/admin/knowledge/${id}`)
}
