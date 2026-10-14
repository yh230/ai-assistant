<template>
  <div class="chat-view">
    <!-- 会话列表 -->
    <div class="session-panel">
      <div class="session-header">
        <button class="new-chat-btn" @click="newChat">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19"/>
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
          新建对话
        </button>
      </div>
      <div class="session-list">
        <div
          v-for="s in sessions"
          :key="s.sessionId"
          class="session-item"
          :class="{ active: currentSessionId === s.sessionId }"
          @click="loadSession(s.sessionId)"
        >
          <svg class="session-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          </svg>
          <span class="session-title">{{ s.title }}</span>
          <span class="session-delete" @click.stop="removeSession(s.sessionId)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 6L6 18M6 6l12 12"/>
            </svg>
          </span>
        </div>
        <div v-if="sessions.length === 0" class="empty-sessions">
          暂无对话记录
        </div>
      </div>
    </div>

    <!-- 聊天主区域 -->
    <div class="chat-area">
      <!-- 角色 / 模型选择栏 -->
      <div class="role-bar" v-if="roles.length > 0">
        <div class="role-pills">
          <button
            v-for="role in roles"
            :key="role.id"
            class="role-pill"
            :class="{ active: selectedRoleId === role.id }"
            :title="role.description"
            @click="switchRole(role.id)"
          >
            <span class="role-avatar">{{ role.avatar }}</span>
            <span class="role-name">{{ role.name }}</span>
          </button>
        </div>
        <div class="model-select">
          <select v-model="selectedModelId" title="选择大模型">
            <option v-for="m in models" :key="m.id" :value="m.id">
              {{ m.label }}{{ m.active ? '' : '（未启用）' }}
            </option>
          </select>
        </div>
      </div>

      <!-- 消息列表 -->
      <div ref="messageListRef" class="message-list">
        <div v-if="messages.length === 0" class="welcome">
          <div class="welcome-icon">伴</div>
          <h2>你好！我是你的{{ currentRole ? currentRole.name : '聊天小伙伴' }}</h2>
          <p>可以陪我聊天解闷，也可以直接问我天气，比如「北京今天天气怎么样」。</p>
          <div class="welcome-suggestions">
            <div class="suggestion" @click="sendQuickMessage('你好呀，介绍下你自己')">
              <span class="suggestion-emoji">👋</span>
              <span>打个招呼</span>
            </div>
            <div class="suggestion" @click="sendQuickMessage('北京今天天气怎么样？')">
              <span class="suggestion-emoji">🌤️</span>
              <span>查查天气</span>
            </div>
            <div class="suggestion" @click="sendQuickMessage('讲个笑话解解闷')">
              <span class="suggestion-emoji">😄</span>
              <span>讲个笑话</span>
            </div>
          </div>
        </div>

        <div
          v-for="(msg, index) in messages"
          :key="index"
          class="message-item"
          :class="msg.role"
        >
          <div class="message-avatar">
            {{ msg.role === 'user' ? '我' : '伴' }}
          </div>
          <div class="message-bubble">
            <div class="markdown-body" v-html="renderMarkdownSafe(msg.content)"></div>
            <span v-if="msg.streaming" class="streaming-cursor"></span>
            <span v-if="msg.role === 'assistant' && msg.localFallback" class="local-badge">本地回复</span>
          </div>
        </div>

        <!-- 打字动画：等待第一个 token 时显示 -->
        <div v-if="showTypingDots" class="message-item assistant">
          <div class="message-avatar">伴</div>
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
          <textarea
            ref="inputRef"
            v-model="inputText"
            class="input-textarea"
            placeholder="输入消息，按 Enter 发送，Shift+Enter 换行..."
            rows="1"
            @keydown="handleKeydown"
            @input="autoResize"
          ></textarea>
          <button class="send-btn" :disabled="!inputText.trim() || loading" @click="sendMessage">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="22" y1="2" x2="11" y2="13"/>
              <polygon points="22 2 15 22 11 13 2 9 22 2"/>
            </svg>
          </button>
        </div>
        <p class="input-hint">
          聊天小伙伴由 Spring AI + Vue 3 驱动 · 流式响应 · 对话内容保存在 MySQL
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { sendMessageStream, getChatHistory, getSessions, deleteSession, getRoles, getModels } from '../api/chat'
import { confirm as showConfirm, toast } from '../utils/message'
import { renderMarkdownSafe } from '../utils/safeMarkdown'

