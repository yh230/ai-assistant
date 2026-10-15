<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="brand-logo">医</div>
        <h1 class="brand-title">AI 医院助手</h1>
        <p class="brand-sub">患者自助入口</p>
      </div>

      <el-tabs v-model="tab" @tab-change="switchTab">
        <!-- 登录 -->
        <el-tab-pane label="登录" name="login">
          <form class="login-form" @submit.prevent="handleLogin">
            <div class="field">
              <label>手机号</label>
              <el-input
                v-model="loginForm.phone"
                type="text"
                placeholder="请输入手机号"
                autocomplete="username"
              />
            </div>

            <div class="field">
              <label>密码</label>
              <el-input
                v-model="loginForm.password"
                type="password"
                placeholder="请输入密码"
                autocomplete="current-password"
              />
            </div>

            <div class="form-error" v-if="errorMsg">{{ errorMsg }}</div>

            <el-button class="login-btn" type="primary" native-type="submit" :loading="loading">
              {{ loading ? '登录中…' : '登 录' }}
            </el-button>
          </form>
        </el-tab-pane>

        <!-- 注册 -->
        <el-tab-pane label="注册" name="register">
          <form class="login-form" @submit.prevent="handleRegister">
            <div class="field">
              <label>姓名</label>
              <el-input v-model="registerForm.name" type="text" placeholder="请输入姓名" />
            </div>

            <div class="field">
              <label>手机号</label>
              <el-input
                v-model="registerForm.phone"
                type="text"
                placeholder="请输入手机号"
                autocomplete="username"
              />
            </div>

            <div class="field">
              <label>密码</label>
              <el-input
                v-model="registerForm.password"
                type="password"
                placeholder="6-32 位密码"
                autocomplete="new-password"
              />
            </div>

            <div class="field">
              <label>所属医院</label>
              <el-select
                v-model="registerForm.hospitalId"
                class="hospital-select"
                :disabled="hospitalsLoading"
                :placeholder="hospitalsLoading ? '医院加载中…' : '请选择医院'"
              >
                <el-option
                  v-for="h in hospitals"
                  :key="h.id"
                  :label="h.name"
                  :value="h.id"
                />
              </el-select>
            </div>

            <div class="form-error" v-if="errorMsg">{{ errorMsg }}</div>

            <el-button class="login-btn" type="primary" native-type="submit" :loading="loading">
              {{ loading ? '注册中…' : '注 册' }}
            </el-button>
          </form>
        </el-tab-pane>
      </el-tabs>

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
    setPatientAuth(res.token, { phone }, res.refreshToken)
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
  background: linear-gradient(135deg, #0f766e 0%, #14b8a6 100%);
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

.login-form :deep(.el-input__wrapper) {
  background: var(--bg-input);
}

.hospital-select {
  width: 100%;
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
