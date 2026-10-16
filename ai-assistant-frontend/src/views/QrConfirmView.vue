<template>
  <div class="qr-confirm-page">
    <div class="qr-confirm-card">
      <div class="card-brand">
        <div class="brand-logo">扫</div>
        <h1 class="brand-title">扫码登录确认</h1>
      </div>

      <template v-if="!scene">
        <p class="confirm-text">缺少扫码场景参数，请重新扫码。</p>
      </template>

      <template v-else-if="authedIdentities.length === 0">
        <p class="confirm-text">请先登录，再确认本次扫码登录。</p>
        <div class="entry-list">
          <router-link
            class="entry-btn staff"
            :to="{ path: '/login', query: { redirect: redirectTarget } }"
          >
            员工登录
          </router-link>
          <router-link
            class="entry-btn patient"
            :to="{ path: '/patient/login', query: { redirect: redirectTarget } }"
          >
            患者登录
          </router-link>
        </div>
      </template>

      <template v-else>
        <p class="confirm-text">确认后，电脑端将以当前登录身份完成登录。</p>
        <div class="entry-list">
          <el-button
            v-for="id in authedIdentities"
            :key="id"
            class="confirm-btn"
            type="primary"
            :loading="confirming === id"
            @click="confirmLogin(id)"
          >
            {{ authedIdentities.length > 1 ? `确认登录（${ID_LABEL[id]}）` : '确认登录' }}
          </el-button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { qrConfirm as staffQrConfirm } from '../api/auth'
import { qrConfirm as patientQrConfirm } from '../api/patient'
import { msg } from '../utils/message'

const route = useRoute()
const scene = computed(() => route.query.scene || '')
const redirectTarget = computed(() => `/qr-confirm?scene=${scene.value}`)

const ID_LABEL = { staff: '员工', patient: '患者' }
const confirming = ref('')

// 已登录身份列表（员工/患者 token 各自独立判定）；依赖路由以便登录回跳后重新计算
const authedIdentities = computed(() => {
  void route.fullPath
  const list = []
  if (localStorage.getItem('ai_token')) list.push('staff')
  if (localStorage.getItem('patient_token')) list.push('patient')
  return list
})

async function confirmLogin(identity) {
  if (!scene.value) return msg('缺少扫码场景参数')
  confirming.value = identity
  try {
    if (identity === 'staff') {
      await staffQrConfirm({ sceneId: scene.value })
    } else {
      await patientQrConfirm({ sceneId: scene.value })
    }
    msg('已确认登录，请在电脑端继续')
  } catch (e) {
    msg(e.message || '确认失败，请重试')
  } finally {
    confirming.value = ''
  }
}
</script>

<style scoped>
.qr-confirm-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0f766e 0%, #14b8a6 100%);
}

.qr-confirm-card {
  width: 360px;
  max-width: 92vw;
  background: var(--bg-card);
  border-radius: var(--border-radius);
  padding: 36px 32px;
  box-shadow: var(--shadow-xl);
  text-align: center;
}

.card-brand {
  margin-bottom: 20px;
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

.confirm-text {
  margin: 0 0 20px;
  font-size: 14px;
  color: var(--text-secondary);
}

.entry-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.entry-btn {
  display: block;
  padding: 10px 0;
  border-radius: var(--border-radius);
  font-size: 15px;
  text-decoration: none;
  color: white;
}

.entry-btn.staff {
  background: var(--primary);
}

.entry-btn.patient {
  background: var(--primary-light);
}

.confirm-btn {
  width: 100%;
  font-size: 15px;
}
</style>
