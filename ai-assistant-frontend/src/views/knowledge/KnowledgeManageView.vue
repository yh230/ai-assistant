<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">知识库管理</h2>
        <p class="page-sub">上传 md / txt 文档供「知识库问答」检索，文档仅本院可见，上传后自动切片向量化。</p>
      </div>
    </div>

    <!-- 上传区（保留自研上传卡片结构，不使用 el-upload） -->
    <div class="upload-card">
      <div class="upload-info">
        <span class="upload-title">上传文档</span>
        <span class="upload-hint">支持 .md / .txt，大小不超过 2MB</span>
      </div>
      <div class="upload-actions">
        <span class="file-name" :title="selectedFile ? selectedFile.name : ''">
          {{ selectedFile ? selectedFile.name : '未选择文件' }}
        </span>
        <el-button @click="chooseFile">选择文件</el-button>
        <el-button type="primary" :disabled="!selectedFile || uploading" @click="doUpload" data-testid="knowledge-upload">
          {{ uploading ? '上传中…' : '上传' }}
        </el-button>
      </div>
      <input ref="fileInputRef" type="file" accept=".md,.txt" class="hidden-input" data-testid="knowledge-file-input" @change="onFileChange" />
    </div>

    <!-- 表格 -->
    <div class="table-card">
      <el-table v-if="list.length > 0" :data="list" stripe data-testid="knowledge-table">
        <el-table-column label="ID" width="64">
          <template #default="{ row }"><code class="id-cell" :title="String(row.id)">{{ row.id }}</code></template>
        </el-table-column>
        <el-table-column label="文件名">
          <template #default="{ row }"><span :title="row.name">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ row.type ? '.' + row.type : '—' }}</template>
        </el-table-column>
        <el-table-column label="大小 (KB)" width="120">
          <template #default="{ row }">{{ formatKb(row.size) }}</template>
        </el-table-column>
        <el-table-column label="切片数" width="100">
          <template #default="{ row }">{{ row.chunkCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.status === 1" type="success" size="small">就绪</el-tag>
            <el-tag v-else type="warning" size="small">处理中</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" width="170">
          <template #default="{ row }"><span class="time-cell" :title="formatTime(row.createTime)">{{ formatTime(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeDoc(row)" data-testid="knowledge-delete">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>{{ loading ? '加载中…' : '暂无知识库文档' }}</template>
      </el-table>
      <div v-else class="empty-tip" data-testid="knowledge-empty">{{ loading ? '加载中…' : '暂无知识库文档' }}</div>
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
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { knowledgeList, knowledgeUpload, knowledgeDelete } from '../../api/admin'
import { formatTime } from '../../utils/format'
import { confirm as showConfirm, toast } from '../../utils/message'

// 上传前校验口径（与后端一致：仅 md/txt，≤2MB）
const ALLOWED_EXTENSIONS = ['md', 'txt']
const MAX_SIZE = 2 * 1024 * 1024

const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const fileInputRef = ref(null)
const selectedFile = ref(null)
const uploading = ref(false)

async function loadData() {
  loading.value = true
  try {
    const res = await knowledgeList({ pageNum: pageNum.value, pageSize: pageSize.value })
    list.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    toast.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

function changePage(p) {
  pageNum.value = p
  loadData()
}

// 打开系统文件选择框
function chooseFile() {
  fileInputRef.value?.click()
}

// 选择文件后校验（扩展名 / 大小），不合规则中文提示并清空
function onFileChange(e) {
  const file = e.target.files && e.target.files[0]
  // 清空 input 值，保证同一文件再次选择也能触发 change
  e.target.value = ''
  if (!file) return
  const message = validateFile(file)
  if (message) {
    toast.warn(message)
    selectedFile.value = null
    return
  }
  selectedFile.value = file
}

function validateFile(file) {
  const ext = (file.name.split('.').pop() || '').toLowerCase()
  if (!ALLOWED_EXTENSIONS.includes(ext)) return '仅支持 .md / .txt 格式的文档'
  if (file.size > MAX_SIZE) return '文档大小不能超过 2MB'
  return null
}

async function doUpload() {
  const file = selectedFile.value
  if (!file || uploading.value) return
  const message = validateFile(file)
  if (message) {
    toast.warn(message)
    return
  }
  uploading.value = true
  try {
    await knowledgeUpload(file)
    toast.success('上传成功，处理中，稍后刷新查看状态')
    selectedFile.value = null
    pageNum.value = 1
    await loadData()
  } catch (e) {
    toast.error('上传失败：' + e.message)
  } finally {
    uploading.value = false
  }
}

// 删除（二次确认后同步清理向量分片）
async function removeDoc(doc) {
  if (!(await showConfirm(`确定删除文档「${doc.name}」吗？删除后将同步清理向量分片，且不可恢复。`))) return
  try {
    await knowledgeDelete(doc.id)
    toast.success('已删除')
    // 删除当前页最后一条时回退一页
    if (list.value.length === 1 && pageNum.value > 1) {
      pageNum.value -= 1
    }
    await loadData()
  } catch (e) {
    toast.error('删除失败：' + e.message)
  }
}

// 字节转 KB 展示（保留 1 位小数）
function formatKb(size) {
  if (size == null) return '—'
  return (size / 1024).toFixed(1)
}

onMounted(loadData)
</script>

<style scoped>
.upload-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 20px;
  margin-bottom: 16px;
  background: var(--bg-card);
  border: 1px dashed var(--el-color-primary-light-5, var(--border-color));
  border-radius: var(--border-radius);
}

.upload-info {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.upload-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.upload-hint {
  font-size: 12px;
  color: var(--text-tertiary);
}

.upload-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.file-name {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--text-secondary);
}

.hidden-input {
  display: none;
}
</style>
