<template>
  <div class="screen-shell">
    <div class="screen-stage" :style="stageStyle">
      <header class="screen-header">
        <div class="header-side">
          <span class="dot" :class="streamState"></span>
          <span>{{ streamLabel }}</span>
        </div>
        <h1>医院数据治理运营大屏</h1>
        <div class="header-side right">
          <span>{{ today }}</span>
          <span class="clock">{{ clock }}</span>
        </div>
      </header>

      <el-alert
        v-if="error"
        class="screen-error"
        :title="error"
        type="error"
        :closable="false"
        show-icon
      />

      <section class="kpi-row">
        <article v-for="item in overview.kpis" :key="item.key" class="kpi-card">
          <span class="kpi-label">{{ item.label }}</span>
          <strong class="kpi-value">{{ displayKpi(item) }}</strong>
        </article>
      </section>

      <section class="screen-grid">
        <div class="panel">
          <h3 class="panel-title">医院挂号排行</h3>
          <div ref="hospitalRef" class="chart"></div>
        </div>

        <div class="panel center">
          <h3 class="panel-title">近 {{ trendDays }} 天挂号趋势</h3>
          <div ref="trendRef" class="chart"></div>
        </div>

        <div class="panel right">
          <h3 class="panel-title">质量异常告警</h3>
          <ul class="alert-list">
            <li
              v-for="(alert, index) in quality.alerts"
              :key="`${alert.rule_id ?? 'alert'}-${alert.checked_at ?? index}`"
            >
              <span class="sev" :class="severityClass(alert.severity)">{{ alert.severity || 'P2' }}</span>
              <div class="alert-body">
                <p class="alert-title">{{ alert.title || '质量规则失败' }}</p>
                <p class="alert-meta">{{ formatDateTime(alert.checked_at) }}</p>
              </div>
            </li>
            <li v-if="!quality.alerts.length" class="alert-empty">暂无质量异常</li>
          </ul>
          <div class="health">
            <div>
              <span>质量分</span>
              <strong>{{ percent(quality.health.quality_score) }}</strong>
            </div>
            <div>
              <span>链路延迟</span>
              <strong>{{ formatMetric(quality.health.lag_seconds) }}s</strong>
            </div>
            <div>
              <span>异常规则</span>
              <strong>{{ formatMetric(quality.health.open_alerts) }}</strong>
            </div>
          </div>
        </div>

        <div class="panel lower">
          <h3 class="panel-title">科室 TOP10</h3>
          <div ref="departmentRef" class="chart"></div>
        </div>
      </section>

      <RouterLink class="back-link" to="/datasources">治理后台</RouterLink>
    </div>
  </div>
</template>

<script setup>
import * as echarts from 'echarts';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';

import {
  fetchOverview,
  fetchQuality,
  fetchRanking,
  fetchTrend,
  isMockMode,
  screenStreamUrl,
} from '../api/client.js';
import {
  buildRankingOption,
  buildTrendOption,
  formatDateTime,
  formatMetric,
} from '../utils/chartOptions.js';

const THEME = 'dp-screen';

echarts.registerTheme(THEME, {
  backgroundColor: 'transparent',
  color: ['#2fd2ff', '#3ee08f', '#ffcf5c', '#ff7a7a', '#8a7dff'],
  textStyle: { color: '#a9d8f0' },
  categoryAxis: {
    axisLine: { lineStyle: { color: 'rgba(70, 150, 190, 0.5)' } },
    axisTick: { show: false },
    axisLabel: { color: '#8fb8d2', fontSize: 13 },
    splitLine: { show: false },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#8fb8d2', fontSize: 13 },
    splitLine: { lineStyle: { color: 'rgba(50, 110, 150, 0.28)' } },
  },
});

const overview = ref({ kpis: [] });
const quality = ref({ health: {}, alerts: [] });
const trendDays = ref(30);
const error = ref('');
const scale = ref(1);
const streamState = ref('connecting');
const clock = ref('');
const today = ref('');

const trendRef = ref(null);
const hospitalRef = ref(null);
const departmentRef = ref(null);

let trendChart;
let hospitalChart;
let departmentChart;
let stream;
let clockTimer;
let refreshTimer;
let refreshing = false;
let disposed = false;

const stageStyle = computed(() => ({ transform: `scale(${scale.value})` }));
const streamLabel = computed(
  () =>
    ({
      mock: 'MOCK 本地预览',
      connecting: '实时流连接中',
      live: '实时流已连接',
      degraded: '实时流重连中',
    })[streamState.value] || '实时流未连接',
);

function updateScale() {
  const width = window.innerWidth || 1920;
  const height = window.innerHeight || 1080;
  scale.value = Math.min(width / 1920, height / 1080);
}

function tickClock() {
  const now = new Date();
  clock.value = now.toLocaleTimeString('zh-CN', { hour12: false });
  today.value = now.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    weekday: 'long',
  });
}

function percent(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '0%';
  }
  return `${(number * 100).toFixed(1)}%`;
}

