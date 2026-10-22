<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">角色权限</h2>
        <p class="page-sub">维护系统角色，并为角色分配菜单 / 按钮权限。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增角色</el-button>
    </div>

    <div class="toolbar">
      <el-input v-model="keyword" placeholder="搜索角色编码 / 名称" style="width: 240px" @keyup.enter="reload" />
      <el-button @click="reload">查询</el-button>
    </div>

    <div class="table-card" v-loading="loading">
      <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon style="margin: 12px 12px 0">
        <el-button link type="primary" @click="retry">重试</el-button>
      </el-alert>
      <el-table v-if="list.length > 0" :data="list" stripe>
        <el-table-column label="ID" width="70">
          <template #default="{ row }"><code class="id-cell">{{ row.id }}</code></template>
        </el-table-column>
        <el-table-column label="角色编码" width="140">
          <template #default="{ row }"><code class="code-cell">{{ row.roleCode }}</code></template>
        </el-table-column>
        <el-table-column prop="roleName" label="角色名称" />
        <el-table-column label="描述">
          <template #default="{ row }"><span class="desc-cell">{{ row.roleDesc || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }"><span class="time-cell">{{ formatTime(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAssign(row)">分配权限</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="removeRole(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loading && !error" description="暂无角色数据" :image-size="96" />
    </div>

    <div class="pager" v-if="total > 0">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :current-page="pageNum"
        :page-size="pageSize"
        @current-change="changePage"
      />
    </div>

    <!-- 新增/编辑角色 -->
    <el-dialog v-model="showModal" :title="isEdit ? '编辑角色' : '新增角色'" width="520px" @close="closeModal">
      <el-form label-position="top">
        <el-form-item label="角色编码" required>
          <el-input v-model="form.roleCode" :disabled="isEdit" placeholder="如 admin（字母数字下划线）" />
          <span class="form-hint" v-if="isEdit">编辑时不可修改</span>
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="form.roleName" placeholder="如 超级管理员" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.roleDesc" type="textarea" :rows="3" placeholder="角色用途说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeModal">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</el-button>
      </template>
    </el-dialog>

    <!-- 分配菜单权限 -->
    <el-dialog v-model="showAssign" :title="'分配权限 - ' + (assignTarget?.roleName || '')" width="640px">
      <div class="tree-panel">
        <el-tree
          ref="treeRef"
          :data="menuTree"
          node-key="id"
          show-checkbox
          check-strictly
          default-expand-all
          :props="{ label: 'menuName', children: 'children' }"
          @check="onTreeCheck"
        >
          <template #default="{ data }">
            <span class="node-name">{{ data.menuName }}</span>
            <em class="node-meta">{{ data.type === 2 ? '按钮' : '菜单' }}<template v-if="data.perms"> · {{ data.perms }}</template></em>
          </template>
        </el-tree>
      </div>
      <template #footer>
        <el-button @click="showAssign = false">取消</el-button>
        <el-button type="primary" :loading="savingAssign" @click="saveAssign">
          {{ savingAssign ? '保存中…' : '保存权限' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { pageRoles, createRole, updateRole, deleteRole, assignRoleMenus, getRoleMenus } from '../api/sysrole'
import { allMenus } from '../api/sysmenu'
import { useCrudPage } from '../composables/useCrudPage'
import { formatTime } from '../utils/format'
import { toast } from '../utils/message'

const {
  list, total, pageNum, pageSize, keyword, totalPages,
  loading, error,
  showModal, isEdit, saving, form,
  loadData, retry, reload, changePage, openCreate, openEdit, closeModal, save,
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

// 页面特有：分配菜单权限弹窗（el-tree 级联勾选逻辑，不并入骨架）
const showAssign = ref(false)
const savingAssign = ref(false)
const assignTarget = ref(null)
const menuTree = ref([])
const checkedMenuIds = ref([])
const treeRef = ref(null)

// 收集某节点下所有子孙 ID（含自身）——与原自研树语义一致
function collectDescendants(node, acc = []) {
  acc.push(node.id)
  if (node.children) node.children.forEach((c) => collectDescendants(c, acc))
  return acc
}

// check-strictly 独立勾选 + 事件内级联：勾/取消 = 增删「自身+全部子孙」；子不反哺父，无半选
function onTreeCheck(data, { checkedKeys }) {
  const checked = checkedKeys.includes(data.id)
  const set = new Set(checkedKeys)
  collectDescendants(data).forEach((id) => (checked ? set.add(id) : set.delete(id)))
  checkedMenuIds.value = [...set]
  treeRef.value?.setCheckedKeys(checkedMenuIds.value)
}

async function openAssign(r) {
  savingAssign.value = true
  try {
    menuTree.value = await allMenus()
    checkedMenuIds.value = await getRoleMenus(r.id)
    assignTarget.value = r
    showAssign.value = true
    await nextTick()
    treeRef.value?.setCheckedKeys(checkedMenuIds.value)
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
.code-cell { background: var(--bg-input); padding: 2px 6px; border-radius: 4px; font-size: 12px; color: var(--text-secondary); }
.desc-cell { color: var(--text-secondary); }

.form-hint { font-size: 12px; color: var(--text-tertiary); }

.tree-panel { max-height: 50vh; overflow-y: auto; border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); padding: 12px; background: var(--bg-input); }
.node-name { font-size: 14px; color: var(--text-primary); }
.node-meta { margin-left: 8px; font-size: 12px; color: var(--text-tertiary); font-style: normal; }
</style>
