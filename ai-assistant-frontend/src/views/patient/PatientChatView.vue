<template>
  <div class="patient-chat">
    <!-- 顶栏 -->
    <header class="topbar">
      <div class="topbar-brand">
        <span class="brand-badge">医</span>
        <div class="brand-text">
          <div class="brand-name">AI 医院助手</div>
          <div class="brand-sub">患者端 · 导诊 / 知识库 / 闲聊</div>
        </div>
      </div>
      <div class="topbar-user">
        <span class="user-phone">{{ displayPhone }}</span>
        <el-badge :value="unreadCount" :hidden="unreadCount === 0" class="notify-badge">
          <el-button circle text @click="openNotifications" title="通知">
            <el-icon><Bell /></el-icon>
          </el-button>
        </el-badge>
        <el-button size="small" round plain class="logout-btn" @click="handleLogout">退出</el-button>
      </div>
    </header>

    <div class="chat-body">
      <!-- 会话侧边栏 -->
      <div class="session-panel">
        <div class="session-header">
          <el-button type="primary" class="new-chat-btn" :icon="Plus" @click="toggleTypeMenu">新对话</el-button>
          <div v-if="showTypeMenu" class="type-menu">
            <div v-for="t in SESSION_TYPES" :key="t.value" class="type-item" @click="createSession(t.value)">
              <span class="type-name">{{ t.label }}</span>
              <span class="type-desc">{{ t.desc }}</span>
            </div>
          </div>
        </div>
        <div class="session-list">
          <div
            v-for="s in sessions"
            :key="s.sessionId"
            class="session-item"
            :class="{ active: currentSessionId === s.sessionId }"
            @click="loadSession(s)"
          >
            <el-icon class="session-icon" :size="16"><ChatDotRound /></el-icon>
            <span class="session-title">{{ s.title || '新对话' }}</span>
            <el-tag size="small" class="session-type-tag" :type="tagType(s.sessionType)" effect="light">{{ typeLabel(s.sessionType) }}</el-tag>
            <span class="session-delete" @click.stop="removeSession(s.sessionId)">
              <el-icon :size="14"><Close /></el-icon>
            </span>
          </div>
          <div v-if="sessions.length === 0" class="empty-sessions">
            暂无对话记录
          </div>
        </div>
      </div>

      <!-- 聊天主区域 -->
      <div class="chat-area">
        <div ref="messageListRef" class="message-list">
          <!-- 空会话：按类型展示引导 -->
          <div v-if="messages.length === 0" class="guide">
            <div class="guide-icon">医</div>
            <h2 class="guide-title">{{ guide.title }}</h2>
            <p class="guide-text">{{ guide.text }}</p>
            <div class="guide-suggestions">
              <div class="suggestion" @click="sendQuickMessage(guide.suggestion)">
                {{ guide.suggestion }}
              </div>
            </div>
          </div>

          <template v-for="(msg, index) in messages" :key="index">
            <!-- 系统消息（错误提示等） -->
            <div v-if="msg.role === 'system'" class="message-item system">
              <div class="system-msg">{{ msg.content }}</div>
            </div>
            <div v-else class="message-item" :class="msg.role">
              <div class="message-avatar">{{ msg.role === 'user' ? '我' : '医' }}</div>
              <div class="message-bubble">
                <div class="markdown-body" v-html="renderMarkdownSafe(msg.content)"></div>
                <div v-if="msg.references && msg.references.length" class="reference-block">
                  <div class="reference-title">参考来源</div>
                  <div v-for="(ref, ri) in msg.references" :key="ri" class="reference-item">
                    <span class="reference-name">{{ ref.name }}</span>
                    <span class="reference-snippet">{{ ref.snippet }}</span>
                  </div>
                </div>
                <span v-if="msg.streaming" class="streaming-cursor"></span>
              </div>
            </div>
          </template>

          <!-- 打字动画：等待第一个 token 时显示 -->
          <div v-if="showTypingDots" class="message-item assistant">
            <div class="message-avatar">医</div>
            <div class="message-bubble typing">
              <span class="dot"></span>
              <span class="dot"></span>
              <span class="dot"></span>
            </div>
          </div>
        </div>

        <!-- 输入区域 -->
        <div class="input-area">
          <div class="input-wrapper">
            <el-input
              ref="inputRef"
              v-model="inputText"
              type="textarea"
              class="input-textarea"
              :autosize="{ minRows: 1, maxRows: 6 }"
              placeholder="输入消息，按 Enter 发送，Shift+Enter 换行..."
              @keydown="handleKeydown"
            />
            <el-button v-if="loading" type="danger" class="stop-btn" title="停止生成" :icon="VideoPause" @click="stopGeneration">停止</el-button>
            <el-button
              v-else
              type="primary"
              class="send-btn"
              :disabled="!inputText.trim()"
              :icon="Position"
              @click="sendMessage"
            />
          </div>
          <p class="input-hint">AI 内容仅供参考，不能替代执业医师诊断 · 挂号等写操作会先与您确认</p>
        </div>
      </div>
    </div>

    <!-- 二次确认弹窗（AI 写操作：预约/取消预约等） -->
    <el-dialog
      v-model="pendingConfirm"
      title="操作确认"
      width="420px"
      class="confirm-dialog"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
    >
      <p class="confirm-tool">即将执行：{{ confirmToolLabel }}</p>
      <p class="confirm-summary">{{ pendingConfirm && pendingConfirm.summary }}</p>
      <p class="confirm-tip">确认后 AI 将为您执行该操作，请核对信息无误</p>
      <template #footer>
        <el-button :disabled="confirmLoading" @click="cancelConfirm">取消</el-button>
        <el-button type="primary" :disabled="confirmLoading" @click="submitConfirm">确认</el-button>
      </template>
    </el-dialog>

    <!-- 通知抽屉（站内信） -->
    <el-drawer v-model="notifyVisible" title="我的通知" size="360px">
      <div class="notify-actions">
        <el-button size="small" text :disabled="unreadCount === 0" @click="markAllRead">全部已读</el-button>
      </div>
      <el-empty v-if="!notifications.length" description="暂无通知" />
      <div v-else class="notify-list">
        <div v-for="n in notifications" :key="n.id" class="notify-item" :class="{ unread: n.readFlag === 0 }" @click="markRead(n)">
          <div class="notify-title">{{ n.title }}</div>
          <div class="notify-content">{{ n.content }}</div>
          <div class="notify-time">{{ n.createTime }}</div>
        </div>
      </div>
      <el-pagination v-if="notifyTotal > notifyPageSize" layout="prev, pager, next" :total="notifyTotal"
                     :page-size="notifyPageSize" :current-page="notifyPage" @current-change="onNotifyPage" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Close, ChatDotRound, Position, VideoPause, Bell } from '@element-plus/icons-vue'