function displayKpi(item) {
  if (item.key === 'quality_score') {
    // The ClickHouse service reports the weighted score as a ratio while the
    // local fixtures use a percentage; render both as a percentage.
    const number = Number(item.value);
    const scaled = Number.isFinite(number) && number <= 1 ? number * 100 : number;
    return `${formatMetric(scaled, 1)}%`;
  }
  if (item.key === 'cancel_rate') {
    return `${formatMetric(Number(item.value) * 100, 2)}%`;
  }
  return formatMetric(item.value);
}

function severityClass(severity) {
  return `sev-${String(severity || 'p2').toLowerCase()}`;
}

function namedRows(rows) {
  return (Array.isArray(rows) ? rows : []).map((row, index) => ({
    ...row,
    name: row.name || `未知 #${row.department_id ?? row.hospital_id ?? index + 1}`,
  }));
}

function rankingOption(rows) {
  const option = buildRankingOption(namedRows(rows));
  // ECharts draws the first category at the bottom; invert so #1 is on top.
  option.yAxis.inverse = true;
  option.yAxis.axisLabel = {
    color: '#8fb8d2',
    fontSize: 13,
    width: 104,
    overflow: 'truncate',
  };
  option.grid.left = 120;
  return option;
}

function normalizeQuality(payload) {
  const health = payload?.health || {};
  return {
    health: {
      quality_score: health.quality_score ?? 0,
      lag_seconds: health.lag_seconds ?? 0,
      open_alerts: health.open_alerts ?? 0,
    },
    alerts: Array.isArray(payload?.alerts) ? payload.alerts : [],
  };
}

async function refresh() {
  if (refreshing) {
    return;
  }
  refreshing = true;
  try {
    const [overviewData, trendRows, rankingData, qualityData] = await Promise.all([
      fetchOverview(),
      fetchTrend(),
      fetchRanking(),
      fetchQuality(),
    ]);
    overview.value =
      overviewData && Array.isArray(overviewData.kpis) ? overviewData : { kpis: [] };
    const rows = Array.isArray(trendRows) ? trendRows : [];
    trendChart?.setOption(buildTrendOption(rows), true);
    trendDays.value = rows.length || 30;
    hospitalChart?.setOption(rankingOption(rankingData?.hospitals), true);
    departmentChart?.setOption(rankingOption(rankingData?.departments), true);
    quality.value = normalizeQuality(qualityData);
    error.value = '';
  } catch (err) {
    error.value = `大屏数据加载失败：${err.message || err}`;
  } finally {
    refreshing = false;
  }
}

function connectStream() {
  stream = new EventSource(screenStreamUrl());
  stream.onopen = () => {
    streamState.value = 'live';
  };
  stream.onmessage = (event) => {
    try {
      const payload = JSON.parse(event.data);
      if (payload?.error) {
        streamState.value = 'degraded';
        error.value = `实时流异常：${payload.error}`;
        return;
      }
      if (Array.isArray(payload?.kpis)) {
        overview.value = payload;
        streamState.value = 'live';
        error.value = '';
      }
    } catch {
      streamState.value = 'degraded';
      error.value = '实时流数据解析失败';
    }
  };
  stream.onerror = () => {
    // EventSource reconnects on its own; only the indicator changes.
    streamState.value = 'degraded';
  };
}

onMounted(async () => {
  updateScale();
  window.addEventListener('resize', updateScale);
  trendChart = echarts.init(trendRef.value, THEME);
  hospitalChart = echarts.init(hospitalRef.value, THEME);
  departmentChart = echarts.init(departmentRef.value, THEME);
  tickClock();
  clockTimer = window.setInterval(tickClock, 1000);
  await refresh();
  if (disposed) {
    // The view was unmounted while the first payload was loading.
    return;
  }
  refreshTimer = window.setInterval(refresh, 60000);
  if (isMockMode) {
    streamState.value = 'mock';
  } else {
    connectStream();
  }
});

onBeforeUnmount(() => {
  disposed = true;
  stream?.close();
  stream = undefined;
  window.clearInterval(clockTimer);
  window.clearInterval(refreshTimer);
  window.removeEventListener('resize', updateScale);
  trendChart?.dispose();
  hospitalChart?.dispose();
  departmentChart?.dispose();
  trendChart = undefined;
  hospitalChart = undefined;
  departmentChart = undefined;
});
</script>

