<template>
  <div class="role-manage">
    <!-- 顶部操作栏 -->
    <div class="page-header">
      <div>
        <h2 class="page-title">角色管理</h2>
        <p class="page-sub">角色数据保存在数据库，可在此自由新增、编辑、启用/停用、删除。</p>
      </div>
      <button class="primary-btn" @click="openCreate">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" />
          <line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        新增角色
      </button>
    </div>

    <!-- 角色表格 -->
    <div class="table-card">
      <table class="role-table" v-if="roles.length > 0">
        <thead>
          <tr>
            <th style="width: 120px">角色ID</th>
            <th style="width: 60px">图标</th>
            <th style="width: 120px">名称</th>
            <th>描述</th>
            <th style="width: 80px">排序</th>
            <th style="width: 90px">状态</th>
            <th style="width: 140px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in roles" :key="r.id">
            <td><code class="role-id">{{ r.id }}</code></td>
            <td class="avatar-cell">{{ r.avatar || '—' }}</td>
            <td>{{ r.name }}</td>
            <td class="desc-cell">{{ r.description || '—' }}</td>
            <td>{{ r.sortOrder ?? '—' }}</td>
            <td>
              <span class="status-tag" :class="r.enabled ? 'on' : 'off'">
                {{ r.enabled ? '已启用' : '已停用' }}
              </span>
            </td>
            <td>
              <button class="link-btn" @click="openEdit(r)">编辑</button>
              <button class="link-btn danger" @click="removeRole(r)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-tip">暂无角色，点击右上角「新增角色」创建一个吧。</div>
    </div>

    <!-- 新增/编辑弹窗 -->
    <div class="modal-mask" v-if="showModal" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ isEdit ? '编辑角色' : '新增角色' }}</h3>
          <button class="modal-close" @click="closeModal">✕</button>
        </div>

        <div class="modal-body">
          <div class="form-row">
            <label>角色ID <span class="req">*</span></label>
            <input
              v-model="form.id"
              :disabled="isEdit"
              class="form-input"
              placeholder="如 chat_buddy（字母/数字/下划线，2~32位）"
            />
            <span class="form-hint" v-if="isEdit">编辑时不可修改</span>
          </div>

          <div class="form-row">
            <label>名称 <span class="req">*</span></label>
            <input v-model="form.name" class="form-input" placeholder="如 聊天伙伴" />
          </div>

          <div class="form-row two-col">
            <div>
              <label>图标(emoji)</label>
              <input v-model="form.avatar" class="form-input" placeholder="如 💬" maxlength="8" />
            </div>
            <div>
              <label>排序</label>
              <input v-model.number="form.sortOrder" type="number" class="form-input" placeholder="数字越小越靠前" />
            </div>
          </div>

          <div class="form-row">
            <label>描述</label>
            <input v-model="form.description" class="form-input" placeholder="一句话简介，展示在选择卡片上" />
          </div>

          <div class="form-row">
            <label>系统提示词 <span class="req">*</span></label>
            <textarea
              v-model="form.systemPrompt"
              class="form-textarea"
              rows="10"
              placeholder="定义角色的人设、语气、能力边界，例如：你是用户的贴心聊天小伙伴……"
            ></textarea>
          </div>

          <div class="form-row checkbox-row">
            <label class="checkbox-label">
              <input type="checkbox" v-model="form.enabled" />
              启用（停用后聊天选择器不展示，也无法用于对话）
            </label>
          </div>
        </div>

        <div class="modal-footer">
          <button class="ghost-btn" @click="closeModal">取消</button>
          <button class="primary-btn" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getAdminRoles, createRole, updateRole, deleteRole } from '../api/role'
import { confirm as showConfirm, toast } from '../utils/message'

const roles = ref([])
const showModal = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const editingId = ref('')

const emptyForm = () => ({
  id: '',
  name: '',
  avatar: '',
  description: '',
  systemPrompt: '',
  sortOrder: 0,
  enabled: true
})
const form = ref(emptyForm())

async function loadRoles() {
  try {
    roles.value = await getAdminRoles()
  } catch (e) {
    console.error('加载角色失败:', e)
    toast.error('加载角色失败：' + e.message)
  }
}

function openCreate() {
  isEdit.value = false
  editingId.value = ''
  form.value = emptyForm()
  showModal.value = true
}

function openEdit(role) {
  isEdit.value = true
  editingId.value = role.id
  form.value = {
    id: role.id,
    name: role.name,
    avatar: role.avatar || '',
    description: role.description || '',
    systemPrompt: role.systemPrompt || '',
    sortOrder: role.sortOrder ?? 0,
    enabled: role.enabled !== false
  }
  showModal.value = true
}

