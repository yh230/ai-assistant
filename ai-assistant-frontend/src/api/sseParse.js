/**
 * 解析 SSE 文本缓冲，返回完整事件与未闭合的剩余文本。
 * 支持 `event:` / 多行 `data:` / 注释行 / CRLF。
 *
 * @param {string} buffer
 * @param {{ flush?: boolean }} [options] flush=true 时把残留的无空行结尾片段也当作一个事件解析
 *        （用于流结束时冲刷最后一个事件）。
 */
export function parseSseEvents(buffer, { flush = false } = {}) {
  const events = []
  const parts = buffer.split(/\r?\n\r?\n/)
  let rest = parts.pop() ?? ''
  if (flush) {
    if (rest.trim()) parts.push(rest)
    rest = ''
  }
  for (const part of parts) {
    if (!part.trim()) continue
    let event = 'message'
    let hasField = false
    const data = []
    for (const line of part.split(/\r?\n/)) {
      if (line.startsWith(':')) continue
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
        hasField = true
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).replace(/^\s/, ''))
        hasField = true
      }
    }
    if (!hasField) continue
    events.push({ event, data: data.join('\n') })
  }
  return { events, rest }
}
