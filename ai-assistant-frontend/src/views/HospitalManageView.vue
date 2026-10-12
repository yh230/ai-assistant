<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">医院管理</h2>
        <p class="page-sub">维护医院基础信息（仅院长/管理员可操作，数据范围：全部）。</p>
      </div>
      <button class="primary-btn" @click="openCreate">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        新增医院
      </button>
    </div>

    <div class="toolbar">
      <input v-model="keyword" class="search-input" placeholder="搜索医院名称" @keyup.enter="reload" />
      <button class="ghost-btn" @click="reload">查询</button>
    </div>

    <div class="table-card">
      <table class="data-table" v-if="list.length > 0">
        <thead>
          <tr>
            <th style="width: 70px">ID</th>
            <th>名称</th>
            <th>地址</th>
            <th>电话</th>
            <th style="width: 80px">状态</th>
            <th style="width: 160px">创建时间</th>
            <th style="width: 200px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="h in list" :key="h.id">
            <td><code class="id-cell">{{ h.id }}</code></td>
            <td>{{ h.name }}</td>
            <td>{{ h.address || '—' }}</td>
            <td>{{ h.phone || '—' }}</td>
            <td>
              <span class="status-tag" :class="h.status === 1 ? 'on' : 'off'">
                {{ h.status === 1 ? '启用' : '禁用' }}
              </span>
            </td>
            <td class="time-cell">{{ formatTime(h.createTime) }}</td>
            <td>
              <button class="link-btn" @click="openEdit(h)">编辑</button>
              <button class="link-btn" @click="toggleStatus(h)">
                {{ h.status === 1 ? '禁用' : '启用' }}
              </button>
              <button class="link-btn danger" @click="remove(h)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-tip">暂无医院数据</div>
    </div>

    <div class="pager" v-if="total > 0">
      <button class="ghost-btn" :disabled="pageNum <= 1" @click="changePage(pageNum - 1)">上一页</button>
      <span class="pager-info">第 {{ pageNum }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
      <button class="ghost-btn" :disabled="pageNum >= totalPages" @click="changePage(pageNum + 1)">下一页</button>
    </div>

    <div class="modal-mask" v-if="showModal" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ isEdit ? '编辑医院' : '新增医院' }}</h3>
          <button class="modal-close" @click="closeModal">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label>名称 <span class="req">*</span></label>
            <input v-model="form.name" class="form-input" placeholder="如 市第一人民医院" />
          </div>
          <div class="form-row">
            <label>地址</label>
            <input v-model="form.address" class="form-input" placeholder="选填" />
          </div>
          <div class="form-row">
            <label>电话</label>
            <input v-model="form.phone" class="form-input" placeholder="选填" />
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
import { onMounted } from 'vue'
import { pageHospitals, createHospital, updateHospital, deleteHospital, changeHospitalStatus } from '../api/hospital'
import { useCrudPage } from '../composables/useCrudPage'
import { formatTime } from '../utils/format'

const {
  list, total, pageNum, pageSize, keyword, totalPages,
  showModal, isEdit, saving, form,
  loadData, reload, changePage, openCreate, openEdit, closeModal, save, toggleStatus, remove
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
