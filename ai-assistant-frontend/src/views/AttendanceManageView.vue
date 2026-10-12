<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">出勤管理</h2>
        <p class="page-sub">
          记录员工每日出勤。院长看全院、科室主任看本科室、普通员工仅看本人（后端自动过滤，前端无需传权限参数）。
        </p>
      </div>
      <button v-if="canEdit" class="primary-btn" @click="openCreate">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        登记出勤
      </button>
    </div>

    <div class="toolbar" v-if="canViewAll">
      <select v-model="filterHospital" class="search-input" @change="onHospitalChange">
        <option :value="null">全部医院</option>
        <option v-for="h in hospitalOptions" :key="h.id" :value="h.id">{{ h.name }}</option>
      </select>
      <select v-model="filterDept" class="search-input">
        <option :value="null">全部科室</option>
        <option v-for="d in deptOptions" :key="d.id" :value="d.id">{{ d.name }}</option>
      </select>
      <input v-model="dateStart" type="date" class="search-input" />
      <span class="til">至</span>
      <input v-model="dateEnd" type="date" class="search-input" />
      <select v-model="filterStatus" class="search-input">
        <option :value="null">全部状态</option>
        <option :value="1">出勤</option>
        <option :value="2">缺勤</option>
        <option :value="3">请假</option>
        <option :value="4">迟到</option>
      </select>
      <button class="ghost-btn" @click="reload">查询</button>
    </div>

    <div class="table-card">
      <table class="data-table" v-if="list.length > 0">
        <thead>
          <tr>
            <th style="width: 70px">ID</th>
            <th>员工</th>
            <th v-if="canViewAll">医院</th>
            <th v-if="canViewAll">科室</th>
            <th>日期</th>
            <th>状态</th>
            <th>上班</th>
            <th>下班</th>
            <th>备注</th>
            <th style="width: 140px" v-if="canEdit">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in list" :key="a.id">
            <td><code class="id-cell">{{ a.id }}</code></td>
            <td>{{ a.userName || '—' }}</td>
            <td v-if="canViewAll">{{ a.hospitalName || '—' }}</td>
            <td v-if="canViewAll">{{ a.departmentName || '—' }}</td>
            <td>{{ a.attendDate }}</td>
            <td><span class="status-tag" :class="'s' + a.status">{{ a.statusText }}</span></td>
            <td>{{ a.checkIn || '—' }}</td>
            <td>{{ a.checkOut || '—' }}</td>
            <td class="remark-cell">{{ a.remark || '—' }}</td>
            <td v-if="canEdit">
              <button class="link-btn" @click="openEdit(a)">编辑</button>
              <button class="link-btn danger" @click="remove(a)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-tip">暂无出勤记录</div>
    </div>

    <div class="pager" v-if="total > 0">
      <button class="ghost-btn" :disabled="pageNum <= 1" @click="changePage(pageNum - 1)">上一页</button>
      <span class="pager-info">第 {{ pageNum }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
      <button class="ghost-btn" :disabled="pageNum >= totalPages" @click="changePage(pageNum + 1)">下一页</button>
    </div>

    <div class="modal-mask" v-if="showModal" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ isEdit ? '编辑出勤' : '登记出勤' }}</h3>
          <button class="modal-close" @click="closeModal">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label>员工 <span class="req">*</span></label>
            <select v-model.number="form.userId" class="form-input">
              <option :value="null" disabled>请选择员工</option>
              <option v-for="u in employeeOptions" :key="u.id" :value="u.id">{{ u.name }}（{{ u.username }}）</option>
            </select>
          </div>
          <div class="form-row">
            <label>出勤日期 <span class="req">*</span></label>
            <input v-model="form.attendDate" type="date" class="form-input" />
          </div>
          <div class="form-row">
            <label>状态</label>
            <select v-model.number="form.status" class="form-input">
              <option :value="1">出勤</option>
              <option :value="2">缺勤</option>
              <option :value="3">请假</option>
              <option :value="4">迟到</option>
            </select>
          </div>
          <div class="form-row">
            <label>上班打卡</label>
            <input v-model="form.checkIn" class="form-input" placeholder="HH:mm，如 08:30" />
          </div>
          <div class="form-row">
            <label>下班打卡</label>
            <input v-model="form.checkOut" class="form-input" placeholder="HH:mm，如 17:30" />
          </div>
          <div class="form-row">
            <label>备注</label>
            <input v-model="form.remark" class="form-input" placeholder="选填" />
          </div>
        </div>
        <div class="modal-footer">
          <button class="ghost-btn" @click="closeModal">取消</button>
          <button class="primary-btn" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { pageAttendance, createAttendance, updateAttendance, deleteAttendance, listHospitals, listDepartments, pageEmployees } from '../api/hospital'
