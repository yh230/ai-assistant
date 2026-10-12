<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">角色权限</h2>
        <p class="page-sub">维护系统角色，并为角色分配菜单 / 按钮权限。</p>
      </div>
      <button class="primary-btn" @click="openCreate">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        新增角色
      </button>
    </div>

    <div class="toolbar">
      <input v-model="keyword" class="search-input" placeholder="搜索角色编码 / 名称" @keyup.enter="reload" />
      <button class="ghost-btn" @click="reload">查询</button>
    </div>

    <div class="table-card">
      <table class="data-table" v-if="list.length > 0">
        <thead>
          <tr>
            <th style="width: 70px">ID</th>
            <th style="width: 140px">角色编码</th>
            <th>角色名称</th>
            <th>描述</th>
            <th style="width: 160px">创建时间</th>
            <th style="width: 200px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in list" :key="r.id">
            <td><code class="id-cell">{{ r.id }}</code></td>
            <td><code class="code-cell">{{ r.roleCode }}</code></td>
            <td>{{ r.roleName }}</td>
            <td class="desc-cell">{{ r.roleDesc || '—' }}</td>
            <td class="time-cell">{{ formatTime(r.createTime) }}</td>
            <td>
              <button class="link-btn" @click="openAssign(r)">分配权限</button>
              <button class="link-btn" @click="openEdit(r)">编辑</button>
              <button class="link-btn danger" @click="removeRole(r)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-tip">暂无角色数据</div>
    </div>

    <div class="pager" v-if="total > 0">
      <button class="ghost-btn" :disabled="pageNum <= 1" @click="changePage(pageNum - 1)">上一页</button>
      <span class="pager-info">第 {{ pageNum }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
      <button class="ghost-btn" :disabled="pageNum >= totalPages" @click="changePage(pageNum + 1)">下一页</button>
    </div>

    <!-- 新增/编辑角色 -->
    <div class="modal-mask" v-if="showModal" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ isEdit ? '编辑角色' : '新增角色' }}</h3>
          <button class="modal-close" @click="closeModal">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label>角色编码 <span class="req">*</span></label>
            <input v-model="form.roleCode" :disabled="isEdit" class="form-input" placeholder="如 admin（字母数字下划线）" />
            <span class="form-hint" v-if="isEdit">编辑时不可修改</span>
          </div>
          <div class="form-row">
            <label>角色名称 <span class="req">*</span></label>
            <input v-model="form.roleName" class="form-input" placeholder="如 超级管理员" />
          </div>
          <div class="form-row">
            <label>描述</label>
            <textarea v-model="form.roleDesc" class="form-textarea" rows="3" placeholder="角色用途说明"></textarea>
          </div>
        </div>
        <div class="modal-footer">
          <button class="ghost-btn" @click="closeModal">取消</button>
          <button class="primary-btn" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>

    <!-- 分配菜单权限 -->
    <div class="modal-mask" v-if="showAssign" @click.self="showAssign = false">
      <div class="modal modal-lg">
        <div class="modal-header">
          <h3>分配权限 - {{ assignTarget?.roleName }}</h3>
          <button class="modal-close" @click="showAssign = false">✕</button>
        </div>
        <div class="modal-body">
          <div class="tree-panel">
            <MenuTreeCheckbox v-model="checkedMenuIds" :nodes="menuTree" />
          </div>
        </div>
        <div class="modal-footer">
          <button class="ghost-btn" @click="showAssign = false">取消</button>
          <button class="primary-btn" :disabled="savingAssign" @click="saveAssign">
            {{ savingAssign ? '保存中…' : '保存权限' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { pageRoles, createRole, updateRole, deleteRole, assignRoleMenus, getRoleMenus } from '../api/sysrole'
import { allMenus } from '../api/sysmenu'
import MenuTreeCheckbox from '../components/MenuTreeCheckbox.vue'
import { useCrudPage } from '../composables/useCrudPage'
import { formatTime } from '../utils/format'
import { toast } from '../utils/message'

const {
  list, total, pageNum, pageSize, keyword, totalPages,
  showModal, isEdit, saving, form,
  loadData, reload, changePage, openCreate, openEdit, closeModal, save,
  remove: removeRole
} = useCrudPage({
  fetchPage: (params) => pageRoles(params),
  emptyForm: () => ({ roleCode: '', roleName: '', roleDesc: '' }),
  toForm: (r) => ({ roleCode: r.roleCode, roleName: r.roleName, roleDesc: r.roleDesc || '' }),
  validate: (f) => {
    if (!f.roleCode.trim()) return '请填写角色编码'
    if (!f.roleName.trim()) return '请填写角色名称'
    return null
  },
  persist: (f, id) => {
    const payload = { roleCode: f.roleCode.trim(), roleName: f.roleName.trim(), roleDesc: f.roleDesc.trim() }
    return id ? updateRole(id, payload) : createRole(payload)
  },
  remove: (r) => deleteRole(r.id),
  removeConfirm: (r) => `确定删除角色「${r.roleName}」(${r.roleCode}) 吗？`
})

// 页面特有：分配菜单权限弹窗（含 MenuTreeCheckbox 勾选逻辑，不并入骨架）
const showAssign = ref(false)
const savingAssign = ref(false)
const assignTarget = ref(null)
const menuTree = ref([])
const checkedMenuIds = ref([])

async function openAssign(r) {
  assignTarget.value = r
  savingAssign.value = true
  try {
    menuTree.value = await allMenus()
    checkedMenuIds.value = await getRoleMenus(r.id)
    showAssign.value = true
  } catch (e) {
    toast.error('加载菜单失败：' + e.message)
  } finally {
    savingAssign.value = false
  }
}

async function saveAssign() {
  savingAssign.value = true
  try {
    await assignRoleMenus(assignTarget.value.id, checkedMenuIds.value)
    showAssign.value = false
    toast.success('权限已保存')
  } catch (e) {
    toast.error('保存失败：' + e.message)
  } finally {
    savingAssign.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.primary-btn {
  display: inline-flex; align-items: center; gap: 6px; padding: 9px 16px;
  background: var(--primary); color: white; border: none; border-radius: var(--border-radius-sm);
  font-size: 14px; cursor: pointer; transition: var(--transition);
}

.code-cell { background: var(--bg-input); padding: 2px 6px; border-radius: 4px; font-size: 12px; color: var(--text-secondary); }
.desc-cell { color: var(--text-secondary); max-width: 320px; }

.modal-lg { width: 640px; }

.form-input, .form-textarea {
  width: 100%; padding: 9px 12px; border: 1px solid var(--border-color);
  border-radius: var(--border-radius-sm); background: var(--bg-input);
  color: var(--text-primary); font-size: 14px; outline: none; font-family: inherit;
}
.form-input:focus, .form-textarea:focus { border-color: var(--primary); }
.form-textarea { resize: vertical; line-height: 1.6; }
.form-input:disabled { background: var(--bg-hover); color: var(--text-tertiary); cursor: not-allowed; }
.form-hint { font-size: 12px; color: var(--text-tertiary); }

.tree-panel { max-height: 50vh; overflow-y: auto; border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); padding: 12px; background: var(--bg-input); }
</style>
