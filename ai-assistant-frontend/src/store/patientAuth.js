import { reactive } from 'vue'

const KEY_TOKEN = 'patient_token'
const KEY_USER = 'patient_user'
const KEY_REFRESH = 'patient_refresh_token'

/**
 * 患者登录态（独立于员工 auth store，localStorage key 亦独立）
 */
export const patientAuth = reactive({
  token: localStorage.getItem(KEY_TOKEN) || '',
  user: JSON.parse(localStorage.getItem(KEY_USER) || 'null'),
  refreshToken: localStorage.getItem(KEY_REFRESH) || ''
})

/**
 * 写入患者登录态（token + 简要用户信息）
 */
export function setPatientAuth(token, user, refreshToken) {
  patientAuth.token = token
  patientAuth.user = user
  patientAuth.refreshToken = refreshToken || ''
  localStorage.setItem(KEY_TOKEN, token)
  localStorage.setItem(KEY_USER, JSON.stringify(user))
  if (refreshToken) localStorage.setItem(KEY_REFRESH, refreshToken)
  else localStorage.removeItem(KEY_REFRESH)
}

/**
 * 清除患者登录态
 */
export function clearPatientAuth() {
  patientAuth.token = ''
  patientAuth.user = null
  patientAuth.refreshToken = ''
  localStorage.removeItem(KEY_TOKEN)
  localStorage.removeItem(KEY_USER)
  localStorage.removeItem(KEY_REFRESH)
}
