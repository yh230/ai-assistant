<template>
  <router-view />
  <ToastHost />
</template>

<script setup>
import ToastHost from './components/ToastHost.vue'
// 根组件：仅作为路由出口，布局由 MainLayout 提供
</script>

<style>
html, body, #app {
  height: 100%;
}
</style>
