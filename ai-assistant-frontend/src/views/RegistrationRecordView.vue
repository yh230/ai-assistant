<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">挂号记录</h2>
        <p class="page-desc">现场挂号 / 取号 / 退号 / 标记就诊（按角色与数据权限自动过滤，仅见本院/本人数据）</p>
      </div>
      <el-button v-if="can(PERMS.REGISTRATION_REGISTER_CREATE)" type="primary" @click="openWalkIn">+ 现场挂号</el-button>
    </div>

    <!-- 筛选 -->
    <div class="filter-bar">
      <el-input v-model="filters.patientPhone" placeholder="患者手机号" style="width: 180px" clearable @keyup.enter="load" />
      <el-input v-model="filters.patientName" placeholder="患者姓名" style="width: 160px" clearable @keyup.enter="load" />
      <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 130px" @change="load">
        <el-option v-for="o in statusOptions" :key="o.v" :label="o.t" :value="o.v" />
      </el-select>
      <el-select v-model="filters.period" placeholder="全部时段" clearable style="width: 130px" @change="load">
        <el-option label="上午" :value="1" />
        <el-option label="下午" :value="2" />
      </el-select>
      <el-date-picker v-model="filters.dateFrom" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" style="width: 150px" @change="load" />
      <span class="tilde">至</span>
      <el-date-picker v-model="filters.dateTo" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" style="width: 150px" @change="load" />
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <div class="table-card">
      <el-table :data="list" stripe min-width="1000">
        <el-table-column label="凭证号" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.registrationNo }}</template>
        </el-table-column>
        <el-table-column label="患者" width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.patientName }}</template>
        </el-table-column>
        <el-table-column label="医生" width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.doctorName }}</template>
        </el-table-column>
        <el-table-column label="科室" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.departmentName }}</template>
        </el-table-column>
        <el-table-column label="日期/时段" width="110">
          <template #default="{ row }">{{ row.scheduleDate }} {{ row.periodText }}</template>
        </el-table-column>
        <el-table-column label="序号" width="60">
          <template #default="{ row }">{{ row.seqNo }}</template>
        </el-table-column>
        <el-table-column label="来源" width="80">
          <template #default="{ row }">{{ row.sourceTypeText }}</template>
        </el-table-column>
        <el-table-column label="支付" width="80">
          <template #default="{ row }">{{ row.payStatusText }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :class="statusClass(row.status)" effect="light">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }" class="ops">
            <el-button link type="primary" @click="printTicket(row)">凭证</el-button>
            <el-button v-if="can(PERMS.REGISTRATION_REGISTER_TAKE) && row.status === 1" link type="primary" @click="doTake(row)">取号</el-button>
            <el-button v-if="can(PERMS.REGISTRATION_REGISTER_REFUND) && row.status === 2" link type="danger" @click="doRefund(row)">退号</el-button>
            <el-button v-if="can(PERMS.REGISTRATION_REGISTER_TREAT) && row.status === 2" link type="primary" @click="doTreat(row)">就诊</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无挂号记录</template>
      </el-table>
    </div>

    <div class="pager" v-if="total > pageSize">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :current-page="pageNum"
        :page-size="pageSize"
        @current-change="changePage"
      />
    </div>

    <!-- 现场挂号弹窗 -->
    <el-dialog v-model="walkVisible" title="现场窗口挂号" width="500px">
      <el-form label-position="top">
        <el-form-item label="医生 *" required>
          <el-select v-model="wform.doctorUserId" placeholder="请选择医生" style="width: 100%">
            <el-option v-for="d in doctors" :key="d.id" :label="`${d.name}（${d.departmentName}）`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期 *" required>
          <el-date-picker v-model="wform.scheduleDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="时段 *" required>
          <el-select v-model="wform.period" style="width: 100%">
            <el-option label="上午" :value="1" />
            <el-option label="下午" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="患者姓名 *" required>
          <el-input v-model="wform.patientName" />
        </el-form-item>
        <el-form-item label="手机号 *" required>
          <el-input v-model="wform.patientPhone" />
        </el-form-item>
        <el-form-item label="身份证号">
          <el-input v-model="wform.patientIdCard" />
        </el-form-item>
        <el-form-item label="收费金额">
          <el-input-number v-model="wform.payAmount" :step="0.01" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="walkVisible = false">取消</el-button>
        <el-button type="primary" :disabled="saving" @click="submitWalkIn">{{ saving ? '处理中…' : '收费并取号' }}</el-button>
      </template>
    </el-dialog>

    <!-- 凭证弹窗 -->
    <el-dialog v-model="ticketVisible" title="挂号凭证" width="400px">
      <div class="ticket-body" id="ticket-print" v-if="ticket">
        <div class="ticket-row"><span>凭证号</span><b>{{ ticket.registrationNo }}</b></div>
        <div class="ticket-row"><span>医院</span><b>{{ ticket.hospitalName }}</b></div>
        <div class="ticket-row"><span>科室</span><b>{{ ticket.departmentName }}</b></div>
        <div class="ticket-row"><span>医生</span><b>{{ ticket.doctorName }}</b></div>
        <div class="ticket-row"><span>患者</span><b>{{ ticket.patientName }}（{{ ticket.patientPhone }}）</b></div>
        <div class="ticket-row"><span>就诊日期</span><b>{{ ticket.scheduleDate }} {{ ticket.periodText }}</b></div>
        <div class="ticket-row"><span>就诊序号</span><b class="seq">{{ ticket.seqNo }}</b></div>
        <div class="ticket-row"><span>来源</span><b>{{ ticket.sourceTypeText }}</b></div>
        <div class="ticket-row"><span>支付</span><b>{{ ticket.payStatusText }} {{ ticket.payAmount }}</b></div>
        <div class="ticket-row"><span>状态</span><b>{{ ticket.statusText }}</b></div>
      </div>
      <template #footer>
        <el-button @click="ticketVisible = false">关闭</el-button>
        <el-button type="primary" @click="doPrint">打印</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { pageRegistrations, walkInRegister, takeNumber, refundRegistration, treatRegistration, getRegistration } from '../api/registration'