function closeModal() {
  showModal.value = false
}

async function save() {
  if (!form.value.id.trim()) return toast.warn('请填写角色ID')
  if (!/^[A-Za-z0-9_]{2,32}$/.test(form.value.id.trim())) return toast.warn('角色ID仅允许字母、数字、下划线，长度2~32')
  if (!form.value.name.trim()) return toast.warn('请填写名称')
  if (!form.value.systemPrompt.trim()) return toast.warn('请填写系统提示词')

  saving.value = true
  try {
    if (isEdit.value) {
      await updateRole(editingId.value, form.value)
    } else {
      await createRole(form.value)
    }
    closeModal()
    await loadRoles()
  } catch (e) {
    toast.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

async function removeRole(role) {
  if (!(await showConfirm(`确定删除角色「${role.name}」(${role.id}) 吗？此操作不可恢复。`))) return
  try {
    await deleteRole(role.id)
    await loadRoles()
  } catch (e) {
    toast.error('删除失败：' + e.message)
  }
}

onMounted(loadRoles)
</script>

<style scoped>
.role-manage {
  padding: 24px 32px;
  height: 100%;
  overflow-y: auto;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
}

.page-title {
  font-size: 22px;
  color: var(--text-primary);
  margin: 0;
}

.page-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--text-tertiary);
}

.primary-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 16px;
  background: var(--primary);
  color: white;
  border: none;
  border-radius: var(--border-radius-sm);
  font-size: 14px;
  cursor: pointer;
  transition: var(--transition);
}
.primary-btn:hover:not(:disabled) { background: var(--primary-dark); }
.primary-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.table-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--border-radius);
  overflow: hidden;
}

.role-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.role-table th {
  text-align: left;
  padding: 12px 16px;
  background: var(--bg-hover);
  color: var(--text-secondary);
  font-weight: 600;
  border-bottom: 1px solid var(--border-color);
}

.role-table td {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color);
  color: var(--text-primary);
  vertical-align: middle;
}

.role-table tr:last-child td { border-bottom: none; }

.role-id {
  background: var(--bg-input);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

.avatar-cell { font-size: 18px; text-align: center; }
.desc-cell { color: var(--text-secondary); max-width: 320px; }

.status-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 12px;
  font-size: 12px;
}
.status-tag.on { background: rgba(34, 197, 94, 0.15); color: var(--success); }
.status-tag.off { background: rgba(148, 163, 184, 0.18); color: var(--text-tertiary); }

.link-btn {
  background: none;
  border: none;
  color: var(--primary);
  cursor: pointer;
  font-size: 13px;
  margin-right: 12px;
}
.link-btn.danger { color: var(--danger); }

.empty-tip {
  padding: 48px;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 14px;
}

/* 弹窗 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.modal {
  width: 560px;
  max-width: 92vw;
  max-height: 90vh;
  background: var(--bg-card);
  border-radius: var(--border-radius);
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow);
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color);
}
.modal-header h3 { margin: 0; font-size: 17px; color: var(--text-primary); }
.modal-close {
  background: none; border: none; font-size: 18px; cursor: pointer; color: var(--text-tertiary);
}

.modal-body {
  padding: 20px;
  overflow-y: auto;
}

.form-row { margin-bottom: 16px; }
.form-row label {
  display: block;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
.req { color: var(--danger); }

.form-input, .form-textarea {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--border-color);
  border-radius: var(--border-radius-sm);
  background: var(--bg-input);
  color: var(--text-primary);
  font-size: 14px;
  outline: none;
  font-family: inherit;
}
.form-input:focus, .form-textarea:focus { border-color: var(--primary); }
.form-textarea { resize: vertical; line-height: 1.6; }
.form-input:disabled { background: var(--bg-hover); color: var(--text-tertiary); cursor: not-allowed; }

.form-hint { font-size: 12px; color: var(--text-tertiary); margin-left: 8px; }

.two-col { display: flex; gap: 16px; }
.two-col > div { flex: 1; }

.checkbox-row { margin-top: 4px; }
.checkbox-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 14px 20px;
  border-top: 1px solid var(--border-color);
}

.ghost-btn {
  padding: 9px 18px;
  background: var(--bg-hover);
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
  border-radius: var(--border-radius-sm);
  cursor: pointer;
  font-size: 14px;
}
.ghost-btn:hover { background: var(--bg-input); }
</style>
