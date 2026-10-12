<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">挂号统计</h2>
        <p class="page-desc">按医生 / 科室 / 状态 / 日期维度统计（本院数据，后端自动过滤）</p>
      </div>
      <div class="dim-tabs">
        <button v-for="d in dims" :key="d.v" class="dim-tab" :class="{ active: dimension === d.v }" @click="switchDim(d.v)">
          {{ d.t }}
        </button>
      </div>
    </div>

    <div class="stat-card" v-if="rows.length">
      <div class="bar-row" v-for="r in rows" :key="r.dimId">
        <span class="bar-label" :title="r.dimName">{{ r.dimName }}</span>
        <div class="bar-track">
          <div class="bar-fill" :style="{ width: pct(r.cnt) + '%' }"></div>
        </div>
        <span class="bar-cnt">{{ r.cnt }}</span>
      </div>
    </div>
    <div class="empty" v-else>暂无统计数据</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { registrationStats } from '../api/registration'
import { msg } from '../utils/message'

const dims = [
  { v: 'doctor', t: '按医生' },
  { v: 'department', t: '按科室' },
  { v: 'status', t: '按状态' },
  { v: 'date', t: '按日期' }
]
const dimension = ref('doctor')
const rows = ref([])

async function load() {
  try {
    const res = await registrationStats(dimension.value)
    rows.value = res.rows || []
  } catch (e) {
    msg('加载统计失败：' + e.message)
  }
}
function switchDim(v) { dimension.value = v; load() }
function pct(cnt) {
  const max = Math.max(1, ...rows.value.map(r => r.cnt))
  return Math.round((cnt / max) * 100)
}

onMounted(load)
</script>

<style scoped>
.page { padding: 24px; height: 100%; overflow-y: auto; }
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 18px; flex-wrap: wrap; gap: 12px; }
.page-title { font-size: 20px; margin: 0; color: var(--text-primary); }
.page-desc { margin: 6px 0 0; color: var(--text-secondary); font-size: 13px; }
.dim-tabs { display: flex; gap: 8px; }
.dim-tab { background: var(--bg-card); border: 1px solid var(--border-color); color: var(--text-secondary);
  border-radius: var(--border-radius-sm); padding: 7px 14px; cursor: pointer; font-size: 13px; }
.dim-tab.active { background: var(--primary); color: #fff; border-color: var(--primary); }
.stat-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); padding: 20px; display: flex; flex-direction: column; gap: 14px; max-width: 760px; }
.bar-row { display: flex; align-items: center; gap: 12px; }
.bar-label { width: 120px; text-align: right; color: var(--text-primary); font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bar-track { flex: 1; height: 22px; background: var(--bg-hover); border-radius: 6px; overflow: hidden; }
.bar-fill { height: 100%; background: linear-gradient(90deg, var(--primary), var(--primary-light)); border-radius: 6px; transition: width .3s; }
.bar-cnt { width: 50px; color: var(--text-secondary); font-size: 13px; }
.empty { color: var(--text-secondary); padding: 40px; text-align: center; }
</style>
