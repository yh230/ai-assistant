<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="brand-logo">医</div>
        <h1 class="brand-title">AI 医院助手</h1>
        <p class="brand-sub">员工工作台</p>
      </div>

      <el-tabs v-model="activeTab" class="login-tabs">
        <!-- 账号密码 -->
        <el-tab-pane label="账号密码" name="account">
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
                data-testid="login-username"
              />
            </el-form-item>

            <el-form-item label="密码">
              <el-input
                v-model="form.password"
                type="password"
                placeholder="请输入密码"
                autocomplete="current-password"
                data-testid="login-password"
              />
            </el-form-item>

            <div class="form-error" v-if="errorMsg" data-testid="login-error">{{ errorMsg }}</div>

            <el-button
              class="login-btn"
              type="primary"
              native-type="submit"
              :loading="loading"
              data-testid="login-submit"
            >
              {{ loading ? '登录中…' : '登 录' }}
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- 手机号验证码 -->
        <el-tab-pane label="手机号登录" name="sms">
          <el-form
            class="login-form"
            label-position="top"
            @submit.prevent="handleSmsLogin"
          >
            <el-form-item label="手机号">
              <el-input
                v-model="smsForm.phone"
                type="text"
                placeholder="请输入手机号"
                autocomplete="username"
              />
            </el-form-item>

            <el-form-item label="验证码">
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
      </el-tabs>

      <p class="login-tip">默认管理员：admin / admin123</p>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, watch, nextTick, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QRCode from 'qrcode'
import { useAuth } from '../store/auth'
import { login, sendSmsCode, loginBySms, qrCreate, qrPoll } from '../api/auth'
import { msg } from '../utils/message'

const route = useRoute()
const router = useRouter()
const { setToken, setUser, loadUserInfo, setRefreshToken } = useAuth()

const activeTab = ref('account')
const form = ref({ username: '', password: '' })
const smsForm = reactive({ phone: '', code: '' })
const loading = ref(false)
const sending = ref(false)
const errorMsg = ref('')
const countdown = ref(0)

// 扫码登录状态：loading / waiting / expired / error
const qrCanvas = ref(null)
const qrSceneId = ref('')
const qrStatus = ref('loading')
let qrTimer = null
let countdownTimer = null
// 扫码生成代次：每次 startQr/stopQrTimer 递增，用于丢弃过期异步结果
let qrSeq = 0
// 二维码指向的公开访问地址（手机需可访问，默认当前站点）
const publicBase = import.meta.env.VITE_PUBLIC_BASE_URL || window.location.origin

function redirectAfterLogin() {
  router.replace(route.query.redirect || '/chat')
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
    redirectAfterLogin()
  } catch (e) {
    errorMsg.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}

async function handleSendCode() {
  errorMsg.value = ''
  const phone = smsForm.phone.trim()
  if (!/^1\d{10}$/.test(phone)) return (errorMsg.value = '请输入正确的手机号')

  sending.value = true
  try {
    await sendSmsCode({ identity: 'staff', phone })
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
    setUser(null)
    setToken('')
    setRefreshToken('')
    const res = await loginBySms({ identity: 'staff', phone, code: smsForm.code.trim() })
    setToken(res.token)
    setRefreshToken(res.refreshToken)
    await loadUserInfo()
    redirectAfterLogin()
  } catch (e) {
    errorMsg.value = e.message || '登录失败'
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
    setToken(res.token)
    setRefreshToken(res.refreshToken)
    await loadUserInfo()
    redirectAfterLogin()
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
    if (seq !== qrSeq || activeTab.value !== 'qr') return
    qrSceneId.value = sceneId
    const url = `${publicBase}/qr-confirm?scene=${sceneId}`
    await QRCode.toCanvas(qrCanvas.value, url, { width: 200 })
    if (seq !== qrSeq || activeTab.value !== 'qr') return
    qrStatus.value = 'waiting'
    qrTimer = setInterval(pollQr, 2000)
  } catch (e) {
    if (seq !== qrSeq) return
    qrStatus.value = 'error'
    errorMsg.value = e.message || '二维码生成失败'
  }
}

// 切到扫码页签时创建二维码，离开时清理轮询
watch(activeTab, (tab) => {
  if (tab === 'qr') {
    nextTick(() => startQr())
  } else {
    stopQrTimer()
  }
})

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

.login-tabs :deep(.el-tabs__item) {
  font-size: 14px;
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

.login-tip {
  margin: 18px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
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
</style>
