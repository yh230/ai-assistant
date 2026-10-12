<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">排班与号源</h2>
        <p class="page-desc">维护医生出诊排班，系统按排班预生成每日号源（门诊管理员，本院数据）</p>
      </div>
      <button class="btn primary" @click="openCreate">+ 新建排班</button>
    </div>

    <!-- 筛选 -->
    <div class="filter-bar">
      <select v-model="filters.doctorUserId" class="input" @change="load">
        <option :value="null">全部医生</option>
        <option v-for="d in doctors" :key="d.id" :value="d.id">{{ d.name }}</option>
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

    <!-- 列表 -->
    <div class="table-card">
      <table class="data-table">
        <thead>
          <tr>
            <th style="width:120px">医生</th>
            <th style="width:110px">科室</th>
            <th style="width:120px">日期</th>
            <th style="width:70px">时段</th>
            <th style="width:90px">号源总数</th>
            <th style="width:90px">已挂/余量</th>
            <th style="width:70px">状态</th>
            <th style="width:200px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in list" :key="s.id">
            <td :title="s.doctorName">{{ s.doctorName }}</td>
            <td :title="s.departmentName">{{ s.departmentName }}</td>
            <td>{{ s.scheduleDate }}</td>
            <td>{{ s.periodText }}</td>
            <td>{{ s.totalLimit }}</td>
            <td>
              <span class="cnt">{{ s.registeredCount }}/</span>
              <span class="remain">{{ s.remaining }}</span>
            </td>
            <td>
              <span class="status-tag" :class="s.status === 1 ? 'on' : 'off'">
                {{ s.statusText }}
              </span>
            </td>
            <td class="ops">
              <button class="link" @click="openEdit(s)">编辑</button>
              <button class="link" @click="genSource(s)">生成号源</button>
              <button class="link" @click="viewPool(s)">号源</button>
              <button class="link danger" @click="remove(s)">停用</button>
            </td>
          </tr>
          <tr v-if="!list.length">
            <td colspan="8" class="empty">暂无排班数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager" v-if="total > pageSize">
      <button class="btn" :disabled="pageNum <= 1" @click="pageNum--; load()">上一页</button>
      <span>{{ pageNum }} / {{ Math.ceil(total / pageSize) }}</span>
      <button class="btn" :disabled="pageNum >= Math.ceil(total / pageSize)" @click="pageNum++; load()">下一页</button>
    </div>

    <!-- 号源池弹窗 -->
    <div class="modal-mask" v-if="poolVisible" @click.self="poolVisible = false">
      <div class="modal">
        <div class="modal-header">
          <h3>号源池 - {{ poolSchedule?.doctorName }}（{{ poolSchedule?.scheduleDate }} {{ poolSchedule?.periodText }}）</h3>
          <button class="modal-close" @click="poolVisible = false">×</button>
        </div>
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
        <div class="modal-footer">
          <button class="btn" @click="poolVisible = false">关闭</button>
        </div>
      </div>
    </div>

    <!-- 排班表单弹窗 -->
    <div class="modal-mask" v-if="formVisible" @click.self="formVisible = false">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ form.id ? '编辑排班' : '新建排班' }}</h3>
          <button class="modal-close" @click="formVisible = false">×</button>
        </div>
        <div class="modal-body">
          <label class="field">
            <span>医生 *</span>
            <select v-model="form.doctorUserId" class="input">
              <option :value="null">请选择医生</option>
              <option v-for="d in doctors" :key="d.id" :value="d.id">{{ d.name }}（{{ d.departmentName }}）</option>
            </select>
          </label>
          <label class="field">
            <span>出诊日期 *</span>
            <input v-model="form.scheduleDate" type="date" class="input" />
          </label>
          <label class="field">
            <span>时段 *</span>
            <select v-model="form.period" class="input">
              <option :value="1">上午</option>
              <option :value="2">下午</option>
            </select>
          </label>
          <label class="field">
            <span>号源总数 *</span>
            <input v-model.number="form.totalLimit" type="number" min="1" class="input" />
          </label>
          <label class="field">
            <span>备注</span>
            <input v-model="form.remark" class="input" placeholder="可选" />
          </label>
          <p class="hint">提示：修改排班后系统会作废旧号源并按新配置重生成号源。</p>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="formVisible = false">取消</button>
          <button class="btn primary" :disabled="saving" @click="submit">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>
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
.input { background: var(--bg-card); border: 1px solid var(--border-color); color: var(--text-primary);
  border-radius: var(--border-radius-sm); padding: 8px 10px; font-size: 13px; }
.tilde { color: var(--text-secondary); }
.table-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); overflow-x: auto; }
.data-table { width: 100%; border-collapse: collapse; font-size: 14px; min-width: 900px; }
.data-table td { padding: 12px 16px; border-bottom: 1px solid var(--border-color); color: var(--text-primary); }
.data-table th, .data-table td { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cnt { color: var(--text-secondary); }
.remain { color: var(--success, #16a34a); font-weight: 600; }
.status-tag { padding: 3px 10px; border-radius: 12px; font-size: 12px; }
.status-tag.on { background: rgba(22,163,74,.15); color: #16a34a; }
.status-tag.off { background: rgba(239,68,68,.15); color: #ef4444; }
.ops { display: flex; gap: 8px; }
.link { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 13px; padding: 0; }
.link.danger { color: #ef4444; }
.empty { text-align: center; color: var(--text-secondary); padding: 30px; }
.pager { display: flex; gap: 12px; align-items: center; justify-content: flex-end; margin-top: 14px; color: var(--text-secondary); }
.btn { background: var(--bg-card); border: 1px solid var(--border-color); color: var(--text-primary);
  border-radius: var(--border-radius-sm); padding: 8px 14px; cursor: pointer; font-size: 13px; }
.btn.primary { background: var(--primary); color: #fff; border-color: var(--primary); }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 50; }
.modal { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); width: 460px; max-width: 92vw; }
.modal-header { display: flex; justify-content: space-between; align-items: center; padding: 16px; border-bottom: 1px solid var(--border-color); }
.modal-header h3 { margin: 0; font-size: 16px; color: var(--text-primary); }
.modal-close { background: none; border: none; color: var(--text-secondary); font-size: 22px; cursor: pointer; }
.modal-body { padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.field { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-secondary); }
.hint { font-size: 12px; color: var(--text-secondary); margin: 0; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px; border-top: 1px solid var(--border-color); }
.pool-summary { padding: 12px 16px; color: var(--text-secondary); font-size: 13px; }
.pool-grid { display: grid; grid-template-columns: repeat(10, 1fr); gap: 8px; padding: 0 16px 16px; max-height: 320px; overflow-y: auto; }
.pool-cell { display: flex; flex-direction: column; align-items: center; justify-content: center; aspect-ratio: 1;
  border-radius: var(--border-radius-sm); font-size: 14px; font-weight: 600; }
.pool-cell.free { background: rgba(22,163,74,.12); color: #16a34a; }
.pool-cell.used { background: rgba(239,68,68,.12); color: #ef4444; }
.pool-cell small { font-size: 10px; font-weight: 400; }

/* ===== 修复轮 1：作用域化恢复（本页根元素为 .page，不在管理页公共段作用域内） =====
   基线中与全局逐字相同、已随 Task 22 上收删除的 .data-table th 需在本地恢复（声明取基线原文）。 */
.data-table th { text-align: left; padding: 12px 16px; background: var(--bg-hover); color: var(--text-secondary);
  font-weight: 600; border-bottom: 1px solid var(--border-color); }
</style>