import { pageEmployees } from '../api/hospital'
import { useAuth } from '../store/auth'
import { msg, confirm } from '../utils/message'
import { PERMS } from '../utils/perm'

const { userInfo } = useAuth()
function can(perm) { return (userInfo.value?.permissions || []).includes(perm) }

const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const filters = reactive({ patientPhone: '', patientName: '', status: null, period: null, dateFrom: '', dateTo: '' })
const doctors = ref([])

const statusOptions = [
  { v: 1, t: '已预约' }, { v: 2, t: '已取号' }, { v: 3, t: '已就诊' },
  { v: 4, t: '已取消' }, { v: 5, t: '已退号' }, { v: 6, t: '过期失效' }
]
function statusClass(s) {
  return { 1: 'reserved', 2: 'called', 3: 'treated', 4: 'cancel', 5: 'refund', 6: 'expired' }[s] || ''
}

const walkVisible = ref(false)
const saving = ref(false)
const wform = reactive({ doctorUserId: null, scheduleDate: '', period: 1, patientName: '', patientPhone: '', patientIdCard: '', payAmount: 10 })

const ticketVisible = ref(false)
const ticket = ref(null)

async function loadDoctors() {
  try {
    // 全局分页上限 100
    const res = await pageEmployees({ pageSize: 100 })
    doctors.value = (res.list || []).filter(d => (d.roleNames || '').includes('医生'))
  } catch (e) { /* 忽略 */ }
}

