import api from './index'

/**
 * 角色管理相关接口（后台维护，全量含禁用状态）
 */

/**
 * 获取全部角色（含禁用），供管理页面表格展示
 */
export function getAdminRoles() {
  return api.get('/roles/admin')
}

/**
 * 新增角色
 * @param {Object} data 角色表单 { id, name, avatar, description, systemPrompt, sortOrder, enabled }
 */
export function createRole(data) {
  return api.post('/roles/admin', data)
}

/**
 * 更新角色
 * @param {String} id 角色ID
 * @param {Object} data 角色表单
 */
export function updateRole(id, data) {
  return api.put(`/roles/admin/${id}`, data)
}

/**
 * 删除角色
 */
export function deleteRole(id) {
  return api.delete(`/roles/admin/${id}`)
}
