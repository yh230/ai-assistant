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

/**
 * 发送手机号验证码（identity: staff）
 */
export function sendSmsCode(data) {
  return api.post('/login/sms/code', data)
}

/**
 * 手机号验证码登录（identity: staff）
 */
export function loginBySms(data) {
  return api.post('/login/sms', data)
}

/**
 * 创建扫码登录场景（PC 端）
 */
export function qrCreate() {
  return api.post('/qr/create')
}

/**
 * 轮询扫码结果
 */
export function qrPoll(sceneId) {
  return api.get('/qr/poll', { params: { sceneId } })
}

/**
 * 手机端确认扫码登录
 */
export function qrConfirm(data) {
  return api.post('/qr/confirm', data)
}
