<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">医院管理</h2>
        <p class="page-sub">维护医院基础信息（仅院长/管理员可操作，数据范围：全部）。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增医院</el-button>
    </div>

    <div class="toolbar">
      <el-input v-model="keyword" placeholder="搜索医院名称" style="width: 240px" @keyup.enter="reload" />
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
        <el-table-column prop="name" label="名称" />
        <el-table-column label="地址">
          <template #default="{ row }">{{ row.address || '—' }}</template>
        </el-table-column>
        <el-table-column label="电话">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
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
      <el-empty v-else-if="!loading && !error" description="暂无医院数据" :image-size="96" />
    </div>

    <Pager :page-num="pageNum" :page-size="pageSize" :total="total" @change="changePage" />

    <el-dialog v-model="showModal" :title="isEdit ? '编辑医院' : '新增医院'" width="520px">
      <el-form label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 市第一人民医院" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" placeholder="选填" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="form.phone" placeholder="选填" />
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
import { onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { pageHospitals, createHospital, updateHospital, deleteHospital, changeHospitalStatus } from '../api/hospital'
import { useCrudPage } from '../composables/useCrudPage'
import { formatTime } from '../utils/format'
import Pager from '../components/Pager.vue'

const {
  list, total, pageNum, pageSize, keyword, totalPages,
  loading, error,
  showModal, isEdit, saving, form,
  loadData, retry, reload, changePage, openCreate, openEdit, closeModal, save, toggleStatus, remove
} = useCrudPage({
  fetchPage: (params) => pageHospitals(params),
  emptyForm: () => ({ name: '', address: '', phone: '', status: 1 }),
  toForm: (h) => ({ name: h.name, address: h.address || '', phone: h.phone || '', status: h.status === 0 ? 0 : 1 }),
  validate: (f) => (f.name.trim() ? null : '请填写医院名称'),
  persist: (f, id) => {
    const payload = { name: f.name.trim(), address: f.address.trim(), phone: f.phone.trim(), status: f.status }
    return id ? updateHospital(id, payload) : createHospital(payload)
  },
  toggle: (h, next) => changeHospitalStatus(h.id, next),
  toggleConfirm: (next, h) => `确定${next === 1 ? '启用' : '禁用'}医院「${h.name}」吗？`,
  remove: (h) => deleteHospital(h.id),
  removeConfirm: (h) => `确定删除医院「${h.name}」吗？其下科室需先清空。`,
  removeSuccessMsg: '已删除'
})

onMounted(loadData)
</script>
