import api from './index'

/**
 * 门诊挂号取号模块接口。
 * 注意：所有列表/统计均按登录人数据权限自动过滤，无需前端传权限参数。
 * 患者相关接口（appointments/*）为公开接口，免登录。
 */

// ===================== 排班 / 号源 =====================
export function pageSchedules(params) {
  return api.get('/registration/schedule/page', { params })
}
export function saveSchedule(data) {
  return data.id
    ? api.put(`/registration/schedule/${data.id}`, data)
    : api.post('/registration/schedule', data)
}
export function deleteSchedule(id) {
  return api.delete(`/registration/schedule/${id}`)
}
export function generateSources(scheduleId) {
  return api.post(`/registration/schedule/${scheduleId}/generate`)
}
export function listNumberSourcePools(params) {
  return api.get('/registration/source/pools', { params })
}
// 公开：患者预约选号用，有出诊排班的医生列表
export function listPublicSchedules(params) {
  return api.get('/registration/schedule/public/schedules', { params })
}

// ===================== 挂号记录 =====================
export function pageRegistrations(params) {
  return api.get('/registration/records', { params })
}
export function getRegistration(id) {
  return api.get(`/registration/records/${id}`)
}
export function walkInRegister(data) {
  return api.post('/registration/records/walk-in', data)
}
export function takeNumber(id) {
  return api.post(`/registration/records/${id}/take`)
}
export function refundRegistration(id, data) {
  return api.post(`/registration/records/${id}/refund`, data || {})
}
export function treatRegistration(id) {
  return api.post(`/registration/records/${id}/treat`)
}
export function registrationStats(dimension) {
  return api.get('/registration/stats', { params: { dimension } })
}

// ===================== 公开（患者 / 自助机查询本人） =====================
export function createAppointment(data) {
  return api.post('/registration/appointments', data)
}
export function queryAppointments(params) {
  return api.get('/registration/appointments/query', { params })
}
export function confirmPay(id, data) {
  return api.post(`/registration/appointments/${id}/confirm-pay`, data)
}
export function cancelAppointment(id, data) {
  return api.post(`/registration/appointments/${id}/cancel`, data)
}
