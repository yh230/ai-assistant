import api from './index'

/**
 * RBAC 角色管理接口（区别于聊天人设的 role.js）。
 */

export function pageRoles(params) {
  return api.get('/system/role/page', { params })
}

export function createRole(data) {
  return api.post('/system/role', data)
}

export function updateRole(id, data) {
  return api.put(`/system/role/${id}`, data)
}

export function deleteRole(id) {
  return api.delete(`/system/role/${id}`)
}

export function assignRoleMenus(id, menuIds) {
  return api.post(`/system/role/${id}/menus`, { menuIds })
}

export function getRoleMenus(id) {
  return api.get(`/system/role/${id}/menus`)
}

// 用于用户表单的角色下拉（一次拉全量）
export function listAllRoles() {
  return pageRoles({ pageNum: 1, pageSize: 1000 })
}
