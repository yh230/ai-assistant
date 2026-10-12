<template>
  <ul class="menu-tree">
    <li v-for="node in nodes" :key="node.id" class="tree-node">
      <label class="tree-label">
        <input
          type="checkbox"
          :checked="modelValue.includes(node.id)"
          @change="toggle(node, $event.target.checked)"
        />
        <span class="node-name">{{ node.menuName }}</span>
        <em class="node-meta">{{ node.type === 2 ? '按钮' : '菜单' }}<template v-if="node.perms"> · {{ node.perms }}</template></em>
      </label>
      <MenuTreeCheckbox
        v-if="node.children && node.children.length"
        :nodes="node.children"
        :model-value="modelValue"
        @update:model-value="emitChange"
      />
    </li>
  </ul>
</template>

<script setup>
const props = defineProps({
  nodes: { type: Array, default: () => [] },
  modelValue: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

// 收集某节点下所有子孙 ID（含自身）
function collectDescendants(node, acc) {
  acc.push(node.id)
  if (node.children) {
    node.children.forEach((c) => collectDescendants(c, acc))
  }
  return acc
}

function toggle(node, checked) {
  const set = new Set(props.modelValue)
  const ids = collectDescendants(node, [])
  ids.forEach((id) => (checked ? set.add(id) : set.delete(id)))
  emit('update:modelValue', [...set])
}

function emitChange(val) {
  emit('update:modelValue', val)
}
</script>

<style scoped>
.menu-tree {
  list-style: none;
  padding-left: 18px;
  margin: 0;
}
.menu-tree :deep(.menu-tree) {
  padding-left: 18px;
}
.tree-node {
  margin: 4px 0;
}
.tree-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--text-primary);
  cursor: pointer;
}
.node-meta {
  font-size: 11px;
  font-style: normal;
  color: var(--text-tertiary);
}
</style>
