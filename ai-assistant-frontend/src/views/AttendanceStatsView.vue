<template>
  <div class="stats-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">出勤统计</h2>
        <p class="page-sub">按维度聚合出勤数据。院长看全院、科室主任看本科室（后端自动过滤）。</p>
      </div>
      <div class="dim-switch">
        <button v-for="d in dims" :key="d.value" class="ghost-btn" :class="{ active: dimension === d.value }" @click="switchDim(d.value)">
          {{ d.label }}
        </button>
      </div>
    </div>

    <div class="summary" v-if="rows.length">
      <div class="summary-card">
        <span class="summary-num">{{ totalCount }}</span>
        <span class="summary-label">出勤记录总数</span>
      </div>
      <div class="summary-card">
        <span class="summary-num">{{ rows.length }}</span>
        <span class="summary-label">维度分组数</span>
      </div>
    </div>

    <div class="bar-card" v-if="rows.length">
      <div class="bar-row" v-for="r in rows" :key="r.dimId">
        <div class="bar-label">{{ r.dimName }}</div>
        <div class="bar-track">
          <div class="bar-fill" :style="{ width: pct(r.cnt) + '%' }"></div>
        </div>
        <div class="bar-value">{{ r.cnt }}</div>
      </div>
    </div>
    <div v-else class="empty-tip">暂无统计数据</div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { attendanceStats } from '../api/hospital'
import { toast } from '../utils/message'

const dims = [
  { value: 'department', label: '按科室' },
  { value: 'hospital', label: '按医院' },
  { value: 'status', label: '按状态' }
]
const dimension = ref('department')
const rows = ref([])

const maxCnt = computed(() => rows.value.reduce((m, r) => Math.max(m, r.cnt), 0))
const totalCount = computed(() => rows.value.reduce((s, r) => s + r.cnt, 0))
function pct(cnt) {
  if (maxCnt.value === 0) return 0
  return Math.round((cnt / maxCnt.value) * 100)
}

async function load() {
  try {
    const res = await attendanceStats(dimension.value)
    rows.value = (res.rows || []).map(r => ({ dimId: r.dimId, dimName: r.dimName, cnt: r.cnt }))
  } catch (e) {
    toast.error('统计失败：' + e.message)
    rows.value = []
  }
}
function switchDim(v) { dimension.value = v; load() }

onMounted(load)
</script>

<style scoped>
.stats-page { padding: 24px 32px; height: 100%; overflow-y: auto; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 20px; }
.page-title { font-size: 22px; color: var(--text-primary); margin: 0; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: var(--text-tertiary); }
.dim-switch { display: flex; gap: 8px; }
.ghost-btn { padding: 8px 16px; background: var(--bg-hover); border: 1px solid var(--border-color); color: var(--text-secondary); border-radius: var(--border-radius-sm); cursor: pointer; font-size: 13px; }
.ghost-btn.active { background: var(--primary); color: #fff; border-color: var(--primary); }
.summary { display: flex; gap: 16px; margin-bottom: 20px; }
.summary-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); padding: 18px 24px; display: flex; flex-direction: column; gap: 4px; min-width: 160px; }
.summary-num { font-size: 26px; font-weight: 700; color: var(--text-primary); }
.summary-label { font-size: 12px; color: var(--text-tertiary); }
.bar-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); padding: 20px 24px; }
.bar-row { display: flex; align-items: center; gap: 12px; padding: 10px 0; }
.bar-label { width: 160px; font-size: 14px; color: var(--text-primary); text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-track { flex: 1; height: 22px; background: var(--bg-input); border-radius: 6px; overflow: hidden; }
.bar-fill { height: 100%; background: linear-gradient(90deg, var(--primary), var(--primary-light)); border-radius: 6px; transition: width 0.4s ease; }
.bar-value { width: 56px; font-size: 14px; font-weight: 600; color: var(--text-secondary); }
.empty-tip { padding: 48px; text-align: center; color: var(--text-tertiary); font-size: 14px; }
</style>
