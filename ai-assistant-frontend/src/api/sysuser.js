import api from './index'

/**
 * 员工（系统用户）管理接口。
 */

export function pageUsers(params) {
  return api.get('/system/user/page', { params })
}

export function createUser(data) {
  return api.post('/system/user', data)
}

export function updateUser(id, data) {
  return api.put(`/system/user/${id}`, data)
}

export function deleteUser(id) {
  return api.delete(`/system/user/${id}`)
}

export function changeStatus(id, status) {
  return api.post(`/system/user/${id}/status`, null, { params: { status } })
}

export function resetPassword(id, newPassword) {
  return api.post(`/system/user/${id}/reset-password`, newPassword ? { newPassword } : {})
}

export function kickoutUser(id) {
  return api.post(`/system/user/${id}/kickout`)
}
