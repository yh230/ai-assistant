<template>
  <div class="app-layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <div class="logo">
          <span class="logo-icon">医</span>
        </div>
        <span class="app-title">AI 医院助手</span>
      </div>

      <el-menu class="sidebar-nav" :default-active="route.path" router data-testid="app-menu">
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon>
          <span>智能聊天</span>
        </el-menu-item>
        <el-menu-item index="/weather">
          <el-icon><Sunny /></el-icon>
          <span>天气查询</span>
        </el-menu-item>

        <!-- 系统管理（登录后按 userInfo.permissions 过滤渲染） -->
        <el-menu-item-group v-if="token && hasAnySystemPerm">
          <template #title><div class="nav-group-title">系统管理</div></template>
          <el-menu-item v-if="hasPerm(PERMS.SYSTEM_USER_LIST)" index="/system/users">
            <el-icon><User /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item v-if="hasPerm(PERMS.SYSTEM_ROLE_LIST)" index="/system/roles">
            <el-icon><Avatar /></el-icon>
            <span>角色权限</span>
          </el-menu-item>
          <el-menu-item v-if="hasPerm(PERMS.SYSTEM_MENU_LIST)" index="/system/menus">
            <el-icon><Menu /></el-icon>
            <span>菜单管理</span>
          </el-menu-item>
          <el-menu-item v-if="hasPerm(PERMS.SYSTEM_OPERLOG_LIST)" index="/system/operlog">
            <el-icon><Clock /></el-icon>
            <span>操作日志</span>
          </el-menu-item>
          <el-menu-item v-if="hasPerm(PERMS.AI_ROLE_MANAGE)" index="/roles">
            <el-icon><MagicStick /></el-icon>
            <span>AI 人设</span>
          </el-menu-item>
        </el-menu-item-group>

        <!-- 医院管理（按数据权限相关功能权限渲染，自动数据过滤由后端完成） -->
        <el-menu-item-group v-if="hasAnyHospitalPerm">
          <template #title><div class="nav-group-title">医院管理</div></template>
          <el-menu-item v-if="hasPerm(PERMS.HOSPITAL_MANAGE)" index="/hospital/info">
            <el-icon><OfficeBuilding /></el-icon>
            <span>医院管理</span>
          </el-menu-item>
          <el-menu-item v-if="hasPerm(PERMS.HOSPITAL_MANAGE)" index="/hospital/dept">
            <el-icon><Grid /></el-icon>
            <span>科室管理</span>
          </el-menu-item>
          <el-menu-item v-if="hasPerm(PERMS.HOSPITAL_EMPLOYEE_LIST)" index="/hospital/employees">
            <el-icon><UserFilled /></el-icon>
            <span>员工管理</span>
          </el-menu-item>
          <el-menu-item v-if="hasPerm(PERMS.HOSPITAL_ATTENDANCE_LIST) || hasPerm(PERMS.HOSPITAL_ATTENDANCE_SELF)" index="/hospital/attendance">
            <el-icon><AlarmClock /></el-icon>
            <span>出勤管理</span>
          </el-menu-item>
          <el-menu-item v-if="hasPerm(PERMS.HOSPITAL_STATS_VIEW)" index="/hospital/stats">
            <el-icon><TrendCharts /></el-icon>
            <span>出勤统计</span>
          </el-menu-item>
        </el-menu-item-group>

        <!-- 门诊挂号（复用人设系统角色，按数据权限相关功能权限渲染，后端自动过滤） -->
        <el-menu-item-group v-if="hasAnyRegistrationPerm">
          <template #title><div class="nav-group-title">门诊挂号</div></template>
          <el-menu-item v-if="hasPerm(PERMS.REGISTRATION_SCHEDULE_MANAGE)" index="/registration/schedule">
            <el-icon><Calendar /></el-icon>
            <span>排班与号源</span>
          </el-menu-item>
          <el-menu-item v-if="hasPerm(PERMS.REGISTRATION_REGISTER_LIST)" index="/registration/records">
            <el-icon><Tickets /></el-icon>
            <span>挂号记录</span>
          </el-menu-item>
          <el-menu-item v-if="hasPerm(PERMS.REGISTRATION_STATS_VIEW)" index="/registration/stats">
            <el-icon><PieChart /></el-icon>
            <span>挂号统计</span>
          </el-menu-item>
        </el-menu-item-group>

        <!-- AI 助手（静态分组，不依赖后端菜单表；管理接口按医院快照鉴权） -->
        <el-menu-item-group v-if="token">
          <template #title><div class="nav-group-title">AI 助手</div></template>
          <el-menu-item index="/admin/knowledge">
            <el-icon><Notebook /></el-icon>
            <span>知识库管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/triage-records">
            <el-icon><ChatLineSquare /></el-icon>
            <span>AI 问诊记录</span>
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>

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
import { useRoute, useRouter } from 'vue-router'
import {
  ChatDotRound, Sunny, MagicStick, User, Avatar, Menu, Clock,
  OfficeBuilding, Grid, UserFilled, AlarmClock, TrendCharts,
  Calendar, Tickets, PieChart, Notebook, ChatLineSquare
} from '@element-plus/icons-vue'
import { useAuth } from '../store/auth'
import { logout, revokeRefresh } from '../api/auth'
import { confirm as showConfirm } from '../utils/message'
import { PERMS, hasPerm as checkPerm, hasAnyPerm as checkAnyPerm } from '../utils/perm'