<style scoped>
.screen-shell {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background:
    radial-gradient(circle at 50% 0%, #0b2b45 0%, #04121f 55%, #020a12 100%);
}

.screen-stage {
  position: relative;
  width: 1920px;
  height: 1080px;
  flex: none;
  box-sizing: border-box;
  padding: 20px 24px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  transform-origin: center center;
  color: #cfeaff;
}

.screen-header {
  height: 70px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(46, 130, 180, 0.45);
  background: linear-gradient(90deg, rgba(9, 46, 72, 0) 0%, rgba(13, 74, 112, 0.55) 50%, rgba(9, 46, 72, 0) 100%);
}

.screen-header h1 {
  margin: 0;
  font-size: 34px;
  font-weight: 700;
  letter-spacing: 8px;
  color: #e6f8ff;
  text-shadow: 0 0 22px rgba(60, 200, 255, 0.55);
}

.header-side {
  width: 360px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 15px;
  color: #8fb8d2;
}

.header-side.right {
  justify-content: flex-end;
}

.clock {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 18px;
  color: #43e6ff;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #2fd2ff;
  box-shadow: 0 0 8px #2fd2ff;
}

.dot.degraded {
  background: #ffb020;
  box-shadow: 0 0 8px #ffb020;
}

.dot.mock {
  background: #8a7dff;
  box-shadow: 0 0 8px #8a7dff;
}

.dot.connecting {
  background: #6f93a8;
  box-shadow: 0 0 8px #6f93a8;
}

.screen-error {
  position: absolute;
  top: 100px;
  right: 28px;
  width: 560px;
  z-index: 10;
}

.kpi-row {
  flex: none;
  height: 108px;
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 16px;
}

.kpi-card {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
  padding: 0 22px;
  background: linear-gradient(180deg, rgba(12, 52, 80, 0.95), rgba(6, 26, 42, 0.9));
  border: 1px solid rgba(47, 140, 190, 0.5);
  box-shadow: inset 0 0 20px rgba(24, 110, 160, 0.25);
}

.kpi-label {
  font-size: 16px;
  letter-spacing: 1px;
  color: #8fb8d2;
}

.kpi-value {
  font-family: Consolas, 'DIN Alternate', 'Segoe UI', monospace;
  font-size: 38px;
  font-weight: 700;
  color: #43e6ff;
  text-shadow: 0 0 18px rgba(50, 210, 255, 0.45);
}

.screen-grid {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 560px 1fr 480px;
  grid-template-rows: 1fr 1fr;
  gap: 16px;
}

.panel {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 12px 18px 14px;
  background: linear-gradient(180deg, rgba(9, 39, 60, 0.92), rgba(4, 20, 32, 0.92));
  border: 1px solid rgba(37, 110, 150, 0.55);
  box-shadow: inset 0 0 24px rgba(20, 90, 130, 0.25);
}

.panel::before,
.panel::after {
  content: '';
  position: absolute;
  width: 26px;
  height: 26px;
  pointer-events: none;
}

.panel::before {
  top: -1px;
  left: -1px;
  border-top: 2px solid #2fd2ff;
  border-left: 2px solid #2fd2ff;
}

.panel::after {
  right: -1px;
  bottom: -1px;
  border-right: 2px solid #2fd2ff;
  border-bottom: 2px solid #2fd2ff;
}

.panel.center {
  grid-column: 2;
  grid-row: 1 / span 2;
}

.panel.right {
  grid-column: 3;
  grid-row: 1 / span 2;
}

.panel.lower {
  grid-column: 1;
  grid-row: 2;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 8px;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 2px;
  color: #8fe3ff;
}

.panel-title::before {
  content: '';
  width: 4px;
  height: 18px;
  background: linear-gradient(180deg, #38e8ff, #1a7fd4);
}

.chart {
  flex: 1;
  min-height: 0;
}

.alert-list {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 0 4px 0 0;
  list-style: none;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.alert-list li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 12px;
  background: rgba(12, 48, 72, 0.55);
  border-left: 3px solid #2f6f96;
}

.alert-list .alert-empty {
  border-left-color: transparent;
  color: #6f93a8;
}

.sev {
  flex: none;
  padding: 2px 8px;
  border: 1px solid #35b7e0;
  border-radius: 3px;
  background: #134a63;
  color: #c9f2ff;
  font-size: 13px;
  font-weight: 700;
}

.sev.sev-p0 {
  border-color: #ff6b7a;
  background: #7a1f28;
  color: #ffd9dc;
}

.sev.sev-p1 {
  border-color: #ffb04d;
  background: #7a4d12;
  color: #ffe6c2;
}

.alert-body {
  min-width: 0;
}

.alert-title {
  margin: 0;
  font-size: 16px;
  color: #dff4ff;
}

.alert-meta {
  margin: 4px 0 0;
  font-size: 13px;
  color: #7fa8c0;
}

.health {
  flex: none;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(46, 130, 180, 0.35);
}

.health div {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.health span {
  font-size: 13px;
  color: #86aec6;
}

.health strong {
  font-size: 26px;
  color: #43e6ff;
}

.alert-list::-webkit-scrollbar {
  width: 6px;
}

.alert-list::-webkit-scrollbar-thumb {
  background: rgba(47, 140, 190, 0.5);
  border-radius: 3px;
}

.back-link {
  position: absolute;
  right: 26px;
  bottom: 4px;
  font-size: 12px;
  letter-spacing: 1px;
  color: rgba(130, 190, 220, 0.45);
  text-decoration: none;
}

.back-link:hover {
  color: #7fd4ff;
}
</style>
