<template>
  <div class="patient-page">
    <div class="patient-card">
      <h2 class="title">门诊患者自助服务</h2>
      <p class="sub">线上预约挂号 · 凭手机号/身份证查询本人预约</p>

      <div class="tabs">
        <button class="tab" :class="{ active: tab === 'book' }" @click="tab = 'book'">在线预约</button>
        <button class="tab" :class="{ active: tab === 'query' }" @click="tab = 'query'">我的预约</button>
      </div>

      <!-- 预约 -->
      <div v-if="tab === 'book'">
        <div class="form-row">
          <label>就诊日期</label>
          <input v-model="bookDate" type="date" class="input" @change="loadSlots" />
        </div>
        <p class="section-tip">可选号源（余量 > 0 可约）</p>
        <div class="slot-list" v-if="slots.length">
          <div v-for="s in slots" :key="s.id" class="slot"
               :class="{ disabled: s.remaining <= 0 }" @click="s.remaining > 0 && pickSlot(s)">
            <div class="slot-doc">{{ s.doctorName }}</div>
            <div class="slot-meta">{{ s.departmentName }} · {{ s.scheduleDate }} {{ s.periodText }}</div>
            <div class="slot-remain">余量 <b>{{ s.remaining }}</b> / {{ s.totalLimit }}</div>
          </div>
        </div>
        <div class="empty" v-else>当日暂无可约号源</div>

        <!-- 填写患者信息 -->
        <div class="book-form" v-if="picked">
          <div class="picked-tip">已选：{{ picked.doctorName }}（{{ picked.scheduleDate }} {{ picked.periodText }}）</div>
          <label class="field"><span>姓名 *</span><input v-model="form.patientName" class="input" /></label>
          <label class="field"><span>手机号 *</span><input v-model="form.patientPhone" class="input" /></label>
          <label class="field"><span>身份证号 *</span><input v-model="form.patientIdCard" class="input" /></label>
          <button class="btn primary block" :disabled="booking" @click="submitBook">提交预约（¥10）</button>
          <p class="hint">提交后请在 15 分钟内完成支付，超时号源自动释放。</p>
        </div>
      </div>

      <!-- 查询 -->
      <div v-else>
        <div class="form-row query-row">
          <input v-model="q.phone" class="input" placeholder="手机号" />
          <input v-model="q.idCard" class="input" placeholder="身份证号" />
          <input v-model="q.registrationNo" class="input" placeholder="预约号（可选）" />
          <button class="btn" @click="doQuery">查询</button>
        </div>
        <div class="result-list" v-if="myList.length">
          <div v-for="r in myList" :key="r.id" class="result">
            <div class="result-top">
              <b>{{ r.doctorName }}</b>
              <span class="r-status" :class="statusClass(r.status)">{{ r.statusText }}</span>
            </div>
            <div class="result-meta">{{ r.scheduleDate }} {{ r.periodText }} · 序号 {{ r.seqNo }} · {{ r.sourceTypeText }}</div>
            <div class="result-meta">凭证号 {{ r.registrationNo }}</div>
            <div class="result-ops" v-if="r.status === 1">
              <button class="link" @click="pay(r)">确认支付</button>
              <button class="link danger" @click="cancel(r)">取消预约</button>
            </div>
            <div class="result-ops" v-else-if="r.status === 2">
              <span class="tip">请凭预约号/身份证到窗口或自助机取号</span>
            </div>
          </div>
        </div>
        <div class="empty" v-else-if="queried">未查询到预约记录</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { listPublicSchedules, createAppointment, queryAppointments, confirmPay, cancelAppointment } from '../api/registration'
import { msg, confirm } from '../utils/message'

const tab = ref('book')
const bookDate = ref(new Date().toISOString().slice(0, 10))
const slots = ref([])
const picked = ref(null)
const booking = ref(false)
const form = reactive({ patientName: '', patientPhone: '', patientIdCard: '' })

const q = reactive({ phone: '', idCard: '', registrationNo: '' })
const myList = ref([])
const queried = ref(false)

async function loadSlots() {
  picked.value = null
  try {
    const res = await listPublicSchedules({ date: bookDate.value })
    slots.value = res || []
  } catch (e) {
    msg('加载号源失败：' + e.message)
  }
}
function pickSlot(s) { picked.value = s }

async function submitBook() {
  if (!form.patientName || !form.patientPhone || !form.patientIdCard) {
    msg('请完整填写患者信息')
    return
  }
  if (!/^\d{17}[\dXx]$/.test(form.patientIdCard)) {
    msg('身份证号格式不正确')
    return
  }
  booking.value = true
  try {
    await createAppointment({
      doctorUserId: picked.value.doctorUserId,
      scheduleDate: picked.value.scheduleDate,
      period: picked.value.period,
      patientName: form.patientName,
      patientPhone: form.patientPhone,
      patientIdCard: form.patientIdCard
    })
    msg('预约成功，请尽快完成支付')
    picked.value = null
    Object.assign(form, { patientName: '', patientPhone: '', patientIdCard: '' })
    loadSlots()
  } catch (e) {
    msg('预约失败：' + e.message)
  } finally {
    booking.value = false
  }
}

