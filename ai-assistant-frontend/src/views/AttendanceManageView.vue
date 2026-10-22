<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">出勤管理</h2>
        <p class="page-sub">
          记录员工每日出勤。院长看全院、科室主任看本科室、普通员工仅看本人（后端自动过滤，前端无需传权限参数）。
        </p>
      </div>
      <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openCreate">登记出勤</el-button>
    </div>

    <div class="toolbar" v-if="canViewAll">
      <el-select v-model="filterHospital" placeholder="全部医院" clearable style="width: 160px" @change="onHospitalChange">
        <el-option v-for="h in hospitalOptions" :key="h.id" :label="h.name" :value="h.id" />
      </el-select>
      <el-select v-model="filterDept" placeholder="全部科室" clearable style="width: 160px">
        <el-option v-for="d in deptOptions" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-date-picker v-model="dateStart" type="date" value-format="YYYY-MM-DD" style="width: 160px" />
      <span class="til">至</span>
      <el-date-picker v-model="dateEnd" type="date" value-format="YYYY-MM-DD" style="width: 160px" />
      <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 160px">
        <el-option label="出勤" :value="1" />
        <el-option label="缺勤" :value="2" />
        <el-option label="请假" :value="3" />
        <el-option label="迟到" :value="4" />
      </el-select>
      <el-button @click="reload">查询</el-button>
    </div>

    <div class="table-card" v-loading="loading">
      <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon style="margin: 12px 12px 0">
        <el-button link type="primary" @click="retry">重试</el-button>
      </el-alert>
      <el-table v-if="list.length > 0" :data="list" stripe>
        <el-table-column label="ID" width="70">
          <template #default="{ row }"><code class="id-cell">{{ row.id }}</code></template>
        </el-table-column>
        <el-table-column label="员工">
          <template #default="{ row }">{{ row.userName || '—' }}</template>
        </el-table-column>
        <el-table-column v-if="canViewAll" label="医院">
          <template #default="{ row }">{{ row.hospitalName || '—' }}</template>
        </el-table-column>
        <el-table-column v-if="canViewAll" label="科室">
          <template #default="{ row }">{{ row.departmentName || '—' }}</template>
        </el-table-column>
        <el-table-column label="日期">
          <template #default="{ row }">{{ row.attendDate }}</template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上班">
          <template #default="{ row }">{{ row.checkIn || '—' }}</template>
        </el-table-column>
        <el-table-column label="下班">
          <template #default="{ row }">{{ row.checkOut || '—' }}</template>
        </el-table-column>
        <el-table-column label="备注">
          <template #default="{ row }"><span class="remark-cell">{{ row.remark || '—' }}</span></template>
        </el-table-column>
        <el-table-column v-if="canEdit" label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loading && !error" description="暂无出勤记录" :image-size="96" />
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

    <el-dialog v-model="showModal" :title="isEdit ? '编辑出勤' : '登记出勤'" width="520px">
      <el-form label-position="top">
        <el-form-item label="员工" required>
          <el-select v-model="form.userId" placeholder="请选择员工" style="width: 100%">
            <el-option label="请选择员工" :value="null" disabled />
            <el-option v-for="u in employeeOptions" :key="u.id" :label="`${u.name}（${u.username}）`" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="出勤日期" required>
          <el-date-picker v-model="form.attendDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="出勤" :value="1" />
            <el-option label="缺勤" :value="2" />
            <el-option label="请假" :value="3" />
            <el-option label="迟到" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="上班打卡">
          <el-input v-model="form.checkIn" placeholder="HH:mm，如 08:30" />
        </el-form-item>
        <el-form-item label="下班打卡">
          <el-input v-model="form.checkOut" placeholder="HH:mm，如 17:30" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeModal">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { Plus } from '@element-plus/icons-vue'
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
  loading, error,
  showModal, isEdit, saving, form,
  loadData, retry, reload, changePage, openCreate, openEdit, closeModal, save, remove
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

// 出勤状态 1-4 对应 el-tag 语义色：出勤/缺勤/请假/迟到
function statusTagType(status) {
  return ({ 1: 'success', 2: 'danger', 3: 'primary', 4: 'warning' })[status] || 'info'
}

// 页面特有：权限视角（普通员工仅看本人数据，隐藏筛选与操作按钮）
async function ensureOptions() {
  if (canViewAll.value) {
    try { hospitalOptions.value = await listHospitals() } catch (e) { hospitalOptions.value = [] }
    // 全局分页上限 100
    try { employeeOptions.value = (await pageEmployees({ pageNum: 1, pageSize: 100 })).list || [] } catch (e) { employeeOptions.value = [] }
  }
}
async function onHospitalChange() {
  filterDept.value = null
  try { deptOptions.value = filterHospital.value ? await listDepartments(filterHospital.value) : [] } catch (e) { deptOptions.value = [] }
}

onMounted(async () => { await ensureOptions(); await loadData() })
</script>

<style scoped>
.til { color: var(--text-tertiary); font-size: 13px; }
.remark-cell { color: var(--text-secondary); display: inline-block; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
