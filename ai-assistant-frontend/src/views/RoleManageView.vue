<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">角色管理</h2>
        <p class="page-sub">员工角色全局共用；患者角色按医院与会话类型隔离。角色数据保存在数据库，可在此新增、编辑、启用/停用、删除。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增角色</el-button>
    </div>

    <div class="table-card">
      <div class="scope-bar">
        <el-radio-group v-model="scope" @change="onScopeChange">
          <el-radio-button value="STAFF">员工角色</el-radio-button>
          <el-radio-button value="PATIENT">患者角色</el-radio-button>
        </el-radio-group>
        <el-select
          v-if="needsHospitalPicker"
          v-model="selectedHospitalId"
          class="hospital-select"
          placeholder="请选择医院"
          :loading="hospitalsLoading"
          filterable
          @change="loadRoles"
        >
          <el-option
            v-for="h in hospitalOptions"
            :key="h.id"
            :label="h.name"
            :value="h.id"
          />
        </el-select>
        <span class="scope-hint">{{ scopeHint }}</span>
      </div>

      <el-table v-if="roles.length > 0" :data="roles" stripe>
        <el-table-column label="角色ID" width="120">
          <template #default="{ row }"><code class="id-cell">{{ row.id }}</code></template>
        </el-table-column>
        <el-table-column label="图标" width="60">
          <template #default="{ row }"><span class="avatar-cell">{{ row.avatar || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" width="120" />
        <el-table-column v-if="scope === 'PATIENT'" label="会话类型" width="120">
          <template #default="{ row }">
            <el-tag effect="light">{{ typeLabel(row.sessionType) }}</el-tag>
          </template>
        </el-table-column>
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
        <el-form-item v-if="scope === 'STAFF' || isEdit" label="角色ID" required>
          <el-input
            v-model="form.id"
            :disabled="isEdit"
            placeholder="如 chat_buddy（字母/数字/下划线，2~32位）"
          />
          <span class="form-hint" v-if="isEdit">编辑时不可修改</span>
        </el-form-item>
        <el-form-item v-else label="角色ID">
          <span class="form-hint">患者角色ID由服务端生成（pat_医院_会话类型），无需填写</span>
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

        <el-form-item v-if="scope === 'PATIENT'" label="会话类型" required>
          <el-select
            v-model="form.sessionType"
            :disabled="isEdit"
            placeholder="请选择会话类型"
            style="width: 200px"
          >
            <el-option v-for="t in SESSION_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <span class="form-hint" v-if="isEdit">会话类型创建后不可修改</span>
        </el-form-item>

        <el-form-item label="描述">
          <el-input v-model="form.description" placeholder="一句话简介，展示在选择卡片上" />
        </el-form-item>

        <el-form-item label="系统提示词" required>
          <el-input
            v-model="form.systemPrompt"
            type="textarea"
            :rows="10"
            placeholder="定义角色的人设、语气、能力边界，例如：你是医院场景的贴心导诊助手……"
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
import { ref, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { getAdminRoles, createRole, updateRole, deleteRole } from '../api/role'
import { publicHospitals } from '../api/patient'
import { useAuth } from '../store/auth'
import { confirm as showConfirm, toast } from '../utils/message'

// 会话类型显示名（与患者端口径一致）
const TYPE_LABELS = { CHAT: '闲聊', TRIAGE: 'AI 导诊', RAG: '知识库问答' }
const SESSION_TYPES = [
  { value: 'CHAT', label: '闲聊' },
  { value: 'TRIAGE', label: 'AI 导诊' },
  { value: 'RAG', label: '知识库问答' }
]

const { userInfo } = useAuth()

// 角色域：STAFF-员工，PATIENT-患者
const scope = ref('STAFF')
// 登录人已绑定医院时由服务端强制使用本人医院，无需选择
const boundHospitalId = computed(() => userInfo.value?.hospitalId)
const needsHospitalPicker = computed(() => scope.value === 'PATIENT' && !boundHospitalId.value)

const scopeHint = computed(() => {
  if (scope.value !== 'PATIENT') return '员工角色全局共用，聊天时供员工选择'
  return boundHospitalId.value
    ? '患者角色按医院隔离，已自动使用您所属医院'
    : '患者角色按医院隔离，请选择医院后维护该院角色'
})

const hospitalOptions = ref([])
const hospitalsLoading = ref(false)
const selectedHospitalId = ref(null)

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
  enabled: true,
  sessionType: 'CHAT'
})
const form = ref(emptyForm())

