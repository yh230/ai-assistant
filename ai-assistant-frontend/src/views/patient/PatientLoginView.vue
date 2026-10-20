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

        <!-- 手机号验证码 -->
        <el-tab-pane label="手机号验证码" name="sms">
          <form class="login-form" @submit.prevent="handleSmsLogin">
            <div class="field">
              <label>手机号</label>
              <el-input
                v-model="smsForm.phone"
                type="text"
                placeholder="请输入手机号"
                autocomplete="username"
              />
            </div>

            <div class="field">
              <label>验证码</label>
              <div class="code-row">
                <el-input
                  v-model="smsForm.code"
                  type="text"
                  placeholder="请输入验证码"
                  autocomplete="one-time-code"
                />
                <el-button
                  class="code-btn"
                  :disabled="countdown > 0"
                  :loading="sending"
                  @click="handleSendCode"
                >
                  {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
                </el-button>
              </div>
            </div>

            <div class="form-error" v-if="errorMsg">{{ errorMsg }}</div>

            <el-button class="login-btn" type="primary" native-type="submit" :loading="loading">
              {{ loading ? '登录中…' : '登 录' }}
            </el-button>
          </form>
        </el-tab-pane>

        <!-- 扫码登录 -->
        <el-tab-pane label="扫码登录" name="qr">
          <div class="qr-box">
            <canvas ref="qrCanvas" class="qr-canvas"></canvas>
            <p class="qr-tip" v-if="qrStatus === 'loading'">二维码生成中…</p>
            <p class="qr-tip" v-else-if="qrStatus === 'waiting'">请使用手机端扫码并确认登录</p>
            <p class="qr-tip" v-else-if="qrStatus === 'expired'">二维码已过期</p>
            <p class="qr-tip error" v-else-if="qrStatus === 'error'">{{ errorMsg || '二维码生成失败' }}</p>
            <el-button
              v-if="qrStatus === 'expired' || qrStatus === 'error'"
              class="login-btn"
              type="primary"
              @click="startQr"
            >
              刷新二维码
            </el-button>
          </div>
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
import { ref, reactive, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QRCode from 'qrcode'
import { patientLogin, patientRegister, publicHospitals, sendSmsCode, loginBySms, qrCreate, qrPoll } from '../../api/patient'
import { setPatientAuth, patientAuth } from '../../store/patientAuth'
import { msg } from '../../utils/message'

const route = useRoute()
const router = useRouter()
const tab = ref('login')
const loading = ref(false)
const errorMsg = ref('')
const hospitals = ref([])
const hospitalsLoading = ref(false)
const sending = ref(false)
const countdown = ref(0)
let countdownTimer = null

// 扫码登录状态：loading / waiting / expired / error
const qrCanvas = ref(null)
const qrSceneId = ref('')
const qrStatus = ref('loading')
let qrTimer = null
// 扫码生成代次：每次 startQr/stopQrTimer 递增，用于丢弃过期异步结果
let qrSeq = 0
// 二维码指向的公开访问地址（手机需可访问，默认当前站点）
const publicBase = import.meta.env.VITE_PUBLIC_BASE_URL || window.location.origin

const loginForm = reactive({ phone: '', password: '' })
const smsForm = reactive({ phone: '', code: '' })
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
    router.replace(route.query.redirect || '/patient/chat')
  } catch (e) {
    errorMsg.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}

function startCountdown() {
  countdown.value = 60
  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      clearInterval(countdownTimer)
      countdownTimer = null
      countdown.value = 0
    }
  }, 1000)
}

async function handleSendCode() {
  errorMsg.value = ''
  const phone = smsForm.phone.trim()
  if (!/^1\d{10}$/.test(phone)) return (errorMsg.value = '请输入正确的手机号')

  sending.value = true
  try {
    await sendSmsCode({ identity: 'patient', phone })
    msg('验证码已发送')
    startCountdown()
  } catch (e) {
    errorMsg.value = e.message || '验证码发送失败'
  } finally {
    sending.value = false
  }
}

async function handleSmsLogin() {
  errorMsg.value = ''
  const phone = smsForm.phone.trim()
  if (!/^1\d{10}$/.test(phone)) return (errorMsg.value = '请输入正确的手机号')
  if (!smsForm.code) return (errorMsg.value = '请输入验证码')

  loading.value = true
  try {
    const res = await loginBySms({ identity: 'patient', phone, code: smsForm.code.trim() })
    setPatientAuth(res.token, { phone }, res.refreshToken)
    router.replace(route.query.redirect || '/patient/chat')
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

function stopQrTimer() {
  qrSeq += 1
  if (qrTimer) {
    clearInterval(qrTimer)
    qrTimer = null
  }
}

async function pollQr() {
  let res
  try {
    res = await qrPoll(qrSceneId.value)
  } catch {
    // 单次轮询失败忽略，等待下一轮
    return
  }
  if (res.status === 'EXPIRED') {
    stopQrTimer()
    qrStatus.value = 'expired'
    return
  }
  if (res.status !== 'CONFIRMED') return

  stopQrTimer()
  try {
    setPatientAuth(res.token, patientAuth.user || {}, res.refreshToken)
    router.replace(route.query.redirect || '/patient/chat')
  } catch (e) {
    errorMsg.value = e.message || '登录失败'
    qrStatus.value = 'error'
  }
}

async function startQr() {
  stopQrTimer()
  const seq = ++qrSeq
  errorMsg.value = ''
  qrStatus.value = 'loading'
  try {
    const { sceneId } = await qrCreate()
    // 生成期间又切页签/重开则丢弃本次结果，避免遗留轮询
    if (seq !== qrSeq || tab.value !== 'qr') return
    qrSceneId.value = sceneId
    const url = `${publicBase}/qr-confirm?scene=${sceneId}`
    await QRCode.toCanvas(qrCanvas.value, url, { width: 200 })
    if (seq !== qrSeq || tab.value !== 'qr') return
    qrStatus.value = 'waiting'
    qrTimer = setInterval(pollQr, 2000)
  } catch (e) {
    if (seq !== qrSeq) return
    qrStatus.value = 'error'
    errorMsg.value = e.message || '二维码生成失败'
  }
}

// 切到扫码页签时创建二维码，离开时清理轮询
watch(tab, (t) => {
  if (t === 'qr') {
    nextTick(() => startQr())
  } else {
    stopQrTimer()
  }
})

onMounted(loadHospitals)

onBeforeUnmount(() => {
  stopQrTimer()
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
})
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

.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.code-btn {
  flex-shrink: 0;
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

.qr-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 8px 0 4px;
  min-height: 240px;
}

.qr-canvas {
  width: 200px;
  height: 200px;
}

.qr-tip {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.qr-tip.error {
  color: var(--danger);
}

@media (max-width: 480px) {
  .login-page {
    padding: 16px;
  }

  .login-card {
    width: 100%;
    max-width: 100%;
    padding: 28px 20px;
  }
}

.login-tip {
  margin: 18px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
}
</style>