const messages = ref([])
const inputText = ref('')
const loading = ref(false)
const currentSessionId = ref(null)
const sessions = ref([])
const inputRef = ref(null)
const messageListRef = ref(null)

// 角色与模型（来自后端配置，用户可自由切换）
const roles = ref([])
const models = ref([])
const selectedRoleId = ref('')
const selectedModelId = ref('')

// 是否显示打字动画（loading 且最后一条消息不是正在流式输出的 assistant 消息）
const showTypingDots = computed(() => {
  if (!loading.value) return false
  const last = messages.value[messages.value.length - 1]
  if (!last || last.role === 'user') return true
  if (last.streaming && !last.content) return true
  return false
})

// 加载会话列表
async function loadSessions() {
  try {
    const data = await getSessions()
    sessions.value = data || []
  } catch (e) {
    console.error('加载会话列表失败:', e)
  }
}

// 新建对话
function newChat() {
  messages.value = []
  currentSessionId.value = null
  inputText.value = ''
  nextTick(() => inputRef.value?.focus())
}

// 切换角色：默认新开一个干净会话（避免人格串）
// 点击当前已选角色时不做任何操作，避免误清空一个空会话
function switchRole(roleId) {
  if (roleId === selectedRoleId.value) return
  selectedRoleId.value = roleId
  messages.value = []
  currentSessionId.value = null
  inputText.value = ''
  nextTick(() => inputRef.value?.focus())
}

// 加载会话历史
async function loadSession(sessionId) {
  try {
    currentSessionId.value = sessionId
    const data = await getChatHistory(sessionId)
    messages.value = (data.messages || []).map(m => ({
      role: m.role,
      content: m.content,
      createdAt: m.createdAt
    }))
    nextTick(scrollToBottom)
  } catch (e) {
    console.error('加载会话历史失败:', e)
  }
}

// 删除会话
async function removeSession(sessionId) {
  if (!(await showConfirm('确定要删除这个对话吗？'))) return
  try {
    await deleteSession(sessionId)
    if (currentSessionId.value === sessionId) {
      newChat()
    }
    await loadSessions()
  } catch (e) {
    toast.error('删除会话失败：' + e.message)
  }
}

// 加载可选角色列表
async function loadRoles() {
  try {
    const data = await getRoles()
    roles.value = data || []
    if (roles.value.length > 0 && !selectedRoleId.value) {
      selectedRoleId.value = roles.value[0].id
    }
  } catch (e) {
    console.error('加载角色列表失败:', e)
  }
}

// 加载可选模型列表
async function loadModels() {
  try {
    const data = await getModels()
    models.value = data || []
    const active = models.value.find(m => m.active)
    if (!selectedModelId.value) {
      selectedModelId.value = active ? active.id : (models.value.length > 0 ? models.value[0].id : '')
    }
  } catch (e) {
    console.error('加载模型列表失败:', e)
  }
}

// 当前选中角色
const currentRole = computed(() => roles.value.find(r => r.id === selectedRoleId.value) || null)

