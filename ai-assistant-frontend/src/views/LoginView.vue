<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="brand-logo">伴</div>
        <h1 class="brand-title">聊天小伙伴</h1>
        <p class="brand-sub">后台管理系统</p>
      </div>

      <form class="login-form" @submit.prevent="handleLogin">
        <div class="field">
          <label>账号</label>
          <input
            v-model="form.username"
            class="field-input"
            type="text"
            placeholder="请输入账号"
            autocomplete="username"
          />
        </div>

        <div class="field">
          <label>密码</label>
          <input
            v-model="form.password"
            class="field-input"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
          />
        </div>

        <div class="form-error" v-if="errorMsg">{{ errorMsg }}</div>

        <button class="login-btn" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登 录' }}
        </button>
      </form>

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
const { setToken, setUser, loadUserInfo } = useAuth()

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
    const res = await login({ username: form.value.username.trim(), password: form.value.password })
    setToken(res.token)
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
  background: linear-gradient(135deg, #1e1b2e 0%, #312e5e 100%);
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

.field {
  margin-bottom: 16px;
}

.field label {
  display: block;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}

.field-input {
  width: 100%;
  padding: 11px 14px;
  border: 1px solid var(--border-color);
  border-radius: var(--border-radius-sm);
  background: var(--bg-input);
  color: var(--text-primary);
  font-size: 14px;
  outline: none;
}

.field-input:focus {
  border-color: var(--primary);
}

.form-error {
  color: var(--danger);
  font-size: 13px;
  margin-bottom: 12px;
}

.login-btn {
  width: 100%;
  padding: 11px;
  background: var(--primary);
  color: white;
  border: none;
  border-radius: var(--border-radius-sm);
  font-size: 15px;
  cursor: pointer;
  transition: var(--transition);
}

.login-btn:hover:not(:disabled) {
  background: var(--primary-dark);
}

.login-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.login-tip {
  margin: 18px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
}
</style>
