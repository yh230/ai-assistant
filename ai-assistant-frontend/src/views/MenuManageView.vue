<template>
  <div class="manage-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">菜单管理</h2>
        <p class="page-sub">维护系统菜单与按钮权限目录，供角色分配使用。</p>
      </div>
      <button class="primary-btn" @click="openCreateRoot">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        新增顶级菜单
      </button>
    </div>

    <div class="tree-card">
      <div v-if="tree.length === 0" class="empty-tip">暂无菜单数据</div>
      <MenuTreeNode
        v-for="node in tree"
        :key="node.id"
        :node="node"
        :depth="0"
        @add-child="openCreateChild"
        @edit="openEdit"
        @remove="removeMenu"
      />
    </div>

    <!-- 新增/编辑弹窗 -->
    <div class="modal-mask" v-if="showModal" @click.self="showModal = false">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ isEdit ? '编辑菜单' : (form.parentId === 0 ? '新增顶级菜单' : '新增子菜单') }}</h3>
          <button class="modal-close" @click="showModal = false">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label>上级菜单</label>
            <select v-model.number="form.parentId" class="form-input" :disabled="isEdit">
              <option :value="0">顶级菜单</option>
              <option v-for="opt in parentOptions" :key="opt.id" :value="opt.id">{{ opt.label }}</option>
            </select>
          </div>
          <div class="form-row">
            <label>菜单名称 <span class="req">*</span></label>
            <input v-model="form.menuName" class="form-input" placeholder="如 用户管理" />
          </div>
          <div class="form-row">
            <label>类型</label>
            <select v-model.number="form.type" class="form-input">
              <option :value="1">菜单</option>
              <option :value="2">按钮</option>
            </select>
          </div>
          <div class="form-row two-col">
            <div>
              <label>路由 path</label>
              <input v-model="form.path" class="form-input" placeholder="菜单填路由，按钮留空" />
            </div>
            <div>
              <label>组件 component</label>
              <input v-model="form.component" class="form-input" placeholder="如 system/user/index" />
            </div>
          </div>
          <div class="form-row two-col">
            <div>
              <label>权限标识 perms</label>
              <input v-model="form.perms" class="form-input" placeholder="如 system:user:add" />
            </div>
            <div>
              <label>排序</label>
              <input v-model.number="form.sort" type="number" class="form-input" placeholder="数字越小越靠前" />
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="ghost-btn" @click="showModal = false">取消</button>
          <button class="primary-btn" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { allMenus, createMenu, updateMenu, deleteMenu } from '../api/sysmenu'
import MenuTreeNode from '../components/MenuTreeNode.vue'
import { useCrudPage } from '../composables/useCrudPage'

const {
  list: tree, showModal: showModalRef, isEdit, saving, form,
  loadData,
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
.primary-btn {
  display: inline-flex; align-items: center; gap: 6px; padding: 9px 16px;
  background: var(--primary); color: white; border: none; border-radius: var(--border-radius-sm);
  font-size: 14px; cursor: pointer; transition: var(--transition);
}

.tree-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--border-radius); overflow: hidden; }

.modal { width: 540px; max-width: 92vw; max-height: 90vh; background: var(--bg-card); border-radius: var(--border-radius); display: flex; flex-direction: column; box-shadow: var(--shadow-xl); overflow: hidden; }

.form-input:disabled { background: var(--bg-hover); color: var(--text-tertiary); cursor: not-allowed; }
.two-col { display: flex; gap: 16px; }
.two-col > div { flex: 1; }
</style>
