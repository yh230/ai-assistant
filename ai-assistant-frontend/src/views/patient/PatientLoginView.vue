<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="brand-logo">医</div>
        <h1 class="brand-title">AI 医院助手</h1>
        <p class="brand-sub">患者自助入口</p>
      </div>

      <div class="tabs">
        <button class="tab" :class="{ active: tab === 'login' }" @click="switchTab('login')">登录</button>
        <button class="tab" :class="{ active: tab === 'register' }" @click="switchTab('register')">注册</button>
      </div>

      <!-- 登录 -->
      <form v-if="tab === 'login'" class="login-form" @submit.prevent="handleLogin">
        <div class="field">
          <label>手机号</label>
          <input
            v-model="loginForm.phone"
            class="field-input"
            type="text"
            placeholder="请输入手机号"
            autocomplete="username"
          />
        </div>

        <div class="field">
          <label>密码</label>
          <input
            v-model="loginForm.password"
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

      <!-- 注册 -->
      <form v-else class="login-form" @submit.prevent="handleRegister">
        <div class="field">
          <label>姓名</label>
          <input v-model="registerForm.name" class="field-input" type="text" placeholder="请输入姓名" />
        </div>

        <div class="field">
          <label>手机号</label>
          <input
            v-model="registerForm.phone"
            class="field-input"
            type="text"
            placeholder="请输入手机号"
            autocomplete="username"
          />
        </div>

        <div class="field">
          <label>密码</label>
          <input
            v-model="registerForm.password"
            class="field-input"
            type="password"
            placeholder="6-32 位密码"
            autocomplete="new-password"
          />
        </div>

        <div class="field">
          <label>所属医院</label>
          <select v-model="registerForm.hospitalId" class="field-input" :disabled="hospitalsLoading">
            <option :value="null" disabled>{{ hospitalsLoading ? '医院加载中…' : '请选择医院' }}</option>
            <option v-for="h in hospitals" :key="h.id" :value="h.id">{{ h.name }}</option>
          </select>
        </div>

        <div class="form-error" v-if="errorMsg">{{ errorMsg }}</div>

        <button class="login-btn" type="submit" :disabled="loading">
          {{ loading ? '注册中…' : '注 册' }}
        </button>
      </form>

      <p class="login-tip">患者账号用于 AI 导诊与线上预约，员工请从管理端登录</p>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { patientLogin, patientRegister, publicHospitals } from '../../api/patient'
import { setPatientAuth } from '../../store/patientAuth'
import { msg } from '../../utils/message'

const router = useRouter()
const tab = ref('login')
const loading = ref(false)
const errorMsg = ref('')
const hospitals = ref([])
const hospitalsLoading = ref(false)

const loginForm = reactive({ phone: '', password: '' })
const registerForm = reactive({ name: '', phone: '', password: '', hospitalId: null })

function switchTab(t) {
  tab.value = t
  errorMsg.value = ''
}

// 注册选院：加载公开医院列表（免登录）
async function loadHospitals() {
  hospitalsLoading.value = true
  try {
    hospitals.value = (await publicHospitals()) || []
  } catch (e) {
    hospitals.value = []
    msg('医院列表加载失败：' + e.message)
  } finally {
    hospitalsLoading.value = false
  }
}

async function handleLogin() {
  errorMsg.value = ''
  const phone = loginForm.phone.trim()
  if (!/^1\d{10}$/.test(phone)) return (errorMsg.value = '请输入正确的手机号')
  if (!loginForm.password) return (errorMsg.value = '请输入密码')

  loading.value = true
  try {
    const res = await patientLogin({ username: phone, password: loginForm.password })
    setPatientAuth(res.token, { phone })
    router.replace('/patient/chat')
  } catch (e) {
    errorMsg.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  errorMsg.value = ''
  const name = registerForm.name.trim()
  const phone = registerForm.phone.trim()
  if (!name) return (errorMsg.value = '请输入姓名')
  if (!/^1\d{10}$/.test(phone)) return (errorMsg.value = '请输入正确的手机号')
  if (!registerForm.password || registerForm.password.length < 6 || registerForm.password.length > 32) {
    return (errorMsg.value = '密码长度需在 6-32 位之间')
  }
  if (!registerForm.hospitalId) return (errorMsg.value = '请选择所属医院')

  loading.value = true
  try {
    await patientRegister({
      name,
      phone,
      password: registerForm.password,
      hospitalId: registerForm.hospitalId
    })
    msg('注册成功，请登录')
    loginForm.phone = phone
    loginForm.password = ''
    switchTab('login')
  } catch (e) {
    errorMsg.value = e.message || '注册失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadHospitals)
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
  width: 400px;
  max-width: 92vw;
  background: var(--bg-card);
  border-radius: var(--border-radius);
  padding: 36px 32px;
  box-shadow: var(--shadow-xl);
}

.login-brand {
  text-align: center;
  margin-bottom: 24px;
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

.tabs {
  display: flex;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--border-color);
}

.tab {
  flex: 1;
  padding: 10px 0;
  background: none;
  border: none;
  color: var(--text-secondary);
  font-size: 14px;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: var(--transition);
}

.tab.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
  font-weight: 600;
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

select.field-input {
  cursor: pointer;
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
