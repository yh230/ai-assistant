<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">挂号记录</h2>
        <p class="page-desc">现场挂号 / 取号 / 退号 / 标记就诊（按角色与数据权限自动过滤，仅见本院/本人数据）</p>
      </div>
      <button v-if="can(PERMS.REGISTRATION_REGISTER_CREATE)" class="btn primary" @click="openWalkIn">+ 现场挂号</button>
    </div>

    <!-- 筛选 -->
    <div class="filter-bar">
      <input v-model="filters.patientPhone" class="input" placeholder="患者手机号" @keyup.enter="load" />
      <input v-model="filters.patientName" class="input" placeholder="患者姓名" @keyup.enter="load" />
      <select v-model="filters.status" class="input" @change="load">
        <option :value="null">全部状态</option>
        <option v-for="o in statusOptions" :key="o.v" :value="o.v">{{ o.t }}</option>
      </select>
      <select v-model="filters.period" class="input" @change="load">
        <option :value="null">全部时段</option>
        <option :value="1">上午</option>
        <option :value="2">下午</option>
      </select>
      <input v-model="filters.dateFrom" type="date" class="input" @change="load" />
      <span class="tilde">至</span>
      <input v-model="filters.dateTo" type="date" class="input" @change="load" />
      <button class="btn" @click="resetFilters">重置</button>
    </div>

    <div class="table-card">
      <table class="data-table">
        <thead>
          <tr>
            <th style="width:160px">凭证号</th>
            <th style="width:90px">患者</th>
            <th style="width:90px">医生</th>
            <th style="width:100px">科室</th>
            <th style="width:110px">日期/时段</th>
            <th style="width:60px">序号</th>
            <th style="width:80px">来源</th>
            <th style="width:80px">支付</th>
            <th style="width:90px">状态</th>
            <th style="width:230px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in list" :key="r.id">
            <td :title="r.registrationNo">{{ r.registrationNo }}</td>
            <td :title="r.patientName">{{ r.patientName }}</td>
            <td :title="r.doctorName">{{ r.doctorName }}</td>
            <td :title="r.departmentName">{{ r.departmentName }}</td>
            <td>{{ r.scheduleDate }} {{ r.periodText }}</td>
            <td>{{ r.seqNo }}</td>
            <td>{{ r.sourceTypeText }}</td>
            <td>{{ r.payStatusText }}</td>
            <td><span class="status-tag" :class="statusClass(r.status)">{{ r.statusText }}</span></td>
            <td class="ops">
              <button class="link" @click="printTicket(r)">凭证</button>
              <button v-if="can(PERMS.REGISTRATION_REGISTER_TAKE) && r.status === 1" class="link" @click="doTake(r)">取号</button>
              <button v-if="can(PERMS.REGISTRATION_REGISTER_REFUND) && r.status === 2" class="link danger" @click="doRefund(r)">退号</button>
              <button v-if="can(PERMS.REGISTRATION_REGISTER_TREAT) && r.status === 2" class="link" @click="doTreat(r)">就诊</button>
            </td>
          </tr>
          <tr v-if="!list.length">
            <td colspan="10" class="empty">暂无挂号记录</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager" v-if="total > pageSize">
      <button class="btn" :disabled="pageNum <= 1" @click="pageNum--; load()">上一页</button>
      <span>{{ pageNum }} / {{ Math.ceil(total / pageSize) }}</span>
      <button class="btn" :disabled="pageNum >= Math.ceil(total / pageSize)" @click="pageNum++; load()">下一页</button>
    </div>

    <!-- 现场挂号弹窗 -->
    <div class="modal-mask" v-if="walkVisible" @click.self="walkVisible = false">
      <div class="modal">
        <div class="modal-header">
          <h3>现场窗口挂号</h3>
          <button class="modal-close" @click="walkVisible = false">×</button>
        </div>
        <div class="modal-body">
          <label class="field"><span>医生 *</span>
            <select v-model="wform.doctorUserId" class="input">
              <option :value="null">请选择医生</option>
              <option v-for="d in doctors" :key="d.id" :value="d.id">{{ d.name }}（{{ d.departmentName }}）</option>
            </select>
          </label>
          <label class="field"><span>日期 *</span><input v-model="wform.scheduleDate" type="date" class="input" /></label>
          <label class="field"><span>时段 *</span>
            <select v-model="wform.period" class="input"><option :value="1">上午</option><option :value="2">下午</option></select>
          </label>
          <label class="field"><span>患者姓名 *</span><input v-model="wform.patientName" class="input" /></label>
          <label class="field"><span>手机号 *</span><input v-model="wform.patientPhone" class="input" /></label>
          <label class="field"><span>身份证号</span><input v-model="wform.patientIdCard" class="input" /></label>
          <label class="field"><span>收费金额</span><input v-model.number="wform.payAmount" type="number" step="0.01" class="input" /></label>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="walkVisible = false">取消</button>
          <button class="btn primary" :disabled="saving" @click="submitWalkIn">{{ saving ? '处理中…' : '收费并取号' }}</button>
        </div>
      </div>
    </div>

    <!-- 凭证弹窗 -->
    <div class="modal-mask" v-if="ticketVisible" @click.self="ticketVisible = false">
      <div class="modal ticket" id="ticket-print">
        <div class="modal-header">
          <h3>挂号凭证</h3>
          <button class="modal-close" @click="ticketVisible = false">×</button>
        </div>
        <div class="ticket-body" v-if="ticket">
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
        <div class="modal-footer">
          <button class="btn" @click="ticketVisible = false">关闭</button>
          <button class="btn primary" @click="doPrint">打印</button>
        </div>
      </div>
    </div>
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
    const res = await pageEmployees({ pageSize: 200 })
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