import { patientAuth, clearPatientAuth } from '../../store/patientAuth'
import {
  patientSessions,
  patientHistory,
  createPatientSession,
  deletePatientSession,
  sendPatientStream,
  confirmPatientAction,
  cancelPatientAction,
  revokePatientRefresh,
  patientNotifications,
  patientUnreadCount,
  readPatientNotification,
  readAllPatientNotifications
} from '../../api/patient'
import { confirm as showConfirm, msg } from '../../utils/message'
import { renderMarkdownSafe } from '../../utils/safeMarkdown'

// 会话类型（类型选择仅在新建会话时出现）
const SESSION_TYPES = [
  { value: 'TRIAGE', label: 'AI 导诊', desc: '描述症状，推荐科室与挂号' },
  { value: 'RAG', label: '知识库问答', desc: '门诊时间 / 医保政策 / 就诊流程' },
  { value: 'CHAT', label: '普通闲聊', desc: '日常聊天与问候' }
]

const TYPE_LABELS = { CHAT: '闲聊', TRIAGE: '导诊', RAG: '知识库' }

const TAG_TYPES = { TRIAGE: 'primary', RAG: 'success', CHAT: 'info' }

const CONFIRM_TOOL_LABELS = { bookAppointment: '预约挂号', cancelAppointment: '取消预约' }

const router = useRouter()

