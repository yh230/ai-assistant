import api from './index'

/**
 * 菜单权限管理接口。
 */

export function allMenus() {
  return api.get('/system/menu/all')
}

export function createMenu(data) {
  return api.post('/system/menu', data)
}

export function updateMenu(id, data) {
  return api.put(`/system/menu/${id}`, data)
}

export function deleteMenu(id) {
  return api.delete(`/system/menu/${id}`)
}
