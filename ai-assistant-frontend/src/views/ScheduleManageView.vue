<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">排班与号源</h2>
        <p class="page-desc">维护医生出诊排班，系统按排班预生成每日号源（门诊管理员，本院数据）</p>
      </div>
      <el-button type="primary" @click="openCreate">+ 新建排班</el-button>
    </div>

    <!-- 筛选 -->
    <div class="filter-bar">
      <el-select v-model="filters.doctorUserId" placeholder="全部医生" clearable style="width: 180px" @change="load">
        <el-option v-for="d in doctors" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-select v-model="filters.period" placeholder="全部时段" clearable style="width: 140px" @change="load">
        <el-option label="上午" :value="1" />
        <el-option label="下午" :value="2" />
      </el-select>
      <el-date-picker v-model="filters.dateFrom" type="date" placeholder="开始日期" value-format="YYYY-MM-DD" style="width: 160px" @change="load" />
      <span class="tilde">至</span>
      <el-date-picker v-model="filters.dateTo" type="date" placeholder="结束日期" value-format="YYYY-MM-DD" style="width: 160px" @change="load" />
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <!-- 列表 -->
    <div class="table-card">
      <el-table :data="list" stripe>
        <el-table-column label="医生" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.doctorName }}</template>
        </el-table-column>
        <el-table-column label="科室" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.departmentName }}</template>
        </el-table-column>
        <el-table-column label="日期" width="120">
          <template #default="{ row }">{{ row.scheduleDate }}</template>
        </el-table-column>
        <el-table-column label="时段" width="70">
          <template #default="{ row }">{{ row.periodText }}</template>
        </el-table-column>
        <el-table-column label="号源总数" width="90">
          <template #default="{ row }">{{ row.totalLimit }}</template>
        </el-table-column>
        <el-table-column label="已挂/余量" width="90">
          <template #default="{ row }">
            <span class="cnt">{{ row.registeredCount }}/</span>
            <span class="remain">{{ row.remaining }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="70">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="genSource(row)">生成号源</el-button>
            <el-button link type="primary" @click="viewPool(row)">号源</el-button>
            <el-button link type="danger" @click="remove(row)">停用</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无排班数据</template>
      </el-table>
    </div>

    <div class="pager" v-if="total > pageSize">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :current-page="pageNum"
        :page-size="pageSize"
        @current-change="(p) => { pageNum = p; load() }"
      />
    </div>

    <!-- 号源池弹窗 -->
    <el-dialog v-model="poolVisible" :title="`号源池 - ${poolSchedule?.doctorName}（${poolSchedule?.scheduleDate} ${poolSchedule?.periodText}）`" width="560px">
      <div class="pool-summary">
        总数 <b>{{ pool?.totalLimit }}</b> · 已占用 <b>{{ pool?.usedCount }}</b> · 余量 <b class="remain">{{ pool?.remainingCount }}</b>
      </div>
      <div class="pool-grid">
        <div v-for="it in pool?.items || []" :key="it.id"
             class="pool-cell" :class="it.status === 1 ? 'free' : 'used'" :title="'序号' + it.seqNo">
          {{ it.seqNo }}
          <small>{{ it.statusText }}</small>
        </div>
      </div>
      <template #footer>
        <el-button @click="poolVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 排班表单弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑排班' : '新建排班'" width="460px">
      <el-form label-position="top">
        <el-form-item label="医生" required>
          <el-select v-model="form.doctorUserId" placeholder="请选择医生" style="width: 100%">
            <el-option v-for="d in doctors" :key="d.id" :label="`${d.name}（${d.departmentName}）`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="出诊日期" required>
          <el-date-picker v-model="form.scheduleDate" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="时段" required>
          <el-select v-model="form.period" style="width: 100%">
            <el-option label="上午" :value="1" />
            <el-option label="下午" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="号源总数" required>
          <el-input-number v-model="form.totalLimit" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="可选" />
        </el-form-item>
        <p class="hint">提示：修改排班后系统会作废旧号源并按新配置重生成号源。</p>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :disabled="saving" @click="submit">{{ saving ? '保存中…' : '保存' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { pageSchedules, saveSchedule, deleteSchedule, generateSources, listNumberSourcePools } from '../api/registration'
import { pageEmployees } from '../api/hospital'
import { useCrudPage } from '../composables/useCrudPage'
import { msg } from '../utils/message'

const filters = reactive({ doctorUserId: null, period: null, dateFrom: '', dateTo: '' })
const doctors = ref([])

const {
  list, total, pageSize, saving, form,
  pageNum: pageNumRef, showModal: formVisibleRef,
  loadData: load, openCreate, openEdit, save: submit, remove
} = useCrudPage({
  // keyword 本页无筛选；doctorUserId/period/dateFrom/dateTo 为页面筛选条件，在 fetchPage 内拼装
  fetchPage: (params) => pageSchedules({
    doctorUserId: filters.doctorUserId || undefined,
    period: filters.period || undefined,
    dateFrom: filters.dateFrom || undefined,
    dateTo: filters.dateTo || undefined,
    pageNum: params.pageNum,
    pageSize: params.pageSize
  }),
  emptyForm: () => ({ id: null, doctorUserId: null, scheduleDate: '', period: 1, totalLimit: 30, remark: '' }),
  toForm: (s) => ({
    id: s.id, doctorUserId: s.doctorUserId, scheduleDate: s.scheduleDate,
    period: s.period, totalLimit: s.totalLimit, remark: s.remark
  }),
  validate: (f) => ((!f.doctorUserId || !f.scheduleDate || !f.totalLimit) ? '请完整填写医生、日期与号源总数' : null),
  persist: async (f) => {
    await saveSchedule({ ...f })
    msg('保存成功')
  },
  remove: (s) => deleteSchedule(s.id),
  removeConfirm: (s) => `确认停用排班「${s.doctorName} ${s.scheduleDate} ${s.periodText}」？空闲号源将释放。`,
  removeSuccessMsg: '已停用'
})

// 薄包装（同名桥接）：模板中 formVisible / pageNum 有直接读写（formVisible = false、pageNum--、pageNum++），
// 而骨架返回的为解构 ref，用 computed get/set 桥接，保证模板读写与骨架状态完全联动（原效果不变）
const formVisible = computed({
  get: () => formVisibleRef.value,
  set: (v) => { formVisibleRef.value = v }
})
const pageNum = computed({
  get: () => pageNumRef.value,
  set: (v) => { pageNumRef.value = v }
})

// 页面特有：号源池弹窗（不并入骨架）
const poolVisible = ref(false)
const pool = ref(null)
const poolSchedule = ref(null)

async function loadDoctors() {
  try {
    const res = await pageEmployees({ pageSize: 200 })
    // 仅取医生角色
    doctors.value = (res.list || []).filter(d => (d.roleNames || '').includes('医生'))
  } catch (e) { /* 忽略 */ }
}

function resetFilters() {
  filters.doctorUserId = null
  filters.period = null
  filters.dateFrom = ''
  filters.dateTo = ''
  pageNum.value = 1
  load()
}

// 页面特有：生成号源（不并入骨架）
async function genSource(s) {
  try {
    await generateSources(s.id)
    msg('号源已生成/重置')
    load()
  } catch (e) {
    msg('生成失败：' + e.message)
  }
}

// 页面特有：查看号源池弹窗（不并入骨架）
async function viewPool(s) {
  poolSchedule.value = s
  try {
    const res = await listNumberSourcePools({ doctorUserId: s.doctorUserId, date: s.scheduleDate, period: s.period })
    pool.value = (res || []).find(p => p.scheduleId === s.id) || res?.[0] || null
    poolVisible.value = true
  } catch (e) {
    msg('加载号源失败：' + e.message)
  }
}

onMounted(() => {
  loadDoctors()
  load()
})
</script>

<style scoped>
.page { padding: 24px; height: 100%; overflow-y: auto; }
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 16px; }
.page-title { font-size: 20px; margin: 0; color: var(--text-primary); }
.page-desc { margin: 6px 0 0; color: var(--text-secondary); font-size: 13px; }
.filter-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.tilde { color: var(--text-secondary); }
.table-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); overflow-x: auto; }
.cnt { color: var(--text-secondary); }
.remain { color: var(--success, #16a34a); font-weight: 600; }
.pager { display: flex; gap: 12px; align-items: center; justify-content: flex-end; margin-top: 14px; color: var(--text-secondary); }
.hint { font-size: 12px; color: var(--text-secondary); margin: 0; }
.pool-summary { padding: 0 0 12px; color: var(--text-secondary); font-size: 13px; }
.pool-grid { display: grid; grid-template-columns: repeat(10, 1fr); gap: 8px; max-height: 320px; overflow-y: auto; }
.pool-cell { display: flex; flex-direction: column; align-items: center; justify-content: center; aspect-ratio: 1;
  border-radius: var(--border-radius-sm); font-size: 14px; font-weight: 600; }
.pool-cell.free { background: rgba(13,148,136,.12); color: #0d9488; }
.pool-cell.used { background: rgba(239,68,68,.12); color: #ef4444; }
.pool-cell small { font-size: 10px; font-weight: 400; }
</style>
