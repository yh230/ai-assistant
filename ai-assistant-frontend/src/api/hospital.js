import api from './index'

/**
 * 医院科室人员 & 出勤模块接口。
 * 注意：所有列表/统计均按登录人数据权限自动过滤，无需前端传权限参数。
 */

// ===================== 医院 =====================
export function pageHospitals(params) {
  return api.get('/hospital/info/page', { params })
}
export function listHospitals() {
  return api.get('/hospital/info/all')
}
export function createHospital(data) {
  return api.post('/hospital/info', data)
}
export function updateHospital(id, data) {
  return api.put(`/hospital/info/${id}`, data)
}
export function deleteHospital(id) {
  return api.delete(`/hospital/info/${id}`)
}
export function changeHospitalStatus(id, status) {
  return api.post(`/hospital/info/${id}/status`, null, { params: { status } })
}

// ===================== 科室 =====================
export function pageDepartments(params) {
  return api.get('/hospital/department/page', { params })
}
export function listDepartments(hospitalId) {
  return api.get('/hospital/department/list', { params: hospitalId ? { hospitalId } : undefined })
}
export function createDepartment(data) {
  return api.post('/hospital/department', data)
}
export function updateDepartment(id, data) {
  return api.put(`/hospital/department/${id}`, data)
}
export function deleteDepartment(id) {
  return api.delete(`/hospital/department/${id}`)
}
export function changeDeptStatus(id, status) {
  return api.post(`/hospital/department/${id}/status`, null, { params: { status } })
}

// ===================== 员工 =====================
export function pageEmployees(params) {
  return api.get('/hospital/employee/page', { params })
}
export function createEmployee(data) {
  return api.post('/hospital/employee', data)
}
export function updateEmployee(id, data) {
  return api.put(`/hospital/employee/${id}`, data)
}
export function deleteEmployee(id) {
  return api.delete(`/hospital/employee/${id}`)
}
export function changeEmployeeStatus(id, status) {
  return api.post(`/hospital/employee/${id}/status`, null, { params: { status } })
}
export function resetEmployeePassword(id, newPassword) {
  return api.post(`/hospital/employee/${id}/reset-password`, newPassword ? { newPassword } : {})
}
// 员工表单角色下拉（医院域，无需 system:role:list）
export function listEmployeeRoles() {
  return api.get('/hospital/employee/roles')
}

// ===================== 出勤 =====================
export function pageAttendance(params) {
  return api.get('/hospital/attendance/page', { params })
}
export function createAttendance(data) {
  return api.post('/hospital/attendance', data)
}
export function updateAttendance(id, data) {
  return api.put(`/hospital/attendance/${id}`, data)
}
export function deleteAttendance(id) {
  return api.delete(`/hospital/attendance/${id}`)
}
export function attendanceStats(dimension) {
  return api.get('/hospital/attendance/stats', { params: { dimension } })
}
