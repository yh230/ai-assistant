import test from 'node:test'
import assert from 'node:assert/strict'
import { parseSseEvents } from './sseParse.js'

test('parses event and multiline data', () => {
  const { events, rest } = parseSseEvents('event: token\ndata: hello\ndata: world\n\npartial')
  assert.deepEqual(events, [{ event: 'token', data: 'hello\nworld' }])
  assert.equal(rest, 'partial')
})

test('ignores comments and handles CRLF', () => {
  const { events } = parseSseEvents(': ping\r\nevent: done\r\ndata: {}\r\n\r\n')
  assert.deepEqual(events, [{ event: 'done', data: '{}' }])
})

test('defaults event to message and preserves data leading spaces', () => {
  const { events } = parseSseEvents('data:  indented\n\n')
  assert.deepEqual(events, [{ event: 'message', data: ' indented' }])
})

test('parses multiple events and keeps the incomplete tail in rest', () => {
  const { events, rest } = parseSseEvents(
    'event: session\ndata: {"sessionId":1}\n\n' +
    'event: token\ndata: 你好\n\n' +
    'event: token\ndata: 世界'
  )
  assert.deepEqual(events, [
    { event: 'session', data: '{"sessionId":1}' },
    { event: 'token', data: '你好' }
  ])
  assert.equal(rest, 'event: token\ndata: 世界')
})

test('flush mode emits an unterminated final event', () => {
  const raw = 'event: token\ndata: hi\n\nevent: done\ndata: {"localFallback":false}'
  const pending = parseSseEvents(raw)
  assert.deepEqual(pending.events, [{ event: 'token', data: 'hi' }])
  assert.equal(pending.rest, 'event: done\ndata: {"localFallback":false}')

  const flushed = parseSseEvents(raw, { flush: true })
  assert.deepEqual(flushed.events, [
    { event: 'token', data: 'hi' },
    { event: 'done', data: '{"localFallback":false}' }
  ])
  assert.equal(flushed.rest, '')
})

test('flush ignores whitespace-only remainder and keeps empty input empty', () => {
  assert.deepEqual(parseSseEvents('event: token\ndata: ok\n\n', { flush: true }), {
    events: [{ event: 'token', data: 'ok' }],
    rest: ''
  })
  assert.deepEqual(parseSseEvents('', { flush: true }), { events: [], rest: '' })
  assert.deepEqual(parseSseEvents('\n', { flush: true }), { events: [], rest: '' })
})

test('returns empty events for empty or incomplete buffer', () => {
  assert.deepEqual(parseSseEvents(''), { events: [], rest: '' })
  assert.deepEqual(parseSseEvents('event: token\ndata: par'), {
    events: [],
    rest: 'event: token\ndata: par'
  })
})
