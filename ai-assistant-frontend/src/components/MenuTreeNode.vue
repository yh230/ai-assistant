<template>
  <div class="menu-node">
    <div class="node-row" :style="{ paddingLeft: depth * 20 + 'px' }">
      <span class="node-name">{{ node.menuName }}</span>
      <span class="type-tag" :class="node.type === 2 ? 'btn' : 'menu'">
        {{ node.type === 2 ? '按钮' : '菜单' }}
      </span>
      <span class="node-perms" v-if="node.perms">{{ node.perms }}</span>
      <span class="node-extra" v-if="node.path">path: {{ node.path }}</span>
      <span class="node-actions">
        <button class="mini-btn" @click="$emit('add-child', node)">加子项</button>
        <button class="mini-btn" @click="$emit('edit', node)">编辑</button>
        <button class="mini-btn danger" @click="$emit('remove', node)">删除</button>
      </span>
    </div>
    <div v-if="node.children && node.children.length">
      <MenuTreeNode
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
        @add-child="$emit('add-child', $event)"
        @edit="$emit('edit', $event)"
        @remove="$emit('remove', $event)"
      />
    </div>
  </div>
</template>

<script setup>
defineProps({
  node: { type: Object, required: true },
  depth: { type: Number, default: 0 }
})
defineEmits(['add-child', 'edit', 'remove'])
</script>

<style scoped>
.node-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--border-color);
  font-size: 14px;
}
.node-row:hover { background: var(--bg-hover); }
.node-name { font-weight: 600; color: var(--text-primary); }
.type-tag { font-size: 11px; padding: 1px 8px; border-radius: 10px; }
.type-tag.menu { background: rgba(99,102,241,0.15); color: var(--primary); }
.type-tag.btn { background: rgba(245,158,11,0.15); color: var(--warning); }
.node-perms { font-size: 12px; color: var(--text-tertiary); }
.node-extra { font-size: 12px; color: var(--text-tertiary); }
.node-actions { margin-left: auto; display: flex; gap: 6px; }
.mini-btn {
  padding: 3px 10px; font-size: 12px; border: 1px solid var(--border-color);
  background: var(--bg-card); color: var(--text-secondary); border-radius: var(--border-radius-sm); cursor: pointer;
}
.mini-btn:hover { border-color: var(--primary); color: var(--primary); }
.mini-btn.danger:hover { border-color: var(--danger); color: var(--danger); }
</style>
