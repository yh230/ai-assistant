<template>
  <div class="manage-page">
    <div class="page-header">
      <h2 class="page-title">操作日志</h2>
    </div>

    <div class="toolbar">
      <input v-model.trim="filters.module" class="search-input" placeholder="业务模块" />
      <input v-model.trim="filters.username" class="search-input" placeholder="操作人账号" />
      <select v-model="filters.status" class="search-input">
        <option :value="''">全部结果</option>
        <option :value="0">成功</option>
        <option :value="1">失败</option>
      </select>
      <input type="date" v-model="filters.dateFrom" class="search-input" />
      <span class="til">至</span>
      <input type="date" v-model="filters.dateTo" class="search-input" />
      <button class="ghost-btn" @click="search">查询</button>
      <button class="ghost-btn" @click="reset">重置</button>
    </div>

    <div class="table-card">
      <table class="data-table" v-if="list.length > 0">
        <thead>
          <tr>
            <th style="width: 160px">操作时间</th>
            <th>模块</th>
            <th>动作</th>
            <th>操作人</th>
            <th style="width: 70px">身份</th>
            <th>请求</th>
            <th style="width: 130px">IP</th>
            <th style="width: 80px">结果</th>
            <th style="width: 90px">耗时(ms)</th>
            <th>错误摘要</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in list" :key="row.id">
            <td class="time-cell">{{ formatTime(row.operTime) }}</td>
            <td>{{ row.module }}</td>
            <td>{{ row.action }}</td>
            <td>{{ row.username || '匿名' }}</td>
            <td>{{ identityText(row.identity) }}</td>
            <td>{{ row.method }} {{ row.requestUri }}</td>
            <td>{{ row.ip }}</td>
            <td>
              <span class="status-tag" :class="row.status === 0 ? 'success' : 'fail'">{{ row.status === 0 ? '成功' : '失败' }}</span>
            </td>
            <td>{{ row.costMs }}</td>
            <td class="error-cell" :title="row.errorMsg">{{ row.errorMsg }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-tip">暂无数据</div>
    </div>

    <div class="pager" v-if="total > 0">
      <button class="ghost-btn" :disabled="pageNum <= 1" @click="turn(-1)">上一页</button>
      <span class="pager-info">第 {{ pageNum }} 页 / 共 {{ total }} 条</span>
      <button class="ghost-btn" :disabled="pageNum * pageSize >= total" @click="turn(1)">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { operLogPage } from '../api/sysoperlog'

const filters = reactive({ module: '', username: '', status: '', dateFrom: '', dateTo: '' })
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

function params() {
  const p = { pageNum: pageNum.value, pageSize: pageSize.value }
  if (filters.module) p.module = filters.module
  if (filters.username) p.username = filters.username
  if (filters.status !== '') p.status = filters.status
  if (filters.dateFrom) p.dateFrom = filters.dateFrom
  if (filters.dateTo) p.dateTo = filters.dateTo
  return p
}

async function load() {
  const data = await operLogPage(params())
  list.value = data.list || []
  total.value = data.total || 0
}

function search() {
  pageNum.value = 1
  load()
}

function reset() {
  filters.module = ''
  filters.username = ''
  filters.status = ''
  filters.dateFrom = ''
  filters.dateTo = ''
  search()
}

function turn(delta) {
  pageNum.value += delta
  load()
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : ''
}

function identityText(identity) {
  if (identity === 'staff') return '员工'
  if (identity === 'patient') return '患者'
  return '匿名'
}

onMounted(load)
</script>

<style scoped>
/* 筛选栏/徽标样式对齐现有管理页（AttendanceManageView 同款写法），不改全局样式 */
.toolbar { align-items: center; flex-wrap: wrap; }
.search-input { width: 160px; }
.til { color: var(--text-tertiary); font-size: 13px; }
.status-tag.success { background: rgba(34, 197, 94, 0.15); color: var(--success); }
.status-tag.fail { background: rgba(239, 68, 68, 0.15); color: var(--danger); }
.error-cell { color: var(--text-secondary); max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