const messages = ref([])
const sessions = ref([])
const currentSessionId = ref(null)
const currentSessionType = ref('CHAT')
const inputText = ref('')
const loading = ref(false)
const showTypeMenu = ref(false)
const pendingConfirm = ref(null)
const confirmLoading = ref(false)
const inputRef = ref(null)
const messageListRef = ref(null)
let currentController = null

// 通知（站内信）状态
const unreadCount = ref(0)
const notifyVisible = ref(false)
const notifications = ref([])
const notifyTotal = ref(0)
const notifyPage = ref(1)
const notifyPageSize = ref(10)

const displayPhone = computed(() => (patientAuth.user && patientAuth.user.phone) || '患者')

// 空会话引导文案：导诊/知识库按类型展示，闲聊为默认问候
const guide = computed(() => {
  if (currentSessionType.value === 'TRIAGE') {
    return {
      title: 'AI 导诊',
      text: '请描述您的症状，例如：头痛三天，想挂神经内科',
      suggestion: '头痛三天，想挂神经内科'
    }
  }
  if (currentSessionType.value === 'RAG') {
    return {
      title: '知识库问答',
      text: '可询问门诊时间、医保政策、就诊流程',
      suggestion: '门诊时间是几点？'
    }
  }
  return {
    title: '你好！我是 AI 医院助手',
    text: '选择左侧「新对话」开始咨询，或直接输入消息（默认普通闲聊）',
    suggestion: '你好'
  }
})

const confirmToolLabel = computed(() => {
  if (!pendingConfirm.value) return ''
  return CONFIRM_TOOL_LABELS[pendingConfirm.value.toolName] || pendingConfirm.value.toolName || '操作'
})

// 是否显示打字动画（loading 且最后一条消息不是正在流式输出的 assistant 消息）
const showTypingDots = computed(() => {
  if (!loading.value) return false
  const last = messages.value[messages.value.length - 1]
  if (!last || last.role === 'user') return true
  if (last.streaming && !last.content) return true
  return false
})

function typeLabel(type) {
  return TYPE_LABELS[type] || '闲聊'
}

function tagType(type) {
  return TAG_TYPES[type] || 'info'
}

// 加载会话列表
async function loadSessions() {
  try {
    sessions.value = (await patientSessions()) || []
  } catch (e) {
    console.error('加载会话列表失败:', e)
  }
}

function toggleTypeMenu() {
  showTypeMenu.value = !showTypeMenu.value
}

// 点击侧边栏以外区域收起类型菜单
function onDocumentClick(e) {
  if (!e.target.closest('.session-header')) showTypeMenu.value = false
}

// 新建会话：选定类型后调用后端建会话（类型不可中途切换）
async function createSession(type) {
  showTypeMenu.value = false
  if (loading.value) return
  try {
    const res = await createPatientSession(type)
    currentSessionId.value = res.sessionId
    currentSessionType.value = type
    messages.value = []
    inputText.value = ''
    await loadSessions()
    nextTick(() => inputRef.value?.focus())
  } catch (e) {
    msg('新建会话失败：' + e.message)
  }
}

// 加载会话历史
async function loadSession(s) {
  if (loading.value) return
  if (currentSessionId.value === s.sessionId) return
  try {
    currentSessionId.value = s.sessionId
    currentSessionType.value = s.sessionType || 'CHAT'
    const data = await patientHistory(s.sessionId)
    messages.value = (data.messages || []).map(m => ({
      role: m.role,
      content: m.content,
      createdAt: m.createdAt,
      references: parseReferences(m.referenceJson)
    }))
    nextTick(scrollToBottom)
  } catch (e) {
    msg('加载会话历史失败：' + e.message)
  }
}

// 删除会话
async function removeSession(sessionId) {
  if (loading.value) return
  if (!(await showConfirm('确定要删除这个对话吗？'))) return
  try {
    await deletePatientSession(sessionId)
    if (currentSessionId.value === sessionId) {
      resetCurrentSession()
    }
    await loadSessions()
  } catch (e) {
    msg('删除会话失败：' + e.message)
  }
}

