<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">员工管理</h2>
        <p class="page-sub">维护医院员工并绑定医院/科室/岗位角色。院长看全部，科室主任仅本科室（后端自动过滤）。</p>
      </div>
      <button class="primary-btn" @click="openCreate">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        新增员工
      </button>
    </div>

    <div class="toolbar">
      <select v-model="filterHospital" class="search-input" @change="onHospitalChange">
        <option :value="null">全部医院</option>
        <option v-for="h in hospitalOptions" :key="h.id" :value="h.id">{{ h.name }}</option>
      </select>
      <select v-model="filterDept" class="search-input">
        <option :value="null">全部科室</option>
        <option v-for="d in deptOptions" :key="d.id" :value="d.id">{{ d.name }}</option>
      </select>
      <input v-model="keyword" class="search-input" placeholder="搜索账号/姓名/手机" @keyup.enter="reload" />
      <button class="ghost-btn" @click="reload">查询</button>
    </div>

    <div class="table-card">
      <table class="data-table" v-if="list.length > 0">
        <thead>
          <tr>
            <th style="width: 70px">ID</th>
            <th>账号</th>
            <th>姓名</th>
            <th>手机</th>
            <th>医院</th>
            <th>科室</th>
            <th>角色</th>
            <th style="width: 80px">状态</th>
            <th style="width: 240px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in list" :key="u.id">
            <td><code class="id-cell">{{ u.id }}</code></td>
            <td>{{ u.username }}</td>
            <td>{{ u.name }}</td>
            <td>{{ u.phone || '—' }}</td>
            <td>{{ u.hospitalName || '—' }}</td>
            <td>{{ u.departmentName || '—' }}</td>
            <td class="role-cell">{{ u.roleNames || '—' }}</td>
            <td>
              <span class="status-tag" :class="u.status === 1 ? 'on' : 'off'">
                {{ u.status === 1 ? '启用' : '禁用' }}
              </span>
            </td>
            <td>
              <button class="link-btn" @click="openEdit(u)">编辑</button>
              <button class="link-btn" @click="toggleStatus(u)">{{ u.status === 1 ? '禁用' : '启用' }}</button>
              <button class="link-btn" @click="openReset(u)">重置密码</button>
              <button class="link-btn danger" @click="remove(u)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-tip">暂无员工数据</div>
    </div>

    <div class="pager" v-if="total > 0">
      <button class="ghost-btn" :disabled="pageNum <= 1" @click="changePage(pageNum - 1)">上一页</button>
      <span class="pager-info">第 {{ pageNum }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
      <button class="ghost-btn" :disabled="pageNum >= totalPages" @click="changePage(pageNum + 1)">下一页</button>
    </div>

    <div class="modal-mask" v-if="showModal" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ isEdit ? '编辑员工' : '新增员工' }}</h3>
          <button class="modal-close" @click="closeModal">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label>账号 <span class="req">*</span></label>
            <input v-model="form.username" :disabled="isEdit" class="form-input" placeholder="登录账号" />
          </div>
          <div class="form-row" v-if="!isEdit">
            <label>密码</label>
            <input v-model="form.password" type="text" class="form-input" placeholder="留空则使用默认密码" />
          </div>
          <div class="form-row">
            <label>姓名 <span class="req">*</span></label>
            <input v-model="form.name" class="form-input" placeholder="如 张三" />
          </div>
          <div class="form-row">
            <label>手机</label>
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
            <label>所属医院</label>
            <select v-model.number="form.hospitalId" class="form-input" @change="onFormHospitalChange">
              <option :value="null">未分配</option>
              <option v-for="h in hospitalOptions" :key="h.id" :value="h.id">{{ h.name }}</option>
            </select>
          </div>
          <div class="form-row">
            <label>所属科室</label>
            <select v-model.number="form.departmentId" class="form-input">
              <option :value="null">未分配</option>
              <option v-for="d in formDeptOptions" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
          </div>
          <div class="form-row">
            <label>岗位角色</label>
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

    <div class="modal-mask" v-if="showReset" @click.self="showReset = false">
      <div class="modal">
        <div class="modal-header">
          <h3>重置密码 - {{ resetTarget?.name }}</h3>
          <button class="modal-close" @click="showReset = false">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label>新密码</label>
            <input v-model="newPassword" type="text" class="form-input" placeholder="留空则重置为默认密码" />
          </div>
        </div>
        <div class="modal-footer">
          <button class="ghost-btn" @click="showReset = false">取消</button>
          <button class="primary-btn" :disabled="resetting" @click="doReset">{{ resetting ? '处理中…' : '确认重置' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import {
  pageEmployees, createEmployee, updateEmployee, deleteEmployee,
  changeEmployeeStatus, resetEmployeePassword, listHospitals, listDepartments, listEmployeeRoles
} from '../api/hospital'
import { useCrudPage } from '../composables/useCrudPage'
import { toast } from '../utils/message'

const filterHospital = ref(null)
const filterDept = ref(null)

const hospitalOptions = ref([])
const deptOptions = ref([])        // 顶部筛选用（全院/选中医院）
const formDeptOptions = ref([])    // 表单用（随所选医院过滤）
const roleOptions = ref([])

const {
  list, total, pageNum, pageSize, keyword, totalPages,
  showModal, isEdit, saving, form,
  loadData, reload, changePage, closeModal, save, toggleStatus, remove,
  openCreate: openCreateBase, openEdit: openEditBase
} = useCrudPage({
  // keyword/hospitalId/departmentId 为本页查询条件，在 fetchPage 内拼装
  fetchPage: (params) => pageEmployees({
    pageNum: params.pageNum, pageSize: params.pageSize, keyword: params.keyword,
    hospitalId: filterHospital.value || undefined, departmentId: filterDept.value || undefined
  }),
  emptyForm: () => ({
    username: '', password: '', name: '', phone: '', email: '', status: 1,
    hospitalId: null, departmentId: null, roleIds: []
  }),
  toForm: (u) => ({
    username: u.username, password: '', name: u.name, phone: u.phone || '', email: u.email || '',
    status: u.status === 0 ? 0 : 1, hospitalId: u.hospitalId || null, departmentId: u.departmentId || null,
    roleIds: u.roleIds ? [...u.roleIds] : []
  }),
  validate: (f) => {
    if (!f.username.trim()) return '请填写账号'
    if (!f.name.trim()) return '请填写姓名'
    return null
  },
  persist: (f, id) => {
    const payload = {
      username: f.username.trim(), name: f.name.trim(),
      phone: f.phone.trim(), email: f.email.trim(), status: f.status,
      hospitalId: f.hospitalId || null, departmentId: f.departmentId || null,
      roleIds: f.roleIds
    }
    if (id) return updateEmployee(id, payload)
    payload.password = f.password.trim() || undefined
    return createEmployee(payload)
  },
  toggle: (u, next) => changeEmployeeStatus(u.id, next),
  toggleConfirm: (next, u) => `确定${next === 1 ? '启用' : '禁用'}员工「${u.name}」吗？`,
  remove: (u) => deleteEmployee(u.id),
  removeConfirm: (u) => `确定删除员工「${u.name}」(${u.username}) 吗？此操作不可恢复。`
})

// 骨架 openCreate/openEdit 之外的本页特有逻辑：表单科室下拉随所选医院联动
function openCreate() {
  openCreateBase()
  formDeptOptions.value = []
}
function openEdit(u) {
  openEditBase(u)
  onFormHospitalChange()
}

async function ensureOptions() {
  try { hospitalOptions.value = await listHospitals() } catch (e) { hospitalOptions.value = [] }
  try { roleOptions.value = await listEmployeeRoles() } catch (e) { roleOptions.value = [] }
  await reloadDeptOptions()
}
async function reloadDeptOptions() {
  try {
    deptOptions.value = filterHospital.value
      ? await listDepartments(filterHospital.value)
      : [] // 未选医院时不展示科室筛选项
  } catch (e) { deptOptions.value = [] }
}
function onHospitalChange() { filterDept.value = null; reloadDeptOptions(); reload() }
async function onFormHospitalChange() {
  form.value.departmentId = null
  try {
    formDeptOptions.value = form.value.hospitalId ? await listDepartments(form.value.hospitalId) : []
  } catch (e) { formDeptOptions.value = [] }
}

// 页面特有：重置密码弹窗（不并入骨架）
const showReset = ref(false)
const resetting = ref(false)
const resetTarget = ref(null)
const newPassword = ref('')

function openReset(u) { resetTarget.value = u; newPassword.value = ''; showReset.value = true }
async function doReset() {
  resetting.value = true
  try {
    await resetEmployeePassword(resetTarget.value.id, newPassword.value.trim() || undefined)
    showReset.value = false
    toast.success('密码已重置' + (newPassword.value.trim() ? '为自定义密码' : '为默认密码'))
  } catch (e) { toast.error('重置失败：' + e.message) } finally { resetting.value = false }
}

// 保持原状：挂载时仅初始化下拉选项（列表由「查询」触发加载），loadData 由骨架内部统一使用
onMounted(ensureOptions)
</script>

<style scoped>
.search-input { width: 200px; padding: 8px 12px; border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); background: var(--bg-input); color: var(--text-primary); font-size: 14px; outline: none; }
.role-cell { color: var(--text-secondary); }
.modal { width: 540px; max-width: 92vw; max-height: 90vh; background: var(--bg-card); border-radius: var(--border-radius); display: flex; flex-direction: column; box-shadow: var(--shadow-xl); overflow: hidden; }
.form-input:disabled { background: var(--bg-hover); color: var(--text-tertiary); cursor: not-allowed; }
.form-hint { font-size: 12px; color: var(--text-tertiary); }
.checkbox-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.checkbox-label { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-secondary); cursor: pointer; }
.checkbox-label .code { color: var(--text-tertiary); font-size: 11px; font-style: normal; }
</style>
