<template>
  <div class="profile-page">
    <div class="page-header">
      <h2 class="page-title">个人信息</h2>
      <p class="page-sub">维护您的手机号、邮箱，并可修改登录密码。</p>
    </div>

    <!-- 基本信息 -->
    <el-card shadow="never" class="profile-card">
      <template #header>
        <h3 class="card-title">基本信息</h3>
      </template>
      <el-form label-position="top">
        <el-form-item label="账号">
          <el-input :model-value="profile.username" disabled />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input :model-value="profile.name" disabled />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="选填" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="选填" />
        </el-form-item>
      </el-form>
      <div class="card-footer">
        <el-button type="primary" :loading="saving" @click="saveProfile">
          {{ saving ? '保存中…' : '保存' }}
        </el-button>
      </div>
    </el-card>

    <!-- 修改密码 -->
    <el-card shadow="never" class="profile-card">
      <template #header>
        <h3 class="card-title">修改密码</h3>
      </template>
      <el-form label-position="top">
        <el-form-item label="原密码" required>
          <el-input v-model="pwd.oldPassword" type="password" placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码" required>
          <el-input v-model="pwd.newPassword" type="password" placeholder="请输入新密码" />
        </el-form-item>
        <el-form-item label="确认新密码" required>
          <el-input v-model="pwd.confirm" type="password" placeholder="再次输入新密码" />
        </el-form-item>
      </el-form>
      <div class="card-footer">
        <el-button type="primary" :loading="pwding" @click="doChangePwd">
          {{ pwding ? '提交中…' : '修改密码' }}
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getProfile, updateProfile, changePassword } from '../api/auth'
import { useAuth } from '../store/auth'
import { toast } from '../utils/message'

const profile = ref({})
const form = ref({ phone: '', email: '' })
const saving = ref(false)

const pwd = ref({ oldPassword: '', newPassword: '', confirm: '' })
const pwding = ref(false)

const router = useRouter()
const { clear } = useAuth()

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
    // 后端已踢下线，清本地登录态并跳登录（旧令牌不可再用）
    clear()
    router.replace('/login')
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

.profile-card { margin-bottom: 20px; }
.card-title { margin: 0; font-size: 16px; color: var(--text-primary); }
.card-footer { display: flex; justify-content: flex-end; }
</style>