async function load() {
  try {
    const res = await pageRegistrations({
      patientPhone: filters.patientPhone || undefined,
      patientName: filters.patientName || undefined,
      status: filters.status || undefined,
      period: filters.period || undefined,
      dateFrom: filters.dateFrom || undefined,
      dateTo: filters.dateTo || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    list.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    msg('加载失败：' + e.message)
  }
}

function resetFilters() {
  Object.assign(filters, { patientPhone: '', patientName: '', status: null, period: null, dateFrom: '', dateTo: '' })
  pageNum.value = 1
  load()
}

function openWalkIn() {
  Object.assign(wform, { doctorUserId: null, scheduleDate: '', period: 1, patientName: '', patientPhone: '', patientIdCard: '', payAmount: 10 })
  walkVisible.value = true
}
async function submitWalkIn() {
  if (!wform.doctorUserId || !wform.scheduleDate || !wform.patientName || !wform.patientPhone) {
    msg('请完整填写医生、日期、患者信息')
    return
  }
  saving.value = true
  try {
    await walkInRegister({ ...wform })
    msg('现场挂号成功（已取号）')
    walkVisible.value = false
    load()
  } catch (e) {
    msg('挂号失败：' + e.message)
  } finally {
    saving.value = false
  }
}

async function doTake(r) {
  const ok = await confirm(`确认为患者「${r.patientName}」取号？`)
  if (!ok) return
  try { await takeNumber(r.id); msg('取号成功'); load() }
  catch (e) { msg('取号失败：' + e.message) }
}
async function doRefund(r) {
  const ok = await confirm(`确认退号（退款并回收号源）？患者「${r.patientName}」`)
  if (!ok) return
  try { await refundRegistration(r.id, {}); msg('已退号'); load() }
  catch (e) { msg('退号失败：' + e.message) }
}
async function doTreat(r) {
  try { await treatRegistration(r.id); msg('已标记就诊'); load() }
  catch (e) { msg('操作失败：' + e.message) }
}

async function printTicket(r) {
  try {
    const res = await getRegistration(r.id)
    ticket.value = res
    ticketVisible.value = true
  } catch (e) { msg('加载凭证失败') }
}
function doPrint() { window.print() }

function changePage(p) { pageNum.value = p; load() }

onMounted(() => { loadDoctors(); load() })
</script>

<style scoped>
.page { padding: 24px; height: 100%; overflow-y: auto; }
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 16px; }
.page-title { font-size: 20px; margin: 0; color: var(--text-primary); }
.page-desc { margin: 6px 0 0; color: var(--text-secondary); font-size: 13px; }
.filter-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.tilde { color: var(--text-secondary); }
.table-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); overflow-x: auto; }
.pager { display: flex; gap: 12px; align-items: center; justify-content: flex-end; margin-top: 14px; color: var(--text-secondary); }
/* 状态徽标：6 态可区分配色（EP tag type 不足，用自定义主题色区分） */
.el-tag.reserved { background: var(--el-color-primary-light-9); color: var(--el-color-primary); border-color: var(--el-color-primary-light-7); }
.el-tag.called { background: var(--el-color-warning-light-9); color: var(--el-color-warning); border-color: var(--el-color-warning-light-7); }
.el-tag.treated { background: var(--el-color-success-light-9); color: var(--el-color-success); border-color: var(--el-color-success-light-7); }
.el-tag.cancel { background: var(--el-fill-color); color: var(--el-text-color-secondary); border-color: var(--el-border-color); }
.el-tag.refund { background: var(--el-color-danger-light-9); color: var(--el-color-danger); border-color: var(--el-color-danger-light-7); }
.el-tag.expired { background: var(--el-fill-color); color: var(--el-text-color-secondary); border-color: var(--el-border-color); }
.ticket-body { padding: 8px 0; display: flex; flex-direction: column; gap: 10px; }
.ticket-row { display: flex; justify-content: space-between; font-size: 14px; color: var(--text-secondary); }
.ticket-row b { color: var(--text-primary); }
.ticket-row .seq { color: var(--primary); font-size: 18px; }
</style>
