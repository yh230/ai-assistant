import { marked } from 'marked'

// 与三个聊天视图原行为保持一致：单换行渲染为 <br>
marked.setOptions({ breaks: true })

/**
 * 安全 Markdown 渲染：先转义 HTML 元字符再解析，剥离 javascript: 协议链接
 *
 * @param {string} text 原始文本
 * @returns {string} 可安全用于 v-html 的 HTML
 */
export function renderMarkdownSafe(text) {
  if (!text) return ''
  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
  const html = marked.parse(escaped)
  return html.replace(/(href|src)\s*=\s*(["'])\s*javascript:[^"']*\2/gi, '$1="#"')
}
