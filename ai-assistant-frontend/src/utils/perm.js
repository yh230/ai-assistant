/** 后端权限标识常量（与后端权限串 / 菜单播种保持一致） */
export const PERMS = {
  SYSTEM_USER_LIST: 'system:user:list',
  SYSTEM_ROLE_LIST: 'system:role:list',
  SYSTEM_MENU_LIST: 'system:menu:list',
  SYSTEM_OPERLOG_LIST: 'system:operlog:list',
  HOSPITAL_MANAGE: 'hospital:manage',
  HOSPITAL_EMPLOYEE_LIST: 'hospital:employee:list',
  HOSPITAL_ATTENDANCE_LIST: 'hospital:attendance:list',
  HOSPITAL_ATTENDANCE_SELF: 'hospital:attendance:self',
  HOSPITAL_STATS_VIEW: 'hospital:stats:view',
  REGISTRATION_SCHEDULE_MANAGE: 'registration:schedule:manage',
  REGISTRATION_REGISTER_LIST: 'registration:register:list',
  REGISTRATION_STATS_VIEW: 'registration:stats:view',
  REGISTRATION_REGISTER_CREATE: 'registration:register:create', // 挂号-现场挂号
  REGISTRATION_REGISTER_TAKE: 'registration:register:take', // 挂号-取号
  REGISTRATION_REGISTER_REFUND: 'registration:register:refund', // 挂号-退号
  REGISTRATION_REGISTER_TREAT: 'registration:register:treat', // 挂号-就诊
  HOSPITAL_ATTENDANCE_EDIT: 'hospital:attendance:edit', // 出勤-编辑
  AI_ROLE_MANAGE: 'ai:role:manage', // AI 人设管理
}

/**
 * 是否拥有指定权限
 * @param {string[]} permissions 当前用户权限列表
 * @param {string} perm 权限标识
 * @returns {boolean}
 */
export function hasPerm(permissions, perm) {
  return (permissions || []).includes(perm)
}

/**
 * 是否拥有任一权限
 * @param {string[]} permissions 当前用户权限列表
 * @param {string[]} permList 权限标识列表
 * @returns {boolean}
 */
export function hasAnyPerm(permissions, permList) {
  return (permList || []).some((p) => (permissions || []).includes(p))
}
