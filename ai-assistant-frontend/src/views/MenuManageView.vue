<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">菜单管理</h2>
        <p class="page-sub">维护系统菜单与按钮权限目录，供角色分配使用。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreateRoot">新增顶级菜单</el-button>
    </div>

    <div class="table-card" v-loading="loading">
      <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon style="margin: 12px 12px 0">
        <el-button link type="primary" @click="retry">重试</el-button>
      </el-alert>
      <el-table
        v-if="tree.length > 0"
        :data="tree"
        row-key="id"
        default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column label="菜单名称">
          <template #default="{ row }">
            <span class="menu-name">{{ row.menuName }}</span>
            <el-tag v-if="row.type === 2" class="type-tag btn-tag" size="small" effect="light">按钮</el-tag>
            <el-tag v-else class="type-tag menu-tag" size="small" effect="light">菜单</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="权限标识">
          <template #default="{ row }">{{ row.perms || '—' }}</template>
        </el-table-column>
        <el-table-column label="路由">
          <template #default="{ row }">{{ row.path || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCreateChild(row)">加子项</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="removeMenu(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loading && !error" description="暂无菜单数据" :image-size="96" />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="showModal"
      :title="isEdit ? '编辑菜单' : (form.parentId === 0 ? '新增顶级菜单' : '新增子菜单')"
      width="540px"
    >
      <el-form label-position="top">
        <el-form-item label="上级菜单" required>
          <el-select v-model="form.parentId" :disabled="isEdit" style="width: 100%">
            <el-option label="顶级菜单" :value="0" />
            <el-option v-for="opt in parentOptions" :key="opt.id" :label="opt.label" :value="opt.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="菜单名称" required>
          <el-input v-model="form.menuName" placeholder="如 用户管理" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option label="菜单" :value="1" />
            <el-option label="按钮" :value="2" />
          </el-select>
        </el-form-item>
        <div class="two-col">
          <el-form-item label="路由 path">
            <el-input v-model="form.path" placeholder="菜单填路由，按钮留空" />
          </el-form-item>
          <el-form-item label="组件 component">
            <el-input v-model="form.component" placeholder="如 system/user/index" />
          </el-form-item>
        </div>
        <div class="two-col">
          <el-form-item label="权限标识 perms">
            <el-input v-model="form.perms" placeholder="如 system:user:add" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="form.sort" :min="0" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="showModal = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { allMenus, createMenu, updateMenu, deleteMenu } from '../api/sysmenu'
import { useCrudPage } from '../composables/useCrudPage'

const {
  list: tree, showModal: showModalRef, isEdit, saving, form,
  loading, error,
  loadData, retry,
  openCreate: openCreateBase, openEdit: openEditBase, save,
  remove: removeMenu
} = useCrudPage({
  // 树形页面无分页/keyword：fetchPage 仅透出 allMenus 结果
  fetchPage: async () => {
    const data = await allMenus()
    return { list: data || [], total: (data || []).length }
  },
  emptyForm: () => ({
    parentId: 0, menuName: '', path: '', component: '', perms: '', type: 1, sort: 1
  }),
  toForm: (node) => ({
    parentId: node.parentId || 0,
    menuName: node.menuName,
    path: node.path || '',
    component: node.component || '',
    perms: node.perms || '',
    type: node.type || 1,
    sort: node.sort || 1
  }),
  validate: (f) => (f.menuName.trim() ? null : '请填写菜单名称'),
  persist: (f, id) => {
    const payload = {
      parentId: f.parentId || 0,
      menuName: f.menuName.trim(),
      path: f.path.trim(),
      component: f.component.trim(),
      perms: f.perms.trim(),
      type: f.type,
      sort: f.sort
    }
    return id ? updateMenu(id, payload) : createMenu(payload)
  },
  remove: (node) => deleteMenu(node.id),
  removeConfirm: (node) => `确定删除菜单「${node.menuName}」吗？其下子节点将一并删除。`
})

// 薄包装（同名桥接）：模板弹窗关闭按钮直接写 `showModal = false`，
// 而骨架返回的 showModal 为解构 ref，用 computed get/set 桥接，保证模板读写与骨架状态完全联动（原效果不变）
const showModal = computed({
  get: () => showModalRef.value,
  set: (v) => { showModalRef.value = v }
})

// 上级菜单下拉（编辑时排除自身及子孙）
const parentOptions = ref([])

function flatten(nodes, depth, acc) {
  nodes.forEach((n) => {
    acc.push({ id: n.id, label: '　'.repeat(depth) + (n.type === 2 ? '↳ ' : '') + n.menuName })
    if (n.children) flatten(n.children, depth + 1, acc)
  })
  return acc
}

// 收集某节点及其所有子孙 ID
function collectIds(node, acc) {
  acc.push(node.id)
  if (node.children) node.children.forEach((c) => collectIds(c, acc))
  return acc
}

// 薄包装（骨架 openCreate/openEdit 之外的本页特有逻辑）：
// 打开弹窗前构建上级菜单下拉；编辑时排除自身及子孙避免循环引用
function openCreateRoot() {
  parentOptions.value = flatten(tree.value, 0, [])
  openCreateBase()
}
function openCreateChild(node) {
  parentOptions.value = flatten(tree.value, 0, [])
  openCreateBase()
  form.value.parentId = node.id
}
function openEdit(node) {
  const exclude = new Set(collectIds(node, []))
  parentOptions.value = flatten(tree.value, 0, []).filter((o) => !exclude.has(o.id))
  openEditBase(node)
}

onMounted(loadData)
</script>

<style scoped>
.type-tag { margin-left: 8px; }
.type-tag.menu-tag { color: var(--primary); background: rgba(20, 184, 166, 0.1); border-color: transparent; }
.type-tag.btn-tag { color: #d97706; background: rgba(217, 119, 6, 0.1); border-color: transparent; }
.two-col { display: flex; gap: 16px; }
.two-col > .el-form-item { flex: 1; }
</style>