onMounted(() => { loadDoctors(); load() })
</script>

<style scoped>
.page { padding: 24px; height: 100%; overflow-y: auto; }
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 16px; }
.page-title { font-size: 20px; margin: 0; color: var(--text-primary); }
.page-desc { margin: 6px 0 0; color: var(--text-secondary); font-size: 13px; }
.filter-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.input { background: var(--bg-card); border: 1px solid var(--border-color); color: var(--text-primary);
  border-radius: var(--border-radius-sm); padding: 8px 10px; font-size: 13px; }
.tilde { color: var(--text-secondary); }
.table-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); overflow-x: auto; }
.data-table { width: 100%; border-collapse: collapse; font-size: 14px; min-width: 1000px; }
.data-table th { text-align: left; padding: 12px 16px; background: var(--bg-hover); color: var(--text-secondary);
  font-weight: 600; border-bottom: 1px solid var(--border-color); }
.data-table td { padding: 12px 16px; border-bottom: 1px solid var(--border-color); color: var(--text-primary); }
.data-table th, .data-table td { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.status-tag { padding: 3px 10px; border-radius: 12px; font-size: 12px; }
.status-tag.reserved { background: rgba(59,130,246,.15); color: #3b82f6; }
.status-tag.called { background: rgba(168,85,247,.15); color: #a855f7; }
.status-tag.treated { background: rgba(22,163,74,.15); color: #16a34a; }
.status-tag.cancel { background: rgba(148,163,184,.2); color: #64748b; }
.status-tag.refund { background: rgba(239,68,68,.15); color: #ef4444; }
.status-tag.expired { background: rgba(148,163,184,.2); color: #64748b; }
.ops { display: flex; gap: 8px; }
.link { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 13px; padding: 0; }
.link.danger { color: #ef4444; }
.empty { text-align: center; color: var(--text-secondary); padding: 30px; }
.pager { display: flex; gap: 12px; align-items: center; justify-content: flex-end; margin-top: 14px; color: var(--text-secondary); }
.btn { background: var(--bg-card); border: 1px solid var(--border-color); color: var(--text-primary);
  border-radius: var(--border-radius-sm); padding: 8px 14px; cursor: pointer; font-size: 13px; }
.btn.primary { background: var(--primary); color: #fff; border-color: var(--primary); }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 50; }
.modal { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); width: 460px; max-width: 92vw; }
.modal.ticket { width: 380px; }
.modal-header { display: flex; justify-content: space-between; align-items: center; padding: 16px; border-bottom: 1px solid var(--border-color); }
.modal-header h3 { margin: 0; font-size: 16px; color: var(--text-primary); }
.modal-close { background: none; border: none; color: var(--text-secondary); font-size: 22px; cursor: pointer; }
.modal-body { padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.field { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-secondary); }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px; border-top: 1px solid var(--border-color); }
.ticket-body { padding: 16px; display: flex; flex-direction: column; gap: 10px; }
.ticket-row { display: flex; justify-content: space-between; font-size: 14px; color: var(--text-secondary); }
.ticket-row b { color: var(--text-primary); }
.ticket-row .seq { color: var(--primary); font-size: 18px; }
</style>
