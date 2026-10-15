<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// 根组件：仅作为路由出口，布局由 MainLayout 提供；el-config-provider 提供中文语言包
</script>

<style>
html, body, #app {
  height: 100%;
}
</style>
