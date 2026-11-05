<template>
  <router-view v-if="isScreen" />
  <div v-else class="layout">
    <header class="topbar">
      <div class="brand">医院数据治理平台</div>
      <nav class="nav">
        <RouterLink v-for="item in links" :key="item.path" :to="item.path" class="nav-link">
          {{ item.label }}
        </RouterLink>
      </nav>
      <RouterLink to="/screen" class="screen-link">进入运营大屏</RouterLink>
    </header>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { RouterLink, useRoute } from 'vue-router';

const route = useRoute();
const isScreen = computed(() => route.path === '/screen');
const links = [
  { path: '/datasources', label: '数据源' },
  { path: '/tasks', label: '同步任务' },
  { path: '/quality', label: '数据质量' },
  { path: '/alerts', label: '告警中心' },
];
</script>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
}

body {
  font-family: 'PingFang SC', 'Microsoft YaHei', 'Segoe UI', system-ui, sans-serif;
  background: #eef2f8;
  color: #1f2d3d;
}

.layout {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 56px;
  flex: none;
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 0 24px;
  background: #0f2742;
  color: #dcecff;
}

.brand {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 1px;
  white-space: nowrap;
}

.nav {
  display: flex;
  gap: 6px;
  flex: 1;
}

.nav-link {
  padding: 6px 14px;
  border-radius: 6px;
  color: #9fc4e8;
  font-size: 14px;
  text-decoration: none;
}

.nav-link:hover {
  background: rgba(255, 255, 255, 0.08);
}

.nav-link.router-link-active {
  background: #1e6fd9;
  color: #fff;
}

.screen-link {
  color: #7fd4ff;
  font-size: 14px;
  text-decoration: none;
}

.screen-link:hover {
  color: #b6ecff;
}

.content {
  flex: 1;
  padding: 20px 24px 32px;
}

.page {
  background: #fff;
  border-radius: 10px;
  padding: 18px 20px 22px;
  box-shadow: 0 1px 3px rgba(15, 39, 66, 0.08);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.page-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.page-header .actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-hint {
  margin: 0 0 12px;
  color: #7a8ba0;
  font-size: 13px;
}

.section-gap {
  margin-top: 18px;
}

.section-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: #33465c;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
}
</style>
