<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">科室管理</h2>
        <p class="page-sub">维护科室并绑定科室主任（仅院长/管理员可操作，数据范围：全部）。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增科室</el-button>
    </div>

    <div class="toolbar">
      <el-select v-model="filterHospital" placeholder="全部医院" clearable style="width: 220px" @change="reload">
        <el-option v-for="h in hospitalOptions" :key="h.id" :label="h.name" :value="h.id" />
      </el-select>
      <el-input v-model="keyword" placeholder="搜索科室名称" style="width: 240px" @keyup.enter="reload" />
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
        <el-table-column label="所属医院">
          <template #default="{ row }">{{ row.hospitalName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="name" label="科室名称" />
        <el-table-column label="科室主任">
          <template #default="{ row }">{{ row.directorName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }"><span class="time-cell">{{ formatTime(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="toggleStatus(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loading && !error" description="暂无科室数据" :image-size="96" />
    </div>

    <Pager :page-num="pageNum" :page-size="pageSize" :total="total" @change="changePage" />

    <el-dialog v-model="showModal" :title="isEdit ? '编辑科室' : '新增科室'" width="520px">
      <el-form label-position="top">
        <el-form-item label="所属医院" required>
          <el-select v-model="form.hospitalId" placeholder="请选择医院" style="width: 100%">
            <el-option v-for="h in hospitalOptions" :key="h.id" :label="h.name" :value="h.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="科室名称" required>
          <el-input v-model="form.name" placeholder="如 骨科" />
        </el-form-item>
        <el-form-item label="科室主任">
          <el-select v-model="form.directorUserId" placeholder="未指定" clearable style="width: 100%">
            <el-option v-for="u in employeeOptions" :key="u.id" :label="`${u.name}（${u.username}）`" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
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
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import {
  pageDepartments, createDepartment, updateDepartment, deleteDepartment,
  changeDeptStatus, listHospitals, pageEmployees
} from '../api/hospital'
import { useCrudPage } from '../composables/useCrudPage'
import { formatTime } from '../utils/format'
import Pager from '../components/Pager.vue'

const filterHospital = ref(null)
const hospitalOptions = ref([])
const employeeOptions = ref([])

const {
  list, total, pageNum, pageSize, keyword, totalPages,
  loading, error,
  showModal, isEdit, saving, form,
  loadData, retry, reload, changePage, openCreate, openEdit, closeModal, save, toggleStatus, remove
} = useCrudPage({
  // keyword 由骨架透传；hospitalId 为本页筛选条件，在 fetchPage 内拼装
  fetchPage: (params) => pageDepartments({
    pageNum: params.pageNum, pageSize: params.pageSize, keyword: params.keyword,
    hospitalId: filterHospital.value || undefined
  }),
  emptyForm: () => ({ hospitalId: null, name: '', directorUserId: null, status: 1 }),
  toForm: (d) => ({
    hospitalId: d.hospitalId, name: d.name,
    directorUserId: d.directorUserId || null, status: d.status === 0 ? 0 : 1
  }),
  validate: (f) => {
    if (!f.hospitalId) return '请选择所属医院'
    if (!f.name.trim()) return '请填写科室名称'
    return null
  },
  persist: (f, id) => {
    const payload = {
      hospitalId: f.hospitalId, name: f.name.trim(),
      directorUserId: f.directorUserId || null, status: f.status
    }
    return id ? updateDepartment(id, payload) : createDepartment(payload)
  },
  toggle: (d, next) => changeDeptStatus(d.id, next),
  toggleConfirm: (next, d) => `确定${next === 1 ? '启用' : '禁用'}科室「${d.name}」吗？`,
  remove: (d) => deleteDepartment(d.id),
  removeConfirm: (d) => `确定删除科室「${d.name}」吗？`,
  removeSuccessMsg: '已删除'
})

// 页面特有：医院/员工下拉选项（主任从员工中选取，院长可见全部员工）
async function ensureOptions() {
  try {
    hospitalOptions.value = await listHospitals()
  } catch (e) { hospitalOptions.value = [] }
  try {
    // 全局分页上限 100
    const res = await pageEmployees({ pageNum: 1, pageSize: 100 })
    employeeOptions.value = res.list || []
  } catch (e) { employeeOptions.value = [] }
}

onMounted(async () => { await ensureOptions(); await loadData() })
</script>
