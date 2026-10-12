<template>
  <div class="app-layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <div class="logo">
          <span class="logo-icon">伴</span>
        </div>
        <span class="app-title">聊天小伙伴</span>
      </div>

      <nav class="sidebar-nav">
        <router-link to="/chat" class="nav-item" active-class="active">
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>
          </svg>
          <span>智能聊天</span>
        </router-link>

        <router-link to="/weather" class="nav-item" active-class="active">
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/>
          </svg>
          <span>天气查询</span>
        </router-link>

        <router-link to="/roles" class="nav-item" active-class="active">
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
            <circle cx="9" cy="7" r="4"/>
            <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>
          </svg>
          <span>聊天人设</span>
        </router-link>

        <!-- 系统管理（登录后按权限渲染，变更点：由仅判断 token 改为按 userInfo.permissions 过滤） -->
        <template v-if="token && hasAnySystemPerm">
          <div class="nav-group-title">系统管理</div>
          <router-link v-if="hasPerm(PERMS.SYSTEM_USER_LIST)" to="/system/users" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/>
            </svg>
            <span>用户管理</span>
          </router-link>
          <router-link v-if="hasPerm(PERMS.SYSTEM_ROLE_LIST)" to="/system/roles" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 2 2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/>
            </svg>
            <span>角色权限</span>
          </router-link>
          <router-link v-if="hasPerm(PERMS.SYSTEM_MENU_LIST)" to="/system/menus" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/>
              <line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/>
            </svg>
            <span>菜单管理</span>
          </router-link>
        </template>

        <!-- 医院管理（按数据权限相关功能权限渲染，自动数据过滤由后端完成） -->
        <template v-if="hasAnyHospitalPerm">
          <div class="nav-group-title">医院管理</div>
          <router-link v-if="hasPerm(PERMS.HOSPITAL_MANAGE)" to="/hospital/info" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 21h18M5 21V7l7-4 7 4v14M9 9h.01M9 13h.01M9 17h.01M15 9h.01M15 13h.01M15 17h.01"/>
            </svg>
            <span>医院管理</span>
          </router-link>
          <router-link v-if="hasPerm(PERMS.HOSPITAL_MANAGE)" to="/hospital/dept" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 7v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7M3 7l9-4 9 4M9 12h6"/>
            </svg>
            <span>科室管理</span>
          </router-link>
          <router-link v-if="hasPerm(PERMS.HOSPITAL_EMPLOYEE_LIST)" to="/hospital/employees" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/>
            </svg>
            <span>员工管理</span>
          </router-link>
          <router-link v-if="hasPerm(PERMS.HOSPITAL_ATTENDANCE_LIST) || hasPerm(PERMS.HOSPITAL_ATTENDANCE_SELF)"
                       to="/hospital/attendance" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="4" width="18" height="18" rx="2"/><path d="M16 2v4M8 2v4M3 10h18"/>
            </svg>
            <span>出勤管理</span>
          </router-link>
          <router-link v-if="hasPerm(PERMS.HOSPITAL_STATS_VIEW)" to="/hospital/stats" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 3v18h18M7 16l4-5 4 3 5-7"/>
            </svg>
            <span>出勤统计</span>
          </router-link>
        </template>

        <!-- 门诊挂号（复用人设系统角色，按数据权限相关功能权限渲染，后端自动过滤） -->
        <template v-if="hasAnyRegistrationPerm">
          <div class="nav-group-title">门诊挂号</div>
          <router-link v-if="hasPerm(PERMS.REGISTRATION_SCHEDULE_MANAGE)" to="/registration/schedule" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="4" width="18" height="18" rx="2"/><path d="M16 2v4M8 2v4M3 10h18M8 14h.01M12 14h.01M16 14h.01M8 18h.01M12 18h.01M16 18h.01"/>
            </svg>
            <span>排班与号源</span>
          </router-link>
          <router-link v-if="hasPerm(PERMS.REGISTRATION_REGISTER_LIST)" to="/registration/records" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M9 13h6M9 17h6"/>
            </svg>
            <span>挂号记录</span>
          </router-link>
          <router-link v-if="hasPerm(PERMS.REGISTRATION_STATS_VIEW)" to="/registration/stats" class="nav-item sub" active-class="active">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 3v18h18M7 16l4-5 4 3 5-7"/>
            </svg>
            <span>挂号统计</span>
          </router-link>
        </template>
      </nav>

      <!-- 底部用户信息 + 登出 -->
      <div class="sidebar-footer">
        <template v-if="userInfo">
          <div class="user-box">
            <div class="user-meta">
              <span class="user-name">{{ userInfo.name || userInfo.username }}</span>
              <span class="user-role">{{ (userInfo.roles || []).join(' / ') || '游客' }}</span>
            </div>
            <div class="user-actions">
              <router-link to="/profile" class="profile-link" title="个人信息">设置</router-link>
              <button class="logout-btn" @click="handleLogout" title="退出登录">退出</button>
            </div>
          </div>
        </template>
        <router-link v-else to="/login" class="login-link">登录</router-link>
      </div>
    </aside>

    <!-- 主内容区 -->
    <main class="main-content">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../store/auth'
