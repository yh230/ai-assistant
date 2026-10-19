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

test('returns empty events for empty or incomplete buffer', () => {
  assert.deepEqual(parseSseEvents(''), { events: [], rest: '' })
  assert.deepEqual(parseSseEvents('event: token\ndata: par'), {
    events: [],
    rest: 'event: token\ndata: par'
  })
})
