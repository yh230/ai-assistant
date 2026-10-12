<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">科室管理</h2>
        <p class="page-sub">维护科室并绑定科室主任（仅院长/管理员可操作，数据范围：全部）。</p>
      </div>
      <button class="primary-btn" @click="openCreate">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        新增科室
      </button>
    </div>

    <div class="toolbar">
      <select v-model="filterHospital" class="search-input" @change="reload">
        <option :value="null">全部医院</option>
        <option v-for="h in hospitalOptions" :key="h.id" :value="h.id">{{ h.name }}</option>
      </select>
      <input v-model="keyword" class="search-input" placeholder="搜索科室名称" @keyup.enter="reload" />
      <button class="ghost-btn" @click="reload">查询</button>
    </div>

    <div class="table-card">
      <table class="data-table" v-if="list.length > 0">
        <thead>
          <tr>
            <th style="width: 70px">ID</th>
            <th>所属医院</th>
            <th>科室名称</th>
            <th>科室主任</th>
            <th style="width: 80px">状态</th>
            <th style="width: 160px">创建时间</th>
            <th style="width: 200px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in list" :key="d.id">
            <td><code class="id-cell">{{ d.id }}</code></td>
            <td>{{ d.hospitalName || '—' }}</td>
            <td>{{ d.name }}</td>
            <td>{{ d.directorName || '—' }}</td>
            <td>
              <span class="status-tag" :class="d.status === 1 ? 'on' : 'off'">
                {{ d.status === 1 ? '启用' : '禁用' }}
              </span>
            </td>
            <td class="time-cell">{{ formatTime(d.createTime) }}</td>
            <td>
              <button class="link-btn" @click="openEdit(d)">编辑</button>
              <button class="link-btn" @click="toggleStatus(d)">
                {{ d.status === 1 ? '禁用' : '启用' }}
              </button>
              <button class="link-btn danger" @click="remove(d)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-tip">暂无科室数据</div>
    </div>

    <div class="pager" v-if="total > 0">
      <button class="ghost-btn" :disabled="pageNum <= 1" @click="changePage(pageNum - 1)">上一页</button>
      <span class="pager-info">第 {{ pageNum }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
      <button class="ghost-btn" :disabled="pageNum >= totalPages" @click="changePage(pageNum + 1)">下一页</button>
    </div>

    <div class="modal-mask" v-if="showModal" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ isEdit ? '编辑科室' : '新增科室' }}</h3>
          <button class="modal-close" @click="closeModal">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label>所属医院 <span class="req">*</span></label>
            <select v-model.number="form.hospitalId" class="form-input">
              <option :value="null" disabled>请选择医院</option>
              <option v-for="h in hospitalOptions" :key="h.id" :value="h.id">{{ h.name }}</option>
            </select>
          </div>
          <div class="form-row">
            <label>科室名称 <span class="req">*</span></label>
            <input v-model="form.name" class="form-input" placeholder="如 骨科" />
          </div>
          <div class="form-row">
            <label>科室主任</label>
            <select v-model.number="form.directorUserId" class="form-input">
              <option :value="null">未指定</option>
              <option v-for="u in employeeOptions" :key="u.id" :value="u.id">{{ u.name }}（{{ u.username }}）</option>
            </select>
          </div>
          <div class="form-row">
            <label>状态</label>
            <select v-model.number="form.status" class="form-input">
              <option :value="1">启用</option>
              <option :value="0">禁用</option>
            </select>
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
import { ref, onMounted } from 'vue'
import {
  pageDepartments, createDepartment, updateDepartment, deleteDepartment,
  changeDeptStatus, listHospitals, pageEmployees
} from '../api/hospital'
import { useCrudPage } from '../composables/useCrudPage'
import { formatTime } from '../utils/format'

const filterHospital = ref(null)
const hospitalOptions = ref([])
const employeeOptions = ref([])

const {
  list, total, pageNum, pageSize, keyword, totalPages,
  showModal, isEdit, saving, form,
  loadData, reload, changePage, openCreate, openEdit, closeModal, save, toggleStatus, remove
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
    const res = await pageEmployees({ pageNum: 1, pageSize: 1000 })
    employeeOptions.value = res.list || []
  } catch (e) { employeeOptions.value = [] }
}

onMounted(async () => { await ensureOptions(); await loadData() })
</script>

<style scoped>
.search-input { width: 220px; padding: 8px 12px; border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); background: var(--bg-input); color: var(--text-primary); font-size: 14px; outline: none; }
</style>