async function doQuery() {
  if (!q.registrationNo && !(q.phone && q.idCard)) {
    msg('请输入预约号，或手机号+身份证')
    return
  }
  try {
    const res = await queryAppointments({ phone: q.phone || undefined, idCard: q.idCard || undefined, registrationNo: q.registrationNo || undefined })
    myList.value = res || []
    queried.value = true
  } catch (e) {
    msg('查询失败：' + e.message)
  }
}
async function pay(r) {
  try { await confirmPay(r.id, { patientIdCard: r.patientIdCard }); msg('支付成功'); doQuery() }
  catch (e) { msg('支付失败：' + e.message) }
}
async function cancel(r) {
  const ok = await confirm('确认取消该预约？号源将释放。')
  if (!ok) return
  try { await cancelAppointment(r.id, { patientIdCard: r.patientIdCard, reason: '患者主动取消' }); msg('已取消'); doQuery() }
  catch (e) { msg('取消失败：' + e.message) }
}

function statusClass(s) {
  return { 1: 'reserved', 2: 'called', 3: 'treated', 4: 'cancel', 5: 'refund', 6: 'expired' }[s] || ''
}

onMounted(loadSlots)
</script>

<style scoped>
.patient-page { padding: 30px 16px; display: flex; justify-content: center; min-height: 100%; background: var(--bg-hover); }
.patient-card { width: 560px; max-width: 100%; background: var(--bg-card); border: 1px solid var(--border-color);
  border-radius: var(--border-radius); padding: 24px; }
.title { margin: 0; font-size: 20px; color: var(--text-primary); text-align: center; }
.sub { margin: 6px 0 18px; color: var(--text-secondary); font-size: 13px; text-align: center; }
.tabs { display: flex; gap: 8px; margin-bottom: 18px; }
.tab { flex: 1; padding: 10px; background: var(--bg-hover); border: 1px solid var(--border-color); color: var(--text-secondary);
  border-radius: var(--border-radius-sm); cursor: pointer; font-size: 14px; }
.tab.active { background: var(--primary); color: #fff; border-color: var(--primary); }
.form-row { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; }
.form-row.query-row { flex-wrap: wrap; }
.input { flex: 1; background: var(--bg-card); border: 1px solid var(--border-color); color: var(--text-primary);
  border-radius: var(--border-radius-sm); padding: 9px 12px; font-size: 13px; min-width: 0; }
.section-tip { color: var(--text-secondary); font-size: 13px; margin: 4px 0 10px; }
.slot-list { display: flex; flex-direction: column; gap: 10px; max-height: 320px; overflow-y: auto; }
.slot { border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); padding: 12px; cursor: pointer; transition: .2s; }
.slot:hover { border-color: var(--primary); }
.slot.disabled { opacity: .5; cursor: not-allowed; }
.slot-doc { font-weight: 600; color: var(--text-primary); }
.slot-meta { color: var(--text-secondary); font-size: 13px; margin: 4px 0; }
.slot-remain { color: var(--text-secondary); font-size: 13px; }
.slot-remain b { color: var(--success, #16a34a); }
.empty { color: var(--text-secondary); text-align: center; padding: 30px; }
.book-form { margin-top: 16px; border-top: 1px solid var(--border-color); padding-top: 16px; display: flex; flex-direction: column; gap: 12px; }
.picked-tip { font-size: 13px; color: var(--primary); }
.field { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-secondary); }
.btn { background: var(--bg-card); border: 1px solid var(--border-color); color: var(--text-primary);
  border-radius: var(--border-radius-sm); padding: 9px 16px; cursor: pointer; font-size: 13px; }
.btn.primary { background: var(--primary); color: #fff; border-color: var(--primary); }
.btn.block { width: 100%; }
.hint { font-size: 12px; color: var(--text-secondary); margin: 0; }
.result-list { display: flex; flex-direction: column; gap: 12px; }
.result { border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); padding: 14px; }
.result-top { display: flex; justify-content: space-between; align-items: center; }
.result-top b { color: var(--text-primary); }
.result-meta { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.r-status { padding: 2px 9px; border-radius: 10px; font-size: 12px; }
.r-status.reserved { background: rgba(59,130,246,.15); color: #3b82f6; }
.r-status.called { background: rgba(168,85,247,.15); color: #a855f7; }
.r-status.treated { background: rgba(22,163,74,.15); color: #16a34a; }
.r-status.cancel, .r-status.expired { background: rgba(148,163,184,.2); color: #64748b; }
.r-status.refund { background: rgba(239,68,68,.15); color: #ef4444; }
.result-ops { margin-top: 8px; display: flex; gap: 12px; align-items: center; }
.link { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 13px; padding: 0; }
.link.danger { color: #ef4444; }
.tip { font-size: 12px; color: var(--text-secondary); }
</style>
