import api from './index'

/**
 * 认证相关接口。
 * 后端 /api/login 返回 { token, refreshToken, tokenType }，登录后需再调 /api/getInfo 获取用户信息。
 */

export function login(data) {
  return api.post('/login', data)
}

export function logout() {
  return api.post('/logout')
}

export function getInfo() {
  return api.get('/getInfo')
}

export function getProfile() {
  return api.get('/profile')
}

export function updateProfile(data) {
  return api.post('/profile', data)
}

export function changePassword(data) {
  return api.post('/profile/password', data)
}

export function revokeRefresh(refreshToken) {
  return api.post('/refresh/revoke', { refreshToken })
}
