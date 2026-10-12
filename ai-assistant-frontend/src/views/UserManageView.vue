<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-sub">维护后台员工账号，可分配角色、启停账号、重置密码。</p>
      </div>
      <button class="primary-btn" @click="openCreate">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        新增用户
      </button>
    </div>

    <!-- 搜索 -->
    <div class="toolbar">
      <input v-model="keyword" class="search-input" placeholder="搜索账号 / 姓名 / 手机号" @keyup.enter="reload" />
      <button class="ghost-btn" @click="reload">查询</button>
    </div>

    <!-- 表格 -->
    <div class="table-card">
      <table class="data-table" v-if="list.length > 0">
        <thead>
<tr>
          <th style="width: 64px">ID</th>
          <th style="width: 140px">账号</th>
          <th style="width: 120px">姓名</th>
          <th style="width: 130px">手机号</th>
          <th style="width: 220px">邮箱</th>
          <th style="width: 80px">状态</th>
          <th style="width: 170px">角色</th>
          <th style="width: 160px">创建时间</th>
          <th style="width: 300px">操作</th>
        </tr>
        </thead>
        <tbody>
          <tr v-for="u in list" :key="u.id">
            <td :title="String(u.id)"><code class="id-cell">{{ u.id }}</code></td>
            <td :title="u.username">{{ u.username }}</td>
            <td :title="u.name">{{ u.name }}</td>
            <td :title="u.phone || ''">{{ u.phone || '—' }}</td>
            <td :title="u.email || ''">{{ u.email || '—' }}</td>
            <td>
              <span class="status-tag" :class="u.status === 1 ? 'on' : 'off'">
                {{ u.status === 1 ? '启用' : '禁用' }}
              </span>
            </td>
            <td class="role-cell" :title="u.roleNames || ''">{{ u.roleNames || '—' }}</td>
            <td class="time-cell" :title="formatTime(u.createTime)">{{ formatTime(u.createTime) }}</td>
            <td>
              <button class="link-btn" @click="openEdit(u)">编辑</button>
              <button class="link-btn" @click="toggleStatus(u)">
                {{ u.status === 1 ? '禁用' : '启用' }}
              </button>
              <button class="link-btn" @click="openReset(u)">重置密码</button>
              <button v-if="isAdmin && u.id !== currentUser?.userId" class="link-btn danger" @click="kickout(u)">踢下线</button>
              <button class="link-btn danger" @click="removeUser(u)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-tip">暂无用户数据</div>
    </div>

    <!-- 分页 -->
    <div class="pager" v-if="total > 0">
      <button class="ghost-btn" :disabled="pageNum <= 1" @click="changePage(pageNum - 1)">上一页</button>
      <span class="pager-info">第 {{ pageNum }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
      <button class="ghost-btn" :disabled="pageNum >= totalPages" @click="changePage(pageNum + 1)">下一页</button>
    </div>

    <!-- 新增/编辑弹窗 -->
    <div class="modal-mask" v-if="showModal" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ isEdit ? '编辑用户' : '新增用户' }}</h3>
          <button class="modal-close" @click="closeModal">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label>账号 <span class="req">*</span></label>
            <input v-model="form.username" :disabled="isEdit" class="form-input" placeholder="登录账号" />
          </div>
          <div class="form-row" v-if="!isEdit">
            <label>密码</label>
            <input v-model="form.password" type="text" class="form-input" placeholder="留空则使用默认密码 123456" />
          </div>
          <div class="form-row">
            <label>姓名 <span class="req">*</span></label>
            <input v-model="form.name" class="form-input" placeholder="如 张三" />
          </div>
          <div class="form-row">
            <label>手机号</label>
            <input v-model="form.phone" class="form-input" placeholder="选填" />
          </div>
          <div class="form-row">
            <label>邮箱</label>
            <input v-model="form.email" class="form-input" placeholder="选填" />
          </div>
          <div class="form-row">
            <label>状态</label>
            <select v-model.number="form.status" class="form-input">
              <option :value="1">启用</option>
              <option :value="0">禁用</option>
            </select>
          </div>
          <div class="form-row">
            <label>分配角色</label>
            <div class="checkbox-grid" v-if="roleOptions.length">
              <label class="checkbox-label" v-for="r in roleOptions" :key="r.id">
                <input type="checkbox" :value="r.id" v-model="form.roleIds" />
                <span>{{ r.roleName }} <em class="code">{{ r.roleCode }}</em></span>
              </label>
            </div>
            <span v-else class="form-hint">暂无角色可选</span>
          </div>
        </div>
        <div class="modal-footer">
          <button class="ghost-btn" @click="closeModal">取消</button>
          <button class="primary-btn" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>

    <!-- 重置密码弹窗 -->
    <div class="modal-mask" v-if="showReset" @click.self="showReset = false">
      <div class="modal">
        <div class="modal-header">
          <h3>重置密码 - {{ resetTarget?.name }}</h3>
          <button class="modal-close" @click="showReset = false">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label>新密码</label>
            <input v-model="newPassword" type="text" class="form-input" placeholder="留空则重置为默认密码 123456" />
          </div>
          <p class="form-hint">重置后用户需使用新密码登录。</p>
        </div>
        <div class="modal-footer">
          <button class="ghost-btn" @click="showReset = false">取消</button>
          <button class="primary-btn" :disabled="resetting" @click="doReset">
            {{ resetting ? '处理中…' : '确认重置' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
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
  showModal, isEdit, saving, form,
  loadData, reload, changePage, openCreate: openCreateBase, openEdit: openEditBase, closeModal, save, toggleStatus,
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
.primary-btn {
  display: inline-flex; align-items: center; gap: 6px; padding: 9px 16px;
  background: var(--primary); color: white; border: none; border-radius: var(--border-radius-sm);
  font-size: 14px; cursor: pointer; transition: var(--transition);
}

.table-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); overflow-x: auto; overflow-y: hidden; }
.data-table { width: 100%; min-width: 1380px; border-collapse: collapse; font-size: 14px; table-layout: fixed; }
.data-table th {
  text-align: left; padding: 12px 16px; background: var(--bg-hover);
  color: var(--text-secondary); font-weight: 600; border-bottom: 1px solid var(--border-color);
  white-space: nowrap;
}
.data-table td { padding: 12px 16px; border-bottom: 1px solid var(--border-color); color: var(--text-primary); vertical-align: middle; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.role-cell { color: var(--text-secondary); }

.form-input:disabled { background: var(--bg-hover); color: var(--text-tertiary); cursor: not-allowed; }
.form-hint { font-size: 12px; color: var(--text-tertiary); }

.checkbox-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.checkbox-label { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-secondary); cursor: pointer; }
.checkbox-label .code { color: var(--text-tertiary); font-size: 11px; font-style: normal; }
</style>
