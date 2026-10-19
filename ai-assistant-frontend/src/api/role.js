import api from './index'

/**
 * 角色管理相关接口（后台维护，全量含禁用状态）
 */

/**
 * 获取角色列表（按角色域；患者域需带医院 ID，已绑院账号由服务端自动使用所属医院）
 * @param {String} scope 角色域 STAFF/PATIENT
 * @param {Number|null} hospitalId 医院 ID（仅未绑院的平台管理员需要显式传入）
 */
export function getAdminRoles(scope = 'STAFF', hospitalId = null) {
  const params = { scope }
  if (hospitalId) params.hospitalId = hospitalId
  return api.get('/roles/admin', { params })
}

/**
 * 新增角色
 * @param {Object} data 角色表单 { id, name, avatar, description, systemPrompt, sortOrder, enabled, scope, sessionType }
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
