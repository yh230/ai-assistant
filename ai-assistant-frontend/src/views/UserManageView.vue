<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-sub">维护后台员工账号，可分配角色、启停账号、重置密码。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
    </div>

    <!-- 搜索 -->
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="搜索账号 / 姓名 / 手机号" style="width: 240px" @keyup.enter="reload" />
      <el-button @click="reload">查询</el-button>
    </div>

    <!-- 表格 -->
    <div class="table-card" v-loading="loading">
      <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon style="margin: 12px 12px 0">
        <el-button link type="primary" @click="retry">重试</el-button>
      </el-alert>
      <el-table v-if="list.length > 0" :data="list" stripe>
        <el-table-column label="ID" width="70">
          <template #default="{ row }"><code class="id-cell" :title="String(row.id)">{{ row.id }}</code></template>
        </el-table-column>
        <el-table-column label="账号" width="140">
          <template #default="{ row }"><span :title="row.username">{{ row.username }}</span></template>
        </el-table-column>
        <el-table-column label="姓名" width="120">
          <template #default="{ row }"><span :title="row.name">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="手机号" width="130">
          <template #default="{ row }"><span :title="row.phone || ''">{{ row.phone || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="邮箱" width="220">
          <template #default="{ row }"><span :title="row.email || ''">{{ row.email || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="170">
          <template #default="{ row }"><span class="role-cell" :title="row.roleNames || ''">{{ row.roleNames || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }"><span class="time-cell" :title="formatTime(row.createTime)">{{ formatTime(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="300">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="toggleStatus(row)">
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
            <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
            <el-button v-if="isAdmin && row.id !== currentUser?.userId" link type="primary" @click="kickout(row)">踢下线</el-button>
            <el-button link type="danger" @click="removeUser(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loading && !error" description="暂无用户数据" :image-size="96" />
    </div>

    <!-- 分页 -->
    <div class="pager" v-if="total > 0">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :current-page="pageNum"
        :page-size="pageSize"
        @current-change="changePage"
      />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="showModal" :title="isEdit ? '编辑用户' : '新增用户'" width="520px">
      <el-form label-position="top">
        <el-form-item label="账号" required>
          <el-input v-model="form.username" :disabled="isEdit" placeholder="登录账号" />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="密码">
          <el-input v-model="form.password" type="text" placeholder="留空则使用默认密码 123456" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" placeholder="如 张三" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="选填" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="选填" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="分配角色">
          <div class="checkbox-grid" v-if="roleOptions.length">
            <el-checkbox-group v-model="form.roleIds">
              <el-checkbox v-for="r in roleOptions" :key="r.id" :value="r.id">
                {{ r.roleName }} <em class="code">{{ r.roleCode }}</em>
              </el-checkbox>
            </el-checkbox-group>
          </div>
          <span v-else class="form-hint">暂无角色可选</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeModal">取消</el-button>
        <el-button type="primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码弹窗 -->
    <el-dialog v-model="showReset" :title="`重置密码 - ${resetTarget?.name}`" width="460px">
      <el-form label-position="top">
        <el-form-item label="新密码">
          <el-input v-model="newPassword" type="text" placeholder="留空则重置为默认密码 123456" />
        </el-form-item>
      </el-form>
      <p class="form-hint">重置后用户需使用新密码登录。</p>
      <template #footer>
        <el-button @click="showReset = false">取消</el-button>
        <el-button type="primary" :disabled="resetting" @click="doReset">
          {{ resetting ? '处理中…' : '确认重置' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import {
  pageUsers, createUser, updateUser, deleteUser, changeStatus, resetPassword, kickoutUser
} from '../api/sysuser'
import { listAllRoles } from '../api/sysrole'
import { useAuth } from '../store/auth'
import { useCrudPage } from '../composables/useCrudPage'
import { formatTime } from '../utils/format'
import { confirm as showConfirm, toast } from '../utils/message'

// 当前登录用户是否为超级管理员（role_code=admin 才可踢人下线）
const { userInfo: currentUser } = useAuth()
const isAdmin = computed(() => (currentUser.value?.roles || []).includes('admin'))

const roleOptions = ref([])

const {
  list, total, pageNum, pageSize, keyword, totalPages,
  loading, error,
  showModal, isEdit, saving, form,
  loadData, retry, reload, changePage, openCreate: openCreateBase, openEdit: openEditBase, closeModal, save, toggleStatus,
  remove: removeUser
} = useCrudPage({
  fetchPage: (params) => pageUsers(params),
  emptyForm: () => ({
    username: '', password: '', name: '', phone: '', email: '', status: 1, roleIds: []
  }),
  toForm: (u) => ({
    username: u.username, password: '', name: u.name, phone: u.phone || '', email: u.email || '',
    status: u.status === 0 ? 0 : 1, roleIds: u.roleIds ? [...u.roleIds] : []
  }),
  validate: (f) => {
    if (!f.username.trim()) return '请填写账号'
    if (!f.name.trim()) return '请填写姓名'
    return null
  },
  persist: (f, id) => {
    const payload = {
      username: f.username.trim(), name: f.name.trim(),
      phone: f.phone.trim(), email: f.email.trim(),
      status: f.status, roleIds: f.roleIds
    }
    if (id) return updateUser(id, payload)
    // 密码留空使用默认密码 123456，无需提示
    payload.password = f.password.trim() || undefined
    return createUser(payload)
  },
  toggle: (u, next) => changeStatus(u.id, next),
  toggleConfirm: (next, u) => `确定${next === 1 ? '启用' : '禁用'}用户「${u.name}」吗？`,
  remove: (u) => deleteUser(u.id),
  removeConfirm: (u) => `确定删除用户「${u.name}」(${u.username}) 吗？此操作不可恢复。`
})

// 薄包装（骨架 openCreate/openEdit 之外的本页特有逻辑）：打开新增/编辑弹窗前确保角色下拉已加载
async function openCreate() {
  await ensureRoles()
  openCreateBase()
}
async function openEdit(u) {
  await ensureRoles()
  openEditBase(u)
}

async function ensureRoles() {
  if (roleOptions.value.length) return
  try {
    const res = await listAllRoles()
    roleOptions.value = res.list || []
  } catch (e) {
    roleOptions.value = []
  }
}

// 页面特有：重置密码弹窗（不并入骨架）
const showReset = ref(false)
const resetting = ref(false)
const resetTarget = ref(null)
const newPassword = ref('')

function openReset(u) {
  resetTarget.value = u
  newPassword.value = ''
  showReset.value = true
}

async function doReset() {
  resetting.value = true
  try {
    await resetPassword(resetTarget.value.id, newPassword.value.trim() || undefined)
    showReset.value = false
    toast.success('密码已重置' + (newPassword.value.trim() ? '为自定义密码' : '为默认密码 123456'))
  } catch (e) {
    toast.error('重置失败：' + e.message)
  } finally {
    resetting.value = false
  }
}

// 页面特有：踢下线（仅超级管理员可操作，不并入骨架）
async function kickout(u) {
  if (!(await showConfirm(`确定将用户「${u.name}」(${u.username}) 强制下线吗？其当前会话会立即失效。`))) return
  try {
    await kickoutUser(u.id)
    toast.success('已强制下线')
  } catch (e) {
    toast.error('操作失败：' + e.message)
  }
}

onMounted(loadData)
</script>

<style scoped>
.form-hint { font-size: 12px; color: var(--text-tertiary); }

.checkbox-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; width: 100%; }
.checkbox-grid .code { color: var(--text-tertiary); font-size: 11px; font-style: normal; }
</style>
