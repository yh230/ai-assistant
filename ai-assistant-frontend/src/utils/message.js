// 全局消息工具：
// 1. 提供 toast / 异步 confirm API（必须 await！）
// 2. 启动时拦截 window.alert；window.confirm 被替换为页面内弹窗，
//    业务代码须 await confirm(...)（window.confirm 调用改为抛错提示迁移）
import { ref } from 'vue'

const queue = ref([])
let nextId = 1

function push(text, type = 'info', duration = 2200) {
  const id = nextId++
  queue.value.push({ id, text, type })
  setTimeout(() => {
    const i = queue.value.findIndex((x) => x.id === id)
    if (i >= 0) queue.value.splice(i, 1)
  }, duration)
}

/**
 * 异步确认对话框，返回 Promise<boolean>。
 * 必须 await：const ok = await confirm('确定删除吗？')
 */
function confirmDialog(text, opts = {}) {
  return new Promise((resolve) => {
    const id = nextId++
    queue.value.push({
      id,
      text,
      type: 'confirm',
      okText: opts.okText || '确定',
      cancelText: opts.cancelText || '取消',
      resolve
    })
  })
}

export const toastQueue = queue
export function showToast(text, type = 'info', duration = 2200) {
  push(text, type, duration)
}
export const toast = {
  info: (t, d) => push(t, 'info', d),
  success: (t, d) => push(t, 'success', d),
  error: (t, d) => push(t, 'error', d),
  warn: (t, d) => push(t, 'warn', d)
}
/**
 * 便捷消息：按文案自动判别样式（与 window.alert 拦截一致）。
 * 成功类（含「成功/已」等）→ success；失败类（含「失败/错误」等）→ error；其余 → info。
 */
export function msg(text) {
  const t = text == null ? '' : String(text)
  if (/失败|错误|异常|denied|error/i.test(t)) toast.error(t)
  else if (/成功|已|完成/.test(t)) toast.success(t)
  else toast.info(t)
}
export { confirmDialog as confirm }

/* ---------------- 全局拦截 ---------------- */

if (typeof window !== 'undefined') {
  if (!window.__aiMessagePatched__) {
    window.__aiMessagePatched__ = true

    // alert 拦截：自动映射为 toast
    window.alert = (msg) => {
      const text = msg == null ? '' : String(msg)
      if (!text) return
      if (/失败|错误|异常|denied|error/i.test(text)) push(text, 'error', 2400)
      else if (/成功|已|完成/.test(text)) push(text, 'success', 2000)
      else push(text, 'info', 2000)
    }

    // window.confirm 不再保留原生弹窗：
    // 浏览器原生 confirm 是同步阻塞函数，无法被替换为异步 Promise 同时保持同步语义。
    // 这里采用"业务代码必须 await confirm(...)"的策略，window.confirm 抛错以提醒迁移。
    window.confirm = () => {
      // 抛出明确错误指引开发者改为 await confirm(...)
      throw new Error(
        '[message] 禁止使用 window.confirm，请改用: const ok = await confirm(...)'
      )
    }
  }
}