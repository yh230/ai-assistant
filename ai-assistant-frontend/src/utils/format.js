/**
 * 格式化时间为 yyyy-MM-dd HH:mm（空值返回占位符）
 * @param {string} value ISO 时间字符串
 * @returns {string}
 */
export function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}
