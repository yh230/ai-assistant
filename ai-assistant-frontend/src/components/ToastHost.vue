<template>
  <!-- 居中模态对话框（confirm 类型） -->
  <transition name="modal">
    <div v-if="confirmItem" class="modal-mask" @click.self="onCancel">
      <div class="modal-card">
        <div class="modal-body">
          <div class="modal-title">{{ confirmItem.title || '提示' }}</div>
          <div class="modal-content">{{ confirmItem.text }}</div>
        </div>
        <div class="modal-footer">
          <button class="modal-btn ghost" @click="onCancel">
            {{ confirmItem.cancelText }}
          </button>
          <button class="modal-btn primary" @click="onOk" autofocus>
            {{ confirmItem.okText }}
          </button>
        </div>
      </div>
    </div>
  </transition>

  <!-- 顶部轻量 toast 队列 -->
  <div class="toast-host">
    <transition-group name="toast" tag="div" class="toast-stack">
      <div
        v-for="item in toastItems"
        :key="item.id"
        class="toast-item"
        :class="['toast-' + item.type]"
      >
        <span class="toast-dot" :class="dotClass(item.type)"></span>
        <div class="toast-text">{{ item.text }}</div>
      </div>
    </transition-group>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { toastQueue } from '../utils/message'

const confirmItem = computed(() => toastQueue.value.find((x) => x.type === 'confirm') || null)
const toastItems = computed(() => toastQueue.value.filter((x) => x.type !== 'confirm'))

function dotClass(type) {
  return 'dot-' + type
}

function onOk() {
  const item = confirmItem.value
  if (!item) return
  item.resolve(true)
  remove(item)
}
function onCancel() {
  const item = confirmItem.value
  if (!item) return
  item.resolve(false)
  remove(item)
}
function remove(item) {
  const i = toastQueue.value.findIndex((x) => x.id === item.id)
  if (i >= 0) toastQueue.value.splice(i, 1)
}
</script>

<style scoped>
.toast-host {
  position: fixed;
  top: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 9999;
  pointer-events: none;
}
.toast-stack { display: flex; flex-direction: column; gap: 10px; align-items: center; }
.toast-item {
  pointer-events: auto;
  min-width: 220px;
  max-width: 460px;
  padding: 10px 18px;
  border-radius: 10px;
  background: var(--bg-card);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  box-shadow: var(--shadow-lg);
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.toast-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--primary);
  flex-shrink: 0;
}
.dot-success { background: #22c55e; }
.dot-error { background: var(--danger); }
.dot-warn { background: #f59e0b; }
.toast-text { flex: 1; word-break: break-all; }

.toast-enter-from { opacity: 0; transform: translateY(-12px); }
.toast-leave-to { opacity: 0; transform: translateY(-8px); }
.toast-enter-active, .toast-leave-active { transition: all .22s ease; }

/* ---------- 模态对话框 ---------- */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(2px);
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.modal-card {
  width: 380px;
  max-width: 92vw;
  background: var(--bg-card);
  border-radius: var(--border-radius);
  box-shadow: var(--shadow-xl);
  border: 1px solid var(--border-color);
  overflow: hidden;
}
.modal-body { padding: 22px 24px 8px; }
.modal-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 10px;
}
.modal-content {
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 24px 20px;
}
.modal-btn {
  padding: 7px 18px;
  border-radius: var(--border-radius-sm);
  font-size: 13px;
  cursor: pointer;
  border: 1px solid var(--border-color);
  background: transparent;
  color: var(--text-secondary);
}
.modal-btn.ghost:hover { background: var(--bg-hover); }
.modal-btn.primary {
  background: var(--primary);
  color: white;
  border-color: var(--primary);
}
.modal-btn.primary:hover { background: var(--primary-dark); }

.modal-enter-from, .modal-leave-to {
  opacity: 0;
}
.modal-enter-from .modal-card,
.modal-leave-to .modal-card {
  transform: scale(0.92);
}
.modal-enter-active, .modal-leave-active {
  transition: opacity .18s ease;
}
.modal-enter-active .modal-card,
.modal-leave-active .modal-card {
  transition: transform .18s ease;
}
</style>