// 解析历史消息的引用来源 JSON（容错：空/非法返回空数组）
function parseReferences(raw) {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function resetCurrentSession() {
  currentSessionId.value = null
  currentSessionType.value = 'CHAT'
  messages.value = []
}

// 发送消息（流式）
function sendMessage() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''

  // 预先创建一条空的 assistant 消息，用于流式追加内容
  messages.value.push({ role: 'assistant', content: '', streaming: true, references: [] })
  const assistantIndex = messages.value.length - 1
  loading.value = true
  nextTick(scrollToBottom)

  currentController = sendPatientStream(
    { sessionId: currentSessionId.value, message: text, sessionType: currentSessionType.value },
    {
      onSession: (info) => {
        if (info && info.sessionId) {
          currentSessionId.value = info.sessionId
          loadSessions()
        }
      },
      onToken: (token) => {
        messages.value[assistantIndex].content += token
        nextTick(scrollToBottom)
      },
      onConfirm: (info) => {
        pendingConfirm.value = info
      },
      onReferences: (refs) => {
        if (Array.isArray(refs)) {
          messages.value[assistantIndex].references = refs
        }
      },
      onDone: () => {
        finishStreaming(assistantIndex)
        loadUnread()
      },
      onError: (m) => {
        failStreaming(assistantIndex, m)
      }
    }
  )
}

// 快捷消息
function sendQuickMessage(text) {
  if (loading.value) return
  inputText.value = text
  sendMessage()
}

function finishStreaming(index) {
  if (index >= 0 && messages.value[index]) {
    messages.value[index].streaming = false
  }
  loading.value = false
  currentController = null
}

// 出错：空占位气泡移除，错误文案以系统消息样式展示
function failStreaming(index, message) {
  const item = index >= 0 ? messages.value[index] : null
  if (item && item.role === 'assistant') {
    if (!item.content) {
      messages.value.splice(index, 1)
    } else {
      item.streaming = false
    }
  }
  loading.value = false
  currentController = null
  messages.value.push({ role: 'system', content: '抱歉，出了点问题：' + message })
  nextTick(scrollToBottom)
}

// 停止生成
function stopGeneration() {
  if (currentController) {
    currentController.abort()
    currentController = null
  }
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].streaming) {
      messages.value[i].streaming = false
      if (!messages.value[i].content) messages.value[i].content = '（已停止生成）'
      break
    }
  }
  loading.value = false
}

// 二次确认：执行写操作并流式续答（内容追加到当前 AI 气泡）
function submitConfirm() {
  const info = pendingConfirm.value
  if (!info || loading.value) return
  pendingConfirm.value = null

  let index = -1
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'assistant') { index = i; break }
  }
  if (index < 0) {
    messages.value.push({ role: 'assistant', content: '', streaming: true })
    index = messages.value.length - 1
  } else {
    messages.value[index].streaming = true
  }
  loading.value = true
  nextTick(scrollToBottom)

  let continued = false
  currentController = confirmPatientAction(info.actionId, {
    onToken: (token) => {
      const bubble = messages.value[index]
      if (!continued) {
        continued = true
        // 续答与上文分段展示
        if (bubble.content && !bubble.content.endsWith('\n')) bubble.content += '\n\n'
      }
      bubble.content += token
      nextTick(scrollToBottom)
    },
    onDone: () => {
      finishStreaming(index)
      loadUnread()
    },
    onError: (m) => {
      failStreaming(index, m)
    }
  })
}

// 取消待确认操作
async function cancelConfirm() {
  const info = pendingConfirm.value
  if (!info || confirmLoading.value) return
  confirmLoading.value = true
  try {
    await cancelPatientAction(info.actionId)
    pendingConfirm.value = null
    msg('已取消')
  } catch (e) {
    msg('取消失败：' + e.message)
  } finally {
    confirmLoading.value = false
  }
}

// 退出登录
async function handleLogout() {
  const refreshToken = localStorage.getItem('patient_refresh_token')
  try {
    await revokePatientRefresh(refreshToken)
  } catch (e) {
    // 撤销失败不阻断登出，本地清除即可
  }
  clearPatientAuth()
  router.replace('/patient/login')
}

// 未读数刷新
async function loadUnread() {
  try {
    unreadCount.value = Number(await patientUnreadCount()) || 0
  } catch (e) {
    console.error('加载未读数失败:', e)
  }
}

