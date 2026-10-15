<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="brand-logo">伴</div>
        <h1 class="brand-title">聊天小伙伴</h1>
        <p class="brand-sub">后台管理系统</p>
      </div>

      <el-form
        class="login-form"
        label-position="top"
        @submit.prevent="handleLogin"
      >
        <el-form-item label="账号">
          <el-input
            v-model="form.username"
            type="text"
            placeholder="请输入账号"
            autocomplete="username"
          />
        </el-form-item>

        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
          />
        </el-form-item>

        <div class="form-error" v-if="errorMsg">{{ errorMsg }}</div>

        <el-button
          class="login-btn"
          type="primary"
          native-type="submit"
          :loading="loading"
        >
          {{ loading ? '登录中…' : '登 录' }}
        </el-button>
      </el-form>

      <p class="login-tip">默认管理员：admin / admin123</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../store/auth'
import { login } from '../api/auth'

const route = useRoute()
const router = useRouter()
const { setToken, setUser, loadUserInfo, setRefreshToken } = useAuth()

const form = ref({ username: '', password: '' })
const loading = ref(false)
const errorMsg = ref('')

async function handleLogin() {
  errorMsg.value = ''
  if (!form.value.username.trim()) return (errorMsg.value = '请输入账号')
  if (!form.value.password) return (errorMsg.value = '请输入密码')

  loading.value = true
  try {
    // 切换账号前清空上一个用户的状态残影，避免新旧账号信息闪现
    setUser(null)
    setToken('')
    setRefreshToken('')
    const res = await login({ username: form.value.username.trim(), password: form.value.password })
    setToken(res.token)
    setRefreshToken(res.refreshToken)
    await loadUserInfo()
    const redirect = route.query.redirect || '/chat'
    router.replace(redirect)
  } catch (e) {
    errorMsg.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0f766e 0%, #14b8a6 100%);
}

.login-card {
  width: 380px;
  max-width: 92vw;
  background: var(--bg-card);
  border-radius: var(--border-radius);
  padding: 36px 32px;
  box-shadow: var(--shadow-xl);
}

.login-brand {
  text-align: center;
  margin-bottom: 28px;
}

.brand-logo {
  width: 56px;
  height: 56px;
  margin: 0 auto 12px;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--primary), var(--primary-light));
  color: white;
  font-size: 24px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-title {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary);
}

.brand-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-tertiary);
}

.login-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.login-form :deep(.el-form-item__label) {
  font-size: 13px;
  color: var(--text-secondary);
  padding-bottom: 4px;
}

.login-form :deep(.el-input__wrapper) {
  background: var(--bg-input);
}

.form-error {
  color: var(--danger);
  font-size: 13px;
  margin-bottom: 12px;
}

.login-btn {
  width: 100%;
  font-size: 15px;
}

.login-tip {
  margin: 18px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
}
</style>
