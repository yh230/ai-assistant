import { marked } from 'marked'

// 与三个聊天视图原行为保持一致：单换行渲染为 <br>
marked.setOptions({ breaks: true })

const SCHEME_RE = /^[a-z][a-z0-9+.-]*:/i
const SAFE_SCHEME_RE = /^(https?:|mailto:|tel:)/i

/**
 * 只放行 http/https/mailto/tel 与相对地址，其余协议（javascript/data/vbscript 等）一律置空
 *
 * @param {string} url 原始链接
 * @returns {string} 安全链接；不安全时返回空串
 */
function sanitizeUrl(url) {
  const value = (url || '').trim()
  if (!value) return ''
  if (SAFE_SCHEME_RE.test(value)) return value
  if (SCHEME_RE.test(value)) return ''
  return value
}

/**
 * 安全 Markdown 渲染：先转义 HTML 元字符再解析，按协议白名单清洗链接与图片地址
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
  let html = marked.parse(escaped)
  html = html.replace(/\s(href|src)\s*=\s*(["'])(.*?)\2/gi, (match, attr, quote, url) => {
    const safe = sanitizeUrl(url)
    if (safe) return ` ${attr}=${quote}${safe}${quote}`
    return attr === 'src' ? ' src=""' : ' href="#"'
  })
  // 新窗口外链禁止 opener 回写
  return html.replace(/<a\s/gi, '<a rel="noopener noreferrer" ')
}
