import { reactive } from 'vue'

const KEY_TOKEN = 'patient_token'
const KEY_USER = 'patient_user'

/**
 * 患者登录态（独立于员工 auth store，localStorage key 亦独立）
 */
export const patientAuth = reactive({
  token: localStorage.getItem(KEY_TOKEN) || '',
  user: JSON.parse(localStorage.getItem(KEY_USER) || 'null')
})

/**
 * 写入患者登录态（token + 简要用户信息）
 */
export function setPatientAuth(token, user) {
  patientAuth.token = token
  patientAuth.user = user
  localStorage.setItem(KEY_TOKEN, token)
  localStorage.setItem(KEY_USER, JSON.stringify(user))
}

/**
 * 清除患者登录态
 */
export function clearPatientAuth() {
  patientAuth.token = ''
  patientAuth.user = null
  localStorage.removeItem(KEY_TOKEN)
  localStorage.removeItem(KEY_USER)
}