// 通知分页加载
async function loadNotifications() {
  try {
    const res = await patientNotifications({ pageNum: notifyPage.value, pageSize: notifyPageSize.value })
    notifications.value = (res && res.list) || []
    notifyTotal.value = (res && res.total) || 0
  } catch (e) {
    msg('加载通知失败：' + e.message)
  }
}

// 打开通知抽屉：刷新未读数与列表
async function openNotifications() {
  notifyVisible.value = true
  notifyPage.value = 1
  await Promise.all([loadUnread(), loadNotifications()])
}

// 标记单条已读（已读项点击不重复请求）
async function markRead(n) {
  if (!n || n.readFlag !== 0) return
  try {
    await readPatientNotification(n.id)
    n.readFlag = 1
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  } catch (e) {
    msg('标记已读失败：' + e.message)
  }
}

// 全部标记已读
async function markAllRead() {
  if (unreadCount.value === 0) return
  try {
    await readAllPatientNotifications()
    notifications.value.forEach(n => { n.readFlag = 1 })
    unreadCount.value = 0
  } catch (e) {
    msg('操作失败：' + e.message)
  }
}

async function onNotifyPage(p) {
  notifyPage.value = p
  await loadNotifications()
}

// 键盘事件
function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

// 滚动到底部
function scrollToBottom() {
  const el = messageListRef.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

onMounted(() => {
  loadSessions()
  loadUnread()
  document.addEventListener('click', onDocumentClick)
  nextTick(() => inputRef.value?.focus())
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  if (currentController) currentController.abort()
})
</script>

<style scoped>
.patient-chat {
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* 顶栏 */
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 20px;
  background: var(--bg-sidebar);
  color: white;
  flex-shrink: 0;
}

.topbar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-badge {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--primary), var(--primary-light));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 700;
  color: white;
}

.brand-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.brand-sub {
  font-size: 12px;
  color: var(--text-tertiary);
}

.topbar-user {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-phone {
  font-size: 13px;
  color: var(--text-secondary);
}

.chat-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

/* 会话面板 */
.session-panel {
  width: 260px;
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  background: var(--bg-card);
  flex-shrink: 0;
}

.session-header {
  padding: 12px;
  border-bottom: 1px solid var(--border-color);
  position: relative;
}

.new-chat-btn {
  width: 100%;
}

/* 类型选择菜单（仅新建会话时出现） */
.type-menu {
  position: absolute;
  top: 58px;
  left: 12px;
  right: 12px;
  z-index: 30;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--border-radius-sm);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}

.type-item {
  padding: 10px 14px;
  cursor: pointer;
  transition: var(--transition);
}

.type-item:hover {
  background: var(--primary-bg);
}

.type-name {
  display: block;
  font-size: 13px;
  color: var(--text-primary);
}

.type-desc {
  display: block;
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 2px;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--border-radius-sm);
  cursor: pointer;
  transition: var(--transition);
  position: relative;
}

.session-item:hover {
  background: var(--bg-hover);
}

.session-item.active {
  background: var(--primary-bg);
  color: var(--primary-dark);
}

.session-icon {
  flex-shrink: 0;
  opacity: 0.5;
}

.session-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.session-item.active .session-type-tag {
  --el-tag-bg-color: var(--primary-bg);
  --el-tag-border-color: transparent;
  --el-tag-text-color: var(--primary-dark);
}

.session-delete {
  opacity: 0;
  display: flex;
  align-items: center;
  transition: var(--transition);
  color: var(--text-tertiary);
}

.session-item:hover .session-delete {
  opacity: 1;
}

.session-delete:hover {
  color: var(--danger);
}

.empty-sessions {
  text-align: center;
  color: var(--text-tertiary);
  padding: 40px 0;
  font-size: 13px;
}

/* 聊天区域 */
.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--bg-main);
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

/* 空会话引导 */
.guide {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
  gap: 12px;
}

.guide-icon {
  width: 72px;
  height: 72px;
  border-radius: 20px;
  background: linear-gradient(135deg, var(--primary), var(--primary-light));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  font-weight: 700;
  color: white;
  margin-bottom: 8px;
}

.guide-title {
  font-size: 22px;
  color: var(--text-primary);
}

