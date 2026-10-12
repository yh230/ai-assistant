<template>
  <div class="profile-page">
    <div class="page-header">
      <h2 class="page-title">个人信息</h2>
      <p class="page-sub">维护您的手机号、邮箱，并可修改登录密码。</p>
    </div>

    <!-- 基本信息 -->
    <section class="card">
      <h3 class="card-title">基本信息</h3>
      <div class="form-row">
        <label>账号</label>
        <input :value="profile.username" class="form-input" disabled />
      </div>
      <div class="form-row">
        <label>姓名</label>
        <input :value="profile.name" class="form-input" disabled />
      </div>
      <div class="form-row">
        <label>手机号</label>
        <input v-model="form.phone" class="form-input" placeholder="选填" />
      </div>
      <div class="form-row">
        <label>邮箱</label>
        <input v-model="form.email" class="form-input" placeholder="选填" />
      </div>
      <div class="card-footer">
        <button class="primary-btn" :disabled="saving" @click="saveProfile">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </section>

    <!-- 修改密码 -->
    <section class="card">
      <h3 class="card-title">修改密码</h3>
      <div class="form-row">
        <label>原密码 <span class="req">*</span></label>
        <input v-model="pwd.oldPassword" type="password" class="form-input" placeholder="请输入当前密码" />
      </div>
      <div class="form-row">
        <label>新密码 <span class="req">*</span></label>
        <input v-model="pwd.newPassword" type="password" class="form-input" placeholder="请输入新密码" />
      </div>
      <div class="form-row">
        <label>确认新密码 <span class="req">*</span></label>
        <input v-model="pwd.confirm" type="password" class="form-input" placeholder="再次输入新密码" />
      </div>
      <div class="card-footer">
        <button class="primary-btn" :disabled="pwding" @click="doChangePwd">
          {{ pwding ? '提交中…' : '修改密码' }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getProfile, updateProfile, changePassword } from '../api/auth'
import { toast } from '../utils/message'

const profile = ref({})
const form = ref({ phone: '', email: '' })
const saving = ref(false)

const pwd = ref({ oldPassword: '', newPassword: '', confirm: '' })
const pwding = ref(false)

async function load() {
  try {
    const data = await getProfile()
    profile.value = data
    form.value.phone = data.phone || ''
    form.value.email = data.email || ''
  } catch (e) {
    toast.error('加载失败：' + e.message)
  }
}

async function saveProfile() {
  saving.value = true
  try {
    await updateProfile({ phone: form.value.phone.trim(), email: form.value.email.trim() })
    profile.value.phone = form.value.phone.trim()
    profile.value.email = form.value.email.trim()
    toast.success('已保存')
  } catch (e) {
    toast.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

async function doChangePwd() {
  if (!pwd.value.oldPassword) return toast.warn('请输入原密码')
  if (!pwd.value.newPassword) return toast.warn('请输入新密码')
  if (pwd.value.newPassword !== pwd.value.confirm) return toast.warn('两次输入的新密码不一致')
  pwding.value = true
  try {
    await changePassword({ oldPassword: pwd.value.oldPassword, newPassword: pwd.value.newPassword })
    toast.success('密码已修改，请使用新密码重新登录')
    pwd.value = { oldPassword: '', newPassword: '', confirm: '' }
  } catch (e) {
    toast.error('修改失败：' + e.message)
  } finally {
    pwding.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.profile-page { padding: 24px 32px; height: 100%; overflow-y: auto; max-width: 720px; }
.page-header { margin-bottom: 20px; }
.page-title { font-size: 22px; color: var(--text-primary); margin: 0; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: var(--text-tertiary); }

.card {
  background: var(--bg-card); border: 1px solid var(--border-color);
  border-radius: var(--border-radius); padding: 20px 24px; margin-bottom: 20px;
}
.card-title { margin: 0 0 16px; font-size: 16px; color: var(--text-primary); }

.form-row { margin-bottom: 16px; }
.form-row label { display: block; font-size: 13px; color: var(--text-secondary); margin-bottom: 6px; }
.req { color: var(--danger); }
.form-input {
  width: 100%; padding: 9px 12px; border: 1px solid var(--border-color);
  border-radius: var(--border-radius-sm); background: var(--bg-input);
  color: var(--text-primary); font-size: 14px; outline: none; font-family: inherit;
}
.form-input:focus { border-color: var(--primary); }
.form-input:disabled { background: var(--bg-hover); color: var(--text-tertiary); cursor: not-allowed; }

.card-footer { display: flex; justify-content: flex-end; }

.primary-btn {
  display: inline-flex; align-items: center; gap: 6px; padding: 9px 20px;
  background: var(--primary); color: white; border: none; border-radius: var(--border-radius-sm);
  font-size: 14px; cursor: pointer; transition: var(--transition);
}
.primary-btn:hover:not(:disabled) { background: var(--primary-dark); }
.primary-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