// 发送消息（流式）
async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  // 添加用户消息
  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  autoResize()
  loading.value = true

  // 预先创建一条空的 assistant 消息，用于流式追加内容
  messages.value.push({
    role: 'assistant',
    content: '',
    streaming: true,
    localFallback: false
  })
  const assistantIndex = messages.value.length - 1

  nextTick(scrollToBottom)

  try {
    await sendMessageStream({
      sessionId: currentSessionId.value,
      message: text,
      roleId: selectedRoleId.value,
      model: selectedModelId.value
    }, {
      onSession: (info) => {
        if (!currentSessionId.value && info.sessionId) {
          currentSessionId.value = info.sessionId
        }
      },
      onToken: (token) => {
        messages.value[assistantIndex].content += token
        nextTick(scrollToBottom)
      },
      onDone: (info) => {
        messages.value[assistantIndex].streaming = false
        messages.value[assistantIndex].localFallback = info.localFallback
        loading.value = false
        loadSessions()
      },
      onError: (msg) => {
        messages.value[assistantIndex].streaming = false
        if (!messages.value[assistantIndex].content) {
          messages.value[assistantIndex].content = `抱歉，出了点问题：${msg}`
        }
        loading.value = false
      }
    })
  } catch (e) {
    messages.value[assistantIndex].streaming = false
    if (!messages.value[assistantIndex].content) {
      messages.value[assistantIndex].content = `抱歉，发送消息时出了点问题：${e.message}`
    }
    loading.value = false
  }
}

// 快捷消息
function sendQuickMessage(text) {
  inputText.value = text
  sendMessage()
}

// 键盘事件
function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

// 自动调整高度
function autoResize() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 120) + 'px'
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
  loadRoles()
  loadModels()
  nextTick(() => inputRef.value?.focus())
})
</script>

<style scoped>
.chat-view {
  display: flex;
  height: 100%;
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
}

.new-chat-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px;
  background: var(--primary);
  color: white;
  border-radius: var(--border-radius-sm);
  font-size: 14px;
  transition: var(--transition);
}

.new-chat-btn:hover {
  background: var(--primary-dark);
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

/* 角色 / 模型选择栏 */
.role-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 24px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-card);
  flex-wrap: wrap;
}

.role-pills {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.role-pill {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 18px;
  border: 1px solid var(--border-color);
  background: var(--bg-main);
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: var(--transition);
}

.role-pill:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.role-pill.active {
  background: var(--primary);
  border-color: var(--primary);
  color: white;
}

.role-avatar {
  font-size: 15px;
}

.model-select select {
  padding: 6px 10px;
  border-radius: 10px;
  border: 1px solid var(--border-color);
  background: var(--bg-main);
  color: var(--text-primary);
  font-size: 13px;
  cursor: pointer;
  outline: none;
}

.model-select select:focus {
  border-color: var(--primary);
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

/* 欢迎界面 */
.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
  gap: 12px;
}

.welcome-icon {
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

.welcome h2 {
  font-size: 22px;
  color: var(--text-primary);
}

.welcome p {
  color: var(--text-secondary);
  font-size: 14px;
}

.welcome-suggestions {
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

.suggestion-emoji {
  font-size: 16px;
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
  background: linear-gradient(135deg, #6366f1, #a78bfa);
  color: white;
}

.message-bubble {
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.6;
  position: relative;
}

.message-item.user .message-bubble {
  background: var(--primary);
  color: white;
  border-top-right-radius: 4px;
}

.message-item.assistant .message-bubble {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-top-left-radius: 4px;
  box-shadow: var(--shadow-sm);
}

.local-badge {
  display: inline-block;
  margin-top: 8px;
  padding: 2px 8px;
  background: var(--warning);
  color: white;
  font-size: 11px;
  border-radius: 4px;
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
  background: var(--bg-input);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  padding: 8px 8px 8px 16px;
  transition: var(--transition);
}

.input-wrapper:focus-within {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-bg);
}

.input-textarea {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 14px;
  line-height: 1.6;
  resize: none;
  color: var(--text-primary);
  max-height: 120px;
}

.send-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--primary);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: var(--transition);
}

.send-btn:hover:not(:disabled) {
  background: var(--primary-dark);
  transform: scale(1.05);
}

.send-btn:disabled {
  background: var(--text-tertiary);
  cursor: not-allowed;
}

.input-hint {
  text-align: center;
  color: var(--text-tertiary);
  font-size: 12px;
  margin-top: 8px;
}
</style>