import { logout } from '../api/auth'
import { confirm as showConfirm } from '../utils/message'
import { PERMS, hasPerm as checkPerm, hasAnyPerm as checkAnyPerm } from '../utils/perm'

const router = useRouter()
const { token, userInfo, clear } = useAuth()

// 变更点：权限判断收敛到 utils/perm（与后端 @SaCheckPermission 的权限串一致）
const hasPerm = (perm) => checkPerm(userInfo.value?.permissions, perm)
const hasAnyPerm = (permList) => checkAnyPerm(userInfo.value?.permissions, permList)

const hasAnySystemPerm = computed(() =>
  hasAnyPerm([
    PERMS.SYSTEM_USER_LIST,
    PERMS.SYSTEM_ROLE_LIST,
    PERMS.SYSTEM_MENU_LIST
  ])
)

const hasAnyHospitalPerm = computed(() =>
  hasAnyPerm([
    PERMS.HOSPITAL_MANAGE,
    PERMS.HOSPITAL_EMPLOYEE_LIST,
    PERMS.HOSPITAL_ATTENDANCE_LIST,
    PERMS.HOSPITAL_ATTENDANCE_SELF,
    PERMS.HOSPITAL_STATS_VIEW
  ])
)

const hasAnyRegistrationPerm = computed(() =>
  hasAnyPerm([
    PERMS.REGISTRATION_SCHEDULE_MANAGE,
    PERMS.REGISTRATION_REGISTER_LIST,
    PERMS.REGISTRATION_STATS_VIEW
  ])
)

async function handleLogout() {
  // 变更点：退出前弹确认框，选「确定」才真正退出
  const ok = await showConfirm('确定要退出登录吗？', { okText: '退出', cancelText: '取消' })
  if (!ok) return
  try {
    await logout()
  } catch (e) {
    // 忽略服务端错误，本地清除凭证即可
  }
  clear()
  router.replace('/login')
}
</script>

<style scoped>
.app-layout {
  display: flex;
  height: 100%;
}

.sidebar {
  width: 240px;
  background: var(--bg-sidebar);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: var(--transition);
}

.sidebar-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.logo {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--primary), var(--primary-light));
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 16px;
  color: white;
  flex-shrink: 0;
}

.app-title {
  color: white;
  font-size: 16px;
  font-weight: 600;
}

.sidebar-nav {
  flex: 1;
  padding: 12px 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  color: rgba(255, 255, 255, 0.65);
  border-radius: var(--border-radius-sm);
  transition: var(--transition);
  font-size: 14px;
}

.nav-item.sub {
  padding-left: 22px;
  font-size: 13px;
}

.nav-item:hover {
  color: white;
  background: rgba(255, 255, 255, 0.08);
}

.nav-item.active {
  color: white;
  background: rgba(99, 102, 241, 0.3);
}

.nav-icon {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.nav-group-title {
  color: rgba(255, 255, 255, 0.35);
  font-size: 11px;
  letter-spacing: 1px;
  padding: 14px 12px 4px;
}

.sidebar-footer {
  padding: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.user-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.user-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.profile-link {
  padding: 5px 12px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.85);
  border-radius: var(--border-radius-sm);
  cursor: pointer;
  font-size: 12px;
  text-decoration: none;
}

.profile-link:hover {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
}

.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.user-name {
  color: white;
  font-size: 14px;
  font-weight: 600;
}

.user-role {
  color: rgba(255, 255, 255, 0.5);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logout-btn {
  flex-shrink: 0;
  padding: 5px 12px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.85);
  border-radius: var(--border-radius-sm);
  cursor: pointer;
  font-size: 12px;
}

.logout-btn:hover {
  background: rgba(239, 68, 68, 0.25);
  border-color: rgba(239, 68, 68, 0.5);
  color: #fff;
}

.login-link {
  display: block;
  text-align: center;
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
  padding: 8px;
  border-radius: var(--border-radius-sm);
  border: 1px solid rgba(255, 255, 255, 0.15);
}

.login-link:hover {
  background: rgba(255, 255, 255, 0.08);
  color: white;
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}
</style>