import { useCrudPage } from '../composables/useCrudPage'
import { useAuth } from '../store/auth'
import { PERMS } from '../utils/perm'

const { userInfo } = useAuth()
const canEdit = computed(() => (userInfo.value?.permissions || []).includes(PERMS.HOSPITAL_ATTENDANCE_EDIT))
const canViewAll = computed(() => (userInfo.value?.permissions || []).includes(PERMS.HOSPITAL_ATTENDANCE_LIST))

const hospitalOptions = ref([])
const deptOptions = ref([])
const employeeOptions = ref([])
const filterHospital = ref(null)
const filterDept = ref(null)
const dateStart = ref('')
const dateEnd = ref('')
const filterStatus = ref(null)

const {
  list, total, pageNum, pageSize, totalPages,
  showModal, isEdit, saving, form,
  loadData, reload, changePage, openCreate, openEdit, closeModal, save, remove
} = useCrudPage({
  // 本页查询条件（医院/科室/日期区间/状态）在 fetchPage 内拼装；无 keyword 输入项
  fetchPage: (params) => pageAttendance({
    pageNum: params.pageNum, pageSize: params.pageSize,
    hospitalId: canViewAll.value ? (filterHospital.value || undefined) : undefined,
    departmentId: canViewAll.value ? (filterDept.value || undefined) : undefined,
    dateStart: dateStart.value || undefined, dateEnd: dateEnd.value || undefined,
    status: filterStatus.value || undefined
  }),
  emptyForm: () => ({ userId: null, attendDate: '', status: 1, checkIn: '', checkOut: '', remark: '' }),
  toForm: (a) => ({
    userId: a.userId, attendDate: a.attendDate, status: a.status,
    checkIn: a.checkIn || '', checkOut: a.checkOut || '', remark: a.remark || ''
  }),
  validate: (f) => {
    if (!f.userId) return '请选择员工'
    if (!f.attendDate) return '请选择出勤日期'
    return null
  },
  persist: (f, id) => {
    const payload = {
      userId: f.userId, attendDate: f.attendDate, status: f.status,
      checkIn: f.checkIn || '', checkOut: f.checkOut || '', remark: f.remark || ''
    }
    return id ? updateAttendance(id, payload) : createAttendance(payload)
  },
  // 出勤状态 1-4（出勤/缺勤/请假/迟到）无启用/禁用语义，故不配置 toggle/toggleConfirm
  remove: (a) => deleteAttendance(a.id),
  removeConfirm: () => '确定删除该出勤记录吗？'
})

// 页面特有：权限视角（普通员工仅看本人数据，隐藏筛选与操作按钮）
async function ensureOptions() {
  if (canViewAll.value) {
    try { hospitalOptions.value = await listHospitals() } catch (e) { hospitalOptions.value = [] }
    try { employeeOptions.value = (await pageEmployees({ pageNum: 1, pageSize: 1000 })).list || [] } catch (e) { employeeOptions.value = [] }
  }
}
async function onHospitalChange() {
  filterDept.value = null
  try { deptOptions.value = filterHospital.value ? await listDepartments(filterHospital.value) : [] } catch (e) { deptOptions.value = [] }
}

onMounted(async () => { await ensureOptions(); await loadData() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 16px; align-items: center; }
.til { color: var(--text-tertiary); font-size: 13px; }
.search-input { width: 160px; padding: 8px 12px; border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); background: var(--bg-input); color: var(--text-primary); font-size: 14px; outline: none; }
.remark-cell { color: var(--text-secondary); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-tag.s1 { background: rgba(34, 197, 94, 0.15); color: var(--success); }
.status-tag.s2 { background: rgba(239, 68, 68, 0.15); color: var(--danger); }
.status-tag.s3 { background: rgba(99, 102, 241, 0.15); color: var(--primary); }
.status-tag.s4 { background: rgba(245, 158, 11, 0.18); color: #f59e0b; }

/* ===== 修复轮 1：作用域化恢复（本页位于 .manage-page 内，全局新增项按基线差集恢复） =====
   基线与本页无 .ghost-btn:disabled 规则，全局版使翻页按钮禁用态（第 78/80 行）呈现 50% 透明度/禁用光标。
   恢复为「无该规则」等价表现：opacity 取初始值 1；光标取基线值 pointer（全局 button{cursor:pointer} 继承）。 */
.ghost-btn:disabled { opacity: 1; cursor: pointer; }
</style>
