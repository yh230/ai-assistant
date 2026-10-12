import api from './index'

/**
 * 认证相关接口。
 * 注意：后端 /api/login 仅返回 { token, tokenType }，登录后需再调 /api/getInfo 获取用户信息。
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