const route = useRoute()
const router = useRouter()
const { token, userInfo, clear } = useAuth()

// 权限判断统一走 utils/perm（与后端权限串一致）
const hasPerm = (perm) => checkPerm(userInfo.value?.permissions, perm)
const hasAnyPerm = (permList) => checkAnyPerm(userInfo.value?.permissions, permList)

const hasAnySystemPerm = computed(() =>
  hasAnyPerm([
    PERMS.SYSTEM_USER_LIST,
    PERMS.SYSTEM_ROLE_LIST,
    PERMS.SYSTEM_MENU_LIST,
    PERMS.SYSTEM_OPERLOG_LIST,
    PERMS.AI_ROLE_MANAGE
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
  // 退出前弹确认框，选「确定」才真正退出
  const ok = await showConfirm('确定要退出登录吗？', { okText: '退出', cancelText: '取消' })
  if (!ok) return
  const refreshToken = localStorage.getItem('ai_refresh_token')
  try {
    await revokeRefresh(refreshToken)
  } catch (e) {
    // 撤销失败不阻断登出，本地清除即可
  }
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
  background: var(--bg-card);
  border-right: 1px solid #e8f0ee;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.sidebar-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px;
  border-bottom: 1px solid #eef4f2;
}

.logo {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, #0d9488, #14b8a6);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 16px;
  color: #fff;
  flex-shrink: 0;
}

.app-title {
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 600;
}

.sidebar-nav {
  flex: 1;
  padding: 8px;
  overflow-y: auto;
  border-right: none;
  --el-menu-item-height: 42px;
}

.sidebar-nav :deep(.el-menu-item) {
  border-radius: 8px;
  margin-bottom: 4px;
  color: var(--text-secondary);
}

.sidebar-nav :deep(.el-menu-item:hover) {
  background: #f0f7f6;
  color: #0d9488;
}

.sidebar-nav :deep(.el-menu-item.is-active) {
  background: #e7f4f3;
  color: #0d9488;
  font-weight: 600;
}

.nav-group-title {
  color: var(--text-tertiary);
  font-size: 11px;
  letter-spacing: 1px;
  padding: 6px 8px 2px;
  line-height: 1.6;
}

.sidebar-footer {
  padding: 16px;
  border-top: 1px solid #eef4f2;
}

.user-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.user-name {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
}

.user-role {
  color: var(--text-tertiary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.profile-link {
  padding: 5px 12px;
  background: #f0f7f6;
  border: 1px solid #dcebe8;
  color: var(--text-secondary);
  border-radius: 8px;
  cursor: pointer;
  font-size: 12px;
  text-decoration: none;
}

.profile-link:hover {
  background: #e7f4f3;
  border-color: #b6dfdb;
  color: #0d9488;
}

.logout-btn {
  flex-shrink: 0;
  padding: 5px 12px;
  background: #f0f7f6;
  border: 1px solid #dcebe8;
  color: var(--text-secondary);
  border-radius: 8px;
  cursor: pointer;
  font-size: 12px;
}

.logout-btn:hover {
  background: #fdecec;
  border-color: #f7a2a2;
  color: #ef4444;
}

.login-link {
  display: block;
  text-align: center;
  color: var(--text-secondary);
  font-size: 13px;
  padding: 8px;
  border-radius: 8px;
  border: 1px solid #dcebe8;
}

.login-link:hover {
  background: #f0f7f6;
  color: #0d9488;
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}
</style>
