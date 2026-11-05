import test from 'node:test'
import assert from 'node:assert/strict'
import { renderMarkdownSafe } from './safeMarkdown.js'

test('strips javascript and data protocols from links', () => {
  for (const url of ['javascript:alert(1)', 'data:text/html;base64,PHNjcmlwdD4=', 'vbscript:msgbox(1)']) {
    const html = renderMarkdownSafe(`[x](${url})`)
    assert.ok(!/javascript:|data:|vbscript:/i.test(html), html)
    assert.ok(html.includes('href="#"'), html)
  }
})

test('keeps http, mailto, tel and relative links', () => {
  const html = renderMarkdownSafe('[a](https://example.com) [b](mailto:a@b.com) [c](/patient) [d](#top)')
  assert.ok(html.includes('href="https://example.com"'), html)
  assert.ok(html.includes('href="mailto:a@b.com"'), html)
  assert.ok(html.includes('href="/patient"'), html)
  assert.ok(html.includes('href="#top"'), html)
})

test('escapes raw HTML and sanitizes image sources', () => {
  const html = renderMarkdownSafe('<script>alert(1)</script> ![x](javascript:alert(1))')
  assert.ok(!html.includes('<script>'), html)
  assert.ok(!/javascript:/i.test(html), html)
  assert.ok(html.includes('src=""'), html)
})

test('adds noopener to anchors', () => {
  const html = renderMarkdownSafe('[a](https://example.com)')
  assert.ok(html.includes('rel="noopener noreferrer"'), html)
})