function typeLabel(type) {
  return TYPE_LABELS[type] || type || '—'
}

// 未绑院账号需显式选择医院；未选择前不请求列表（服务端要求 hospitalId）
async function loadRoles() {
  const hospitalId = needsHospitalPicker.value ? selectedHospitalId.value : null
  if (needsHospitalPicker.value && !hospitalId) {
    roles.value = []
    return
  }
  try {
    roles.value = await getAdminRoles(scope.value, hospitalId)
  } catch (e) {
    console.error('加载角色失败:', e)
    toast.error('加载角色失败：' + e.message)
  }
}

async function loadHospitals() {
  if (hospitalOptions.value.length > 0) return
  hospitalsLoading.value = true
  try {
    hospitalOptions.value = (await publicHospitals()) || []
  } catch (e) {
    console.error('加载医院列表失败:', e)
    toast.error('加载医院列表失败：' + e.message)
  } finally {
    hospitalsLoading.value = false
  }
}

// 切换角色域：关闭并重置表单，重新拉取当前域角色
function onScopeChange() {
  showModal.value = false
  isEdit.value = false
  editingId.value = ''
  form.value = emptyForm()
  roles.value = []
  loadRoles()
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
    enabled: role.enabled !== false,
    sessionType: role.sessionType || 'CHAT'
  }
  showModal.value = true
}

function closeModal() {
  showModal.value = false
}

async function save() {
  const patientCreate = scope.value === 'PATIENT' && !isEdit.value
  if (!patientCreate) {
    if (!form.value.id.trim()) return toast.warn('请填写角色ID')
    if (!/^[A-Za-z0-9_]{2,32}$/.test(form.value.id.trim())) return toast.warn('角色ID仅允许字母、数字、下划线，长度2~32')
  }
  if (!form.value.name.trim()) return toast.warn('请填写名称')
  if (!form.value.systemPrompt.trim()) return toast.warn('请填写系统提示词')
  if (scope.value === 'PATIENT' && !form.value.sessionType) return toast.warn('请选择会话类型')
  if (patientCreate && needsHospitalPicker.value && !selectedHospitalId.value) return toast.warn('请先选择医院')

  // 提交载荷：始终带 scope；新增患者角色时携带会话类型，未绑院账号还需携带所选医院；
  // 患者角色 ID 由服务端派生，不提交客户端 ID
  const payload = {
    name: form.value.name.trim(),
    avatar: form.value.avatar,
    description: form.value.description,
    systemPrompt: form.value.systemPrompt,
    sortOrder: form.value.sortOrder,
    enabled: form.value.enabled,
    scope: scope.value
  }
  if (!patientCreate) payload.id = form.value.id.trim()
  if (patientCreate) {
    payload.sessionType = form.value.sessionType
    if (needsHospitalPicker.value) payload.hospitalId = selectedHospitalId.value
  }

  saving.value = true
  try {
    if (isEdit.value) {
      await updateRole(editingId.value, payload)
    } else {
      await createRole(payload)
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

onMounted(() => {
  if (!boundHospitalId.value) loadHospitals()
  loadRoles()
})
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

.scope-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.scope-hint {
  font-size: 12px;
  color: var(--text-tertiary);
}

.hospital-select {
  width: 220px;
}
</style>
