<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">AI 问诊记录</h2>
        <p class="page-sub">查看患者与 AI 的导诊 / 知识库问答会话，仅本院数据。</p>
      </div>
    </div>

    <!-- 筛选 -->
    <div class="toolbar">
      <select v-model="sessionType" class="search-input type-select" @change="reload">
        <option value="">全部类型</option>
        <option value="TRIAGE">AI 导诊</option>
        <option value="RAG">知识库问答</option>
      </select>
      <button class="ghost-btn" @click="reload">刷新</button>
    </div>

    <!-- 表格 -->
    <div class="table-card">
      <table class="data-table" v-if="list.length > 0">
        <thead>
          <tr>
            <th>会话标题</th>
            <th style="width: 110px">类型</th>
            <th style="width: 120px">患者姓名</th>
            <th style="width: 130px">患者手机号</th>
            <th style="width: 170px">更新时间</th>
            <th style="width: 90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in list" :key="s.sessionId">
            <td :title="s.title || ''">{{ s.title || '—' }}</td>
            <td>
              <span class="type-tag" :class="s.sessionType">{{ typeLabel(s.sessionType) }}</span>
            </td>
            <td :title="s.patientName || ''">{{ s.patientName || '—' }}</td>
            <td :title="s.patientPhone || ''">{{ s.patientPhone || '—' }}</td>
            <td class="time-cell" :title="formatTime(s.updatedAt)">{{ formatTime(s.updatedAt) }}</td>
            <td>
              <button class="link-btn" @click="openDetail(s)">查看</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-tip">{{ loading ? '加载中…' : '暂无问诊记录' }}</div>
    </div>

    <!-- 分页 -->
    <div class="pager" v-if="total > 0">
      <button class="ghost-btn" :disabled="pageNum <= 1" @click="changePage(pageNum - 1)">上一页</button>
      <span class="pager-info">第 {{ pageNum }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
      <button class="ghost-btn" :disabled="pageNum >= totalPages" @click="changePage(pageNum + 1)">下一页</button>
    </div>

    <!-- 会话详情弹层 -->
    <div class="modal-mask" v-if="showDetail" @click.self="closeDetail">
      <div class="modal detail-modal">
        <div class="modal-header">
          <h3>会话详情 · {{ detailSession?.title || '未命名会话' }}</h3>
          <button class="modal-close" @click="closeDetail">✕</button>
        </div>
        <div class="detail-meta" v-if="detailSession">
          <span class="type-tag" :class="detailSession.sessionType">{{ typeLabel(detailSession.sessionType) }}</span>
          <span>患者：{{ detailSession.patientName || '—' }}</span>
          <span>手机号：{{ detailSession.patientPhone || '—' }}</span>
          <span class="time-cell">更新：{{ formatTime(detailSession.updatedAt) }}</span>
        </div>
        <div class="modal-body message-body">
          <div v-if="detailLoading" class="empty-tip">加载中…</div>
          <template v-else>
            <template v-for="(m, index) in messages" :key="index">
              <div v-if="m.role === 'system'" class="record-item system">
                <div class="system-msg">{{ m.content }}</div>
              </div>
              <div v-else class="record-item" :class="m.role">
                <div class="record-avatar">{{ m.role === 'user' ? '患' : '医' }}</div>
                <div class="record-bubble">
                  <div class="markdown-body" v-html="renderMarkdownSafe(m.content)"></div>
                  <div v-if="m.references && m.references.length" class="reference-block">
                    <div class="reference-title">参考来源</div>
                    <div v-for="(ref, ri) in m.references" :key="ri" class="reference-item">
                      <span class="reference-name">{{ ref.name }}</span>
                      <span class="reference-snippet">{{ ref.snippet }}</span>
                    </div>
                  </div>
                  <div class="record-time" v-if="m.createdAt">{{ formatTime(m.createdAt) }}</div>
                </div>
              </div>
            </template>
            <div v-if="messages.length === 0" class="empty-tip">暂无消息</div>
          </template>
        </div>
        <div class="modal-footer">
          <button class="ghost-btn" @click="closeDetail">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { adminSessions, adminSessionHistory } from '../../api/admin'
import { formatTime } from '../../utils/format'
import { toast } from '../../utils/message'
import { renderMarkdownSafe } from '../../utils/safeMarkdown'

// 会话类型显示名（与患者端口径一致：CHAT=闲聊 / TRIAGE=导诊 / RAG=知识库）
const TYPE_LABELS = { CHAT: '闲聊', TRIAGE: 'AI 导诊', RAG: '知识库问答' }

const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const sessionType = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const showDetail = ref(false)
const detailLoading = ref(false)
const detailSession = ref(null)
const messages = ref([])

function typeLabel(type) {
  return TYPE_LABELS[type] || type || '—'
}

async function loadData() {
  loading.value = true
  try {
    const res = await adminSessions({
      sessionType: sessionType.value || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    list.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    toast.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

function reload() {
  pageNum.value = 1
  loadData()
}

function changePage(p) {
  pageNum.value = p
  loadData()
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

// 查看会话消息详情
async function openDetail(s) {
  detailSession.value = s
  messages.value = []
  showDetail.value = true
  detailLoading.value = true
  try {
    const data = await adminSessionHistory(s.sessionId)
    messages.value = (data.messages || []).map(m => ({
      role: m.role,
      content: m.content,
      createdAt: m.createdAt,
      references: parseReferences(m.referenceJson)
    }))
  } catch (e) {
    toast.error('加载会话详情失败：' + e.message)
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  showDetail.value = false
  detailSession.value = null
  messages.value = []
}

onMounted(loadData)
</script>

<style scoped>
.type-select {
  width: 180px;
}

/* 会话详情弹层（消息冒泡） */
.detail-modal {
  width: 680px;
}

.detail-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border-color);
  font-size: 13px;
  color: var(--text-secondary);
}

.message-body {
  min-height: 200px;
  max-height: 56vh;
}

.record-item {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
  max-width: 88%;
  align-items: flex-start;
}

.record-item.user {
  flex-direction: row-reverse;
  margin-left: auto;
}

.record-item.system {
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
}

.record-avatar {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.record-item.user .record-avatar {
  background: var(--primary);
  color: white;
}

.record-item.assistant .record-avatar {
  background: linear-gradient(135deg, #6366f1, #a78bfa);
  color: white;
}

.record-bubble {
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.6;
  min-width: 0;
}

.record-item.user .record-bubble {
  background: var(--primary);
  color: white;
}

.record-item.assistant .record-bubble {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-top-left-radius: 4px;
  box-shadow: var(--shadow-sm);
}

.record-time {
  margin-top: 6px;
  font-size: 11px;
  opacity: 0.65;
}

.type-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 12px;
  font-size: 12px;
  background: var(--bg-hover);
  color: var(--text-tertiary);
}

.type-tag.TRIAGE {
  background: var(--primary-bg);
  color: var(--primary-dark);
}

.type-tag.RAG {
  background: rgba(16, 185, 129, 0.12);
  color: var(--success);
}

.reference-block {
  margin-top: 10px;
  padding: 8px 10px;
  border-left: 3px solid #3b82f6;
  background: rgba(59, 130, 246, 0.06);
  border-radius: 6px;
  font-size: 12px;
}

.reference-title {
  font-weight: 600;
  color: #475569;
  margin-bottom: 4px;
}

.reference-item {
  display: flex;
  gap: 6px;
  margin-top: 2px;
  color: #64748b;
}

.reference-name {
  color: #2563eb;
  flex-shrink: 0;
}

.reference-snippet {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
