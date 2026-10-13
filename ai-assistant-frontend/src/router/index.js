import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layout/MainLayout.vue'
import { getInfo } from '../api/auth'
import { useAuth } from '../store/auth'
import { PERMS } from '../utils/perm'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/patient/login',
    name: 'PatientLogin',
    component: () => import('../views/patient/PatientLoginView.vue'),
    meta: { public: true, title: '患者登录' }
  },
  {
    path: '/patient/chat',
    name: 'PatientChat',
    component: () => import('../views/patient/PatientChatView.vue'),
    meta: { requiresPatient: true, title: 'AI 就诊助手' }
  },
  {
    path: '/',
    component: MainLayout,
    // 未登录用户会被守卫改写到 /login；此处 redirect 仅对已登录生效
    redirect: '/chat',
    children: [
      {
        path: 'chat',
        name: 'Chat',
        component: () => import('../views/ChatView.vue'),
        meta: { title: '智能聊天', requiresAuth: true }
      },
      {
        path: 'weather',
        name: 'Weather',
        component: () => import('../views/WeatherView.vue'),
        meta: { title: '天气查询', requiresAuth: true }
      },
      {
        path: 'roles',
        name: 'ChatRoles',
        component: () => import('../views/RoleManageView.vue'),
        meta: { title: '聊天人设', requiresAuth: true }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('../views/ProfileView.vue'),
        meta: { title: '个人信息', requiresAuth: true }
      },
      {
        path: 'system/users',
        name: 'UserManage',
        component: () => import('../views/UserManageView.vue'),
        meta: { title: '用户管理', requiresAuth: true, perm: PERMS.SYSTEM_USER_LIST }
      },
      {
        path: 'system/roles',
        name: 'PermissionRole',
        component: () => import('../views/PermissionRoleView.vue'),
        meta: { title: '角色权限', requiresAuth: true, perm: PERMS.SYSTEM_ROLE_LIST }
      },
      {
        path: 'system/menus',
        name: 'MenuManage',
        component: () => import('../views/MenuManageView.vue'),
        meta: { title: '菜单管理', requiresAuth: true, perm: PERMS.SYSTEM_MENU_LIST }
      },
      // ===== 医院科室人员 & 出勤模块（数据权限：后端按登录人自动过滤）=====
      {
        path: 'hospital/info',
        name: 'HospitalManage',
        component: () => import('../views/HospitalManageView.vue'),
        meta: { title: '医院管理', requiresAuth: true, perm: PERMS.HOSPITAL_MANAGE }
      },
      {
        path: 'hospital/dept',
        name: 'DepartmentManage',
        component: () => import('../views/DepartmentManageView.vue'),
        meta: { title: '科室管理', requiresAuth: true, perm: PERMS.HOSPITAL_MANAGE }
      },
      {
        path: 'hospital/employees',
        name: 'HospitalEmployee',
        component: () => import('../views/HospitalEmployeeView.vue'),
        meta: { title: '员工管理', requiresAuth: true, perm: PERMS.HOSPITAL_EMPLOYEE_LIST }
      },
      {
        path: 'hospital/attendance',
        name: 'AttendanceManage',
        component: () => import('../views/AttendanceManageView.vue'),
        // 院长/主任用 list，普通员工用 self，路由守卫按 OR 判定
        meta: { title: '出勤管理', requiresAuth: true, perms: [PERMS.HOSPITAL_ATTENDANCE_LIST, PERMS.HOSPITAL_ATTENDANCE_SELF] }
      },
      {
        path: 'hospital/stats',
        name: 'AttendanceStats',
        component: () => import('../views/AttendanceStatsView.vue'),
        meta: { title: '出勤统计', requiresAuth: true, perm: PERMS.HOSPITAL_STATS_VIEW }
      },
      // ===== 门诊挂号取号模块（数据权限：后端按登录人自动过滤；复用人设系统角色）=====
      {
        path: 'registration/schedule',
        name: 'RegistrationSchedule',
        component: () => import('../views/ScheduleManageView.vue'),
        meta: { title: '排班与号源', requiresAuth: true, perm: PERMS.REGISTRATION_SCHEDULE_MANAGE }
      },
      {
        path: 'registration/records',
        name: 'RegistrationRecord',
        component: () => import('../views/RegistrationRecordView.vue'),
        meta: { title: '挂号记录', requiresAuth: true, perm: PERMS.REGISTRATION_REGISTER_LIST }
      },
      {
        path: 'registration/stats',
        name: 'RegistrationStats',
        component: () => import('../views/RegistrationStatsView.vue'),
        meta: { title: '挂号统计', requiresAuth: true, perm: PERMS.REGISTRATION_STATS_VIEW }
      },
      // ===== AI 助手（知识库 / 问诊记录；管理接口按医院快照鉴权，不加权限码）=====
      {
        path: 'admin/knowledge',
        name: 'KnowledgeManage',
        component: () => import('../views/knowledge/KnowledgeManageView.vue'),
        meta: { title: '知识库管理', requiresAuth: true }
      },
      {
        path: 'admin/triage-records',
        name: 'AiTriageRecords',
        component: () => import('../views/chat/AiTriageRecordView.vue'),
        meta: { title: 'AI 问诊记录', requiresAuth: true }
      },
      // 患者线上预约 / 查询（公开，免登录）
      {
        path: 'registration/patient',
        name: 'PatientAppointment',
        component: () => import('../views/PatientAppointmentView.vue'),
        meta: { title: '患者预约', public: true }
      }
    ]
  },
  // 通配：默认跳 /chat，但会被守卫改写到 /login
  { path: '/:pathMatch(.*)*', redirect: '/chat' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 单一可信的登录校验入口
const authStore = useAuth()

async function ensureAuthed() {
  const token = localStorage.getItem('ai_token')
  if (!token) return false
  // 本次会话已认证则直接返回，避免重复打 getInfo 探活
  if (authStore.authed.value && authStore.userInfo.value) return true
  try {
    await getInfo()
    return true
  } catch {
    // 401 时拦截器已清 token 并跳转；这里也清一份兜底
    localStorage.removeItem('ai_token')
    localStorage.removeItem('ai_user')
    return false
  }
}

router.beforeEach(async (to, from, next) => {
  const hasToken = !!localStorage.getItem('ai_token')

  // 1. 公开页：已登录访问 /login → 跳 /chat
  if (to.meta.public) {
    if (to.path === '/login' && hasToken) {
      // 验证一下 token 是否仍有效，无效则清并留在 /login
      const ok = await ensureAuthed()
      next(ok ? '/chat' : true)
      return
    }
    next()
    return
  }

  // 2. 患者页：校验患者 token，缺失跳患者登录
  if (to.meta.requiresPatient) {
    if (!localStorage.getItem('patient_token')) {
      next({ path: '/patient/login' })
      return
    }
    next()
    return
  }

  // 3. 其余全部路由：未登录直接跳 /login
  if (!hasToken) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  // 4. 有 token：已认证则直接放行，不重复打 getInfo；
  //    会话失效由 axios 响应拦截器(401 -> forceToLogin)兜底清 token 跳登录。
  //    （仅首次进入/刷新页面后未认证时，做一次 loadUserInfo 拉用户信息）
  if (!authStore.authed.value) {
    try {
      await authStore.loadUserInfo()
      authStore.setAuthed(true)
    } catch {
      // 拦截器通常已清 token；此处兜底
      localStorage.removeItem('ai_token')
      localStorage.removeItem('ai_user')
      authStore.clear()
      if (!window.location.pathname.includes('/login')) {
        window.location.href = '/login'
      }
      return
    }
  }

  // 5. 权限校验（支持单权限 perm 或 多权限 perms 取「或」）
  const required = to.meta.perm || to.meta.perms
  if (required) {
    let perms = []
    try {
      perms = JSON.parse(localStorage.getItem('ai_user') || '{}').permissions || []
    } catch {
      perms = []
    }
    const list = Array.isArray(required) ? required : [required]
    if (!list.some(p => perms.includes(p))) {
      next('/chat')
      return
    }
  }
  next()
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - AI 助手` : 'AI 助手'
})

export default router
export { ensureAuthed }