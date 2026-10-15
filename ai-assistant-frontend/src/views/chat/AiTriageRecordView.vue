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
      <el-select v-model="sessionType" placeholder="全部类型" clearable style="width: 180px" @change="reload">
        <el-option label="AI 导诊" value="TRIAGE" />
        <el-option label="知识库问答" value="RAG" />
      </el-select>
      <el-button @click="reload">刷新</el-button>
    </div>

    <!-- 表格 -->
    <div class="table-card">
      <el-table :data="list" stripe>
        <el-table-column label="会话标题" show-overflow-tooltip>
          <template #default="{ row }"><span :title="row.title || ''">{{ row.title || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag :type="tagType(row.sessionType)" effect="light">{{ typeLabel(row.sessionType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="患者姓名" width="120" show-overflow-tooltip>
          <template #default="{ row }"><span :title="row.patientName || ''">{{ row.patientName || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="患者手机号" width="130" show-overflow-tooltip>
          <template #default="{ row }"><span :title="row.patientPhone || ''">{{ row.patientPhone || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }"><span class="time-cell" :title="formatTime(row.updatedAt)">{{ formatTime(row.updatedAt) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
        <template #empty>{{ loading ? '加载中…' : '暂无问诊记录' }}</template>
      </el-table>
    </div>

    <!-- 分页 -->
    <div class="pager" v-if="total > 0">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :current-page="pageNum"
        :page-size="pageSize"
        @current-change="changePage"
      />
    </div>

    <!-- 会话详情弹层 -->
    <el-dialog v-model="showDetail" :title="`会话详情 · ${detailSession?.title || '未命名会话'}`" width="680px" @close="closeDetail">
      <div class="detail-meta" v-if="detailSession">
        <el-tag :type="tagType(detailSession.sessionType)" effect="light">{{ typeLabel(detailSession.sessionType) }}</el-tag>
        <span>患者：{{ detailSession.patientName || '—' }}</span>
        <span>手机号：{{ detailSession.patientPhone || '—' }}</span>
        <span class="time-cell">更新：{{ formatTime(detailSession.updatedAt) }}</span>
      </div>
      <div class="message-body">
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
      <template #footer>
        <el-button @click="closeDetail">关闭</el-button>
      </template>
    </el-dialog>
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

// 类型三色：闲聊=info / 导诊=danger（红线）/ 知识库=success
function tagType(type) {
  if (type === 'TRIAGE') return 'danger'
  if (type === 'RAG') return 'success'
  return 'info'
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
.empty-tip {
  text-align: center;
  padding: 32px 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

/* 会话详情弹层（消息冒泡） */
.detail-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  padding: 0 0 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.message-body {
  min-height: 200px;
  max-height: 56vh;
  overflow-y: auto;
  padding-top: 12px;
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
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
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
  background: var(--el-color-primary);
  color: white;
}

.record-item.assistant .record-avatar {
  background: linear-gradient(135deg, var(--el-color-primary-light-3), var(--el-color-primary-light-5));
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
  background: var(--el-color-primary);
  color: white;
}

.record-item.assistant .record-bubble {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-top-left-radius: 4px;
  box-shadow: var(--el-box-shadow-light);
}

.record-time {
  margin-top: 6px;
  font-size: 11px;
  opacity: 0.65;
}

.reference-block {
  margin-top: 10px;
  padding: 8px 10px;
  border-left: 3px solid var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-radius: 6px;
  font-size: 12px;
}

.reference-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 4px;
}

.reference-item {
  display: flex;
  gap: 6px;
  margin-top: 2px;
  color: var(--el-text-color-secondary);
}

.reference-name {
  color: var(--el-color-primary);
  flex-shrink: 0;
}

.reference-snippet {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
