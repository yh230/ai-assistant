import api from './index'

/**
 * 操作日志分页（管理端）
 */
export function operLogPage(params) {
  return api.get('/system/oper-log/page', { params })
}