.guide-text {
  color: var(--text-secondary);
  font-size: 14px;
}

.guide-suggestions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}

.suggestion {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 20px;
  cursor: pointer;
  transition: var(--transition);
  font-size: 13px;
  color: var(--text-secondary);
}

.suggestion:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-bg);
}

/* 消息项 */
.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  max-width: 85%;
  align-items: flex-start;
}

.message-item.user {
  flex-direction: row-reverse;
  margin-left: auto;
}

.message-item.system {
  max-width: 100%;
  justify-content: center;
}

.system-msg {
  padding: 6px 14px;
  background: var(--bg-hover);
  color: var(--text-secondary);
  border-radius: 14px;
  font-size: 12px;
  text-align: center;
  max-width: 70%;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

.message-item.user .message-avatar {
  background: var(--primary);
  color: white;
}

.message-item.assistant .message-avatar {
  background: linear-gradient(135deg, var(--primary), var(--primary-light));
  color: white;
}

.message-bubble {
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.6;
  position: relative;
  min-width: 0;
}

.message-item.user .message-bubble {
  background: var(--primary);
  color: white;
}

.message-item.assistant .message-bubble {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-top-left-radius: 4px;
  box-shadow: var(--shadow-sm);
}

/* 流式输出光标 */
.streaming-cursor {
  display: inline-block;
  width: 8px;
  height: 18px;
  background: var(--primary);
  margin-left: 2px;
  vertical-align: text-bottom;
  border-radius: 1px;
  animation: cursor-blink 1s step-end infinite;
}

@keyframes cursor-blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

/* 打字动画 */
.typing {
  display: flex;
  gap: 4px;
  align-items: center;
  padding: 16px 20px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-tertiary);
  animation: typing 1.4s infinite;
}

.dot:nth-child(2) {
  animation-delay: 0.2s;
}

.dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes typing {
  0%, 60%, 100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-6px);
    opacity: 1;
  }
}

/* 输入区域 */
.input-area {
  padding: 12px 24px 16px;
  border-top: 1px solid var(--border-color);
  background: var(--bg-card);
}

.input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  border-radius: 16px;
}

.input-wrapper :deep(.el-textarea__inner) {
  border-radius: 16px;
  box-shadow: 0 0 0 1px var(--border-color) inset;
  transition: var(--transition);
}

.input-wrapper :deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1px var(--primary) inset;
}

.stop-btn {
  flex-shrink: 0;
}

.send-btn {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
}

.input-hint {
  text-align: center;
  color: var(--text-tertiary);
  font-size: 12px;
  margin-top: 8px;
}

/* 二次确认弹窗 */
.confirm-dialog .confirm-tool {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0 0 8px;
}

.confirm-dialog .confirm-summary {
  font-size: 14px;
  color: var(--text-primary);
  line-height: 1.7;
  background: var(--bg-input);
  border: 1px solid var(--border-color);
  border-radius: var(--border-radius-sm);
  padding: 12px 14px;
  margin: 0;
}

.confirm-dialog .confirm-tip {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--text-tertiary);
}

.reference-block {
  margin-top: 10px;
  padding: 8px 10px;
  border-left: 3px solid var(--primary);
  background: rgba(13, 148, 136, 0.06);
  border-radius: 6px;
  font-size: 12px;
}

.reference-title {
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.reference-item {
  display: flex;
  gap: 6px;
  margin-top: 2px;
  color: var(--text-tertiary);
}

.reference-name {
  color: var(--primary);
  flex-shrink: 0;
}

.reference-snippet {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 通知铃铛 */
.notify-badge {
  display: inline-flex;
  align-items: center;
}

/* 通知抽屉 */
.notify-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.notify-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.notify-item {
  padding: 10px 12px;
  border: 1px solid var(--border-color);
  border-radius: var(--border-radius-sm);
  cursor: pointer;
  transition: var(--transition);
}

.notify-item:hover {
  border-color: var(--primary);
  background: var(--primary-bg);
}

.notify-item.unread {
  border-left: 3px solid var(--primary);
}

.notify-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.notify-content {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 4px;
  line-height: 1.5;
}

.notify-time {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 6px;
}
</style>
