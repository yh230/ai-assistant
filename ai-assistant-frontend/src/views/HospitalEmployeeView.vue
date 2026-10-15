<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">员工管理</h2>
        <p class="page-sub">维护医院员工并绑定医院/科室/岗位角色。院长看全部，科室主任仅本科室（后端自动过滤）。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增员工</el-button>
    </div>

    <div class="toolbar">
      <el-select v-model="filterHospital" placeholder="全部医院" clearable style="width: 220px" @change="onHospitalChange">
        <el-option v-for="h in hospitalOptions" :key="h.id" :label="h.name" :value="h.id" />
      </el-select>
      <el-select v-model="filterDept" placeholder="全部科室" clearable style="width: 220px">
        <el-option v-for="d in deptOptions" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-input v-model="keyword" placeholder="搜索账号/姓名/手机" style="width: 240px" @keyup.enter="reload" />
      <el-button @click="reload">查询</el-button>
    </div>

    <div class="table-card">
      <el-table v-if="list.length > 0" :data="list" stripe>
        <el-table-column label="ID" width="70">
          <template #default="{ row }"><code class="id-cell">{{ row.id }}</code></template>
        </el-table-column>
        <el-table-column prop="username" label="账号" />
        <el-table-column prop="name" label="姓名" />
        <el-table-column label="手机">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
        </el-table-column>
        <el-table-column label="医院">
          <template #default="{ row }">{{ row.hospitalName || '—' }}</template>
        </el-table-column>
        <el-table-column label="科室">
          <template #default="{ row }">{{ row.departmentName || '—' }}</template>
        </el-table-column>
        <el-table-column label="角色">
          <template #default="{ row }"><span class="role-cell">{{ row.roleNames || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="toggleStatus(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
            <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无员工数据" :image-size="96" />
    </div>

    <div class="pager" v-if="total > 0">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :current-page="pageNum"
        :page-size="pageSize"
        @current-change="changePage"
      />
    </div>

    <el-dialog v-model="showModal" :title="isEdit ? '编辑员工' : '新增员工'" width="540px">
      <el-form label-position="top">
        <el-form-item label="账号" required>
          <el-input v-model="form.username" :disabled="isEdit" placeholder="登录账号" />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="密码">
          <el-input v-model="form.password" placeholder="留空则使用默认密码" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" placeholder="如 张三" />
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model="form.phone" placeholder="选填" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="选填" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model.number="form.status" style="width: 100%">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属医院">
          <el-select v-model.number="form.hospitalId" placeholder="未分配" clearable style="width: 100%" @change="onFormHospitalChange">
            <el-option label="未分配" :value="null" />
            <el-option v-for="h in hospitalOptions" :key="h.id" :label="h.name" :value="h.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属科室">
          <el-select v-model.number="form.departmentId" placeholder="未分配" clearable style="width: 100%">
            <el-option label="未分配" :value="null" />
            <el-option v-for="d in formDeptOptions" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="岗位角色">
          <div class="checkbox-grid" v-if="roleOptions.length">
            <el-checkbox-group v-model="form.roleIds">
              <el-checkbox v-for="r in roleOptions" :key="r.id" :value="r.id" class="checkbox-label">
                {{ r.roleName }} <em class="code">{{ r.roleCode }}</em>
              </el-checkbox>
            </el-checkbox-group>
          </div>
          <span v-else class="form-hint">暂无角色可选</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeModal">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showReset" :title="`重置密码 - ${resetTarget?.name}`" width="480px">
      <el-form label-position="top">
        <el-form-item label="新密码">
          <el-input v-model="newPassword" placeholder="留空则重置为默认密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showReset = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="doReset">{{ resetting ? '处理中…' : '确认重置' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
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
.role-cell { color: var(--text-secondary); }
.form-hint { font-size: 12px; color: var(--text-tertiary); }
.checkbox-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.checkbox-label .code { color: var(--text-tertiary); font-size: 11px; font-style: normal; }
</style>
