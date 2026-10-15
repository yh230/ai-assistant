<template>
  <div class="manage-page">
    <div class="page-header">
      <h2 class="page-title">操作日志</h2>
    </div>

    <div class="toolbar">
      <el-input v-model.trim="filters.module" placeholder="业务模块" style="width: 160px" />
      <el-input v-model.trim="filters.username" placeholder="操作人账号" style="width: 160px" />
      <el-select v-model="filters.status" placeholder="全部结果" clearable style="width: 160px">
        <el-option label="成功" :value="0" />
        <el-option label="失败" :value="1" />
      </el-select>
      <el-date-picker v-model="filters.dateFrom" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" style="width: 160px" />
      <span class="til">至</span>
      <el-date-picker v-model="filters.dateTo" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" style="width: 160px" />
      <el-button @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <div class="table-card">
      <el-table v-if="list.length > 0" :data="list" stripe>
        <el-table-column label="操作时间" width="170">
          <template #default="{ row }"><span class="time-cell">{{ formatTime(row.operTime) }}</span></template>
        </el-table-column>
        <el-table-column label="模块">
          <template #default="{ row }">{{ row.module }}</template>
        </el-table-column>
        <el-table-column label="动作">
          <template #default="{ row }">{{ row.action }}</template>
        </el-table-column>
        <el-table-column label="操作人">
          <template #default="{ row }">{{ row.username || '匿名' }}</template>
        </el-table-column>
        <el-table-column label="身份" width="70">
          <template #default="{ row }">{{ identityText(row.identity) }}</template>
        </el-table-column>
        <el-table-column label="请求">
          <template #default="{ row }">{{ row.method }} {{ row.requestUri }}</template>
        </el-table-column>
        <el-table-column label="IP" width="130">
          <template #default="{ row }">{{ row.ip }}</template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'danger'" effect="light">
              {{ row.status === 0 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时(ms)" width="90">
          <template #default="{ row }">{{ row.costMs }}</template>
        </el-table-column>
        <el-table-column label="错误摘要">
          <template #default="{ row }"><span class="error-cell" :title="row.errorMsg">{{ row.errorMsg }}</span></template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无数据" :image-size="96" />
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

function changePage(p) {
  pageNum.value = p
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
.til { color: var(--text-tertiary); font-size: 13px; }
.error-cell { color: var(--text-secondary); display: inline-block; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }
</style>
