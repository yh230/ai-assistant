<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">角色管理</h2>
        <p class="page-sub">角色数据保存在数据库，可在此自由新增、编辑、启用/停用、删除。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增角色</el-button>
    </div>

    <div class="table-card">
      <el-table v-if="roles.length > 0" :data="roles" stripe>
        <el-table-column label="角色ID" width="120">
          <template #default="{ row }"><code class="id-cell">{{ row.id }}</code></template>
        </el-table-column>
        <el-table-column label="图标" width="60">
          <template #default="{ row }"><span class="avatar-cell">{{ row.avatar || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" width="120" />
        <el-table-column label="描述">
          <template #default="{ row }"><span class="desc-cell">{{ row.description || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="排序" width="80">
          <template #default="{ row }">{{ row.sortOrder ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="light">
              {{ row.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="removeRole(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无角色，点击右上角「新增角色」创建一个吧。" :image-size="96" />
    </div>

    <el-dialog v-model="showModal" :title="isEdit ? '编辑角色' : '新增角色'" width="560px">
      <el-form label-position="top">
        <el-form-item label="角色ID" required>
          <el-input
            v-model="form.id"
            :disabled="isEdit"
            placeholder="如 chat_buddy（字母/数字/下划线，2~32位）"
          />
          <span class="form-hint" v-if="isEdit">编辑时不可修改</span>
        </el-form-item>

        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 聊天伙伴" />
        </el-form-item>

        <el-form-item label="图标(emoji)">
          <el-input v-model="form.avatar" placeholder="如 💬" maxlength="8" />
        </el-form-item>

        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" style="width: 200px" />
        </el-form-item>

        <el-form-item label="描述">
          <el-input v-model="form.description" placeholder="一句话简介，展示在选择卡片上" />
        </el-form-item>

        <el-form-item label="系统提示词" required>
          <el-input
            v-model="form.systemPrompt"
            type="textarea"
            :rows="10"
            placeholder="定义角色的人设、语气、能力边界，例如：你是用户的贴心聊天小伙伴……"
          />
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="form.enabled">
            启用（停用后聊天选择器不展示，也无法用于对话）
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeModal">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
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
.id-cell {
  background: var(--bg-input);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

.avatar-cell {
  font-size: 18px;
}

.desc-cell {
  color: var(--text-secondary);
}

.form-hint {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-left: 8px;
}
</style>
