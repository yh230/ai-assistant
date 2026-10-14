"""helpers/sse.py 纯逻辑单测（不依赖任何服务）"""

from helpers.sse import consume, error_message, full_text, parse_sse


def test_parses_named_events_json_and_text_data():
    lines = [
        "event:session", 'data:{"sessionId":"s1"}', "",
        "event:token", "data:头痛", "",
        "event:done", 'data:{"localFallback":false}', "",
    ]
    assert parse_sse(iter(lines)) == [
        ("session", {"sessionId": "s1"}),
        ("token", "头痛"),
        ("done", {"localFallback": False}),
    ]


def test_numeric_like_token_kept_as_string():
    # token 事件载荷是纯文本：形如 "123" 不能被 JSON 解码成数字
    assert parse_sse(iter(["event:token", "data:123", ""])) == [("token", "123")]


def test_multiline_data_joined_by_newline():
    assert parse_sse(iter(["data:line1", "data:line2", ""])) == [("message", "line1\nline2")]


def test_until_stops_reading_further_lines():
    read_after_stop: list[str] = []

    def gen():
        yield "event:confirm_required"
        yield 'data:{"actionId":"a1","summary":"摘要"}'
        yield ""
        read_after_stop.append("parser requested more")
        yield "event:token"
        yield "data:不应被读取"
        yield ""

    assert parse_sse(gen(), until={"confirm_required"}) == [
        ("confirm_required", {"actionId": "a1", "summary": "摘要"})
    ]
    assert read_after_stop == [], "until 命中后不应继续读取后续行"


def test_flushes_trailing_event_without_blank_line():
    assert parse_sse(iter(["event:done", "data:{}"])) == [("done", {})]


def test_full_text_joins_token_events_only():
    events = [("session", {"sessionId": "s"}), ("token", "你好"), ("token", "世界"), ("done", {})]
    assert full_text(events) == "你好世界"


def test_error_message_extracts_first_message():
    events = [("error", {"message": "该操作已处理"}), ("error", {"message": "第二条"})]
    assert error_message(events) == "该操作已处理"
    assert error_message([("token", "x")]) is None


def test_data_field_strips_at_most_one_leading_space():
    # 与前端 slice(5).replace(/^\s/, '') 一致：只剥一个分隔空格，载荷自身空白保留
    assert parse_sse(iter(["event:token", "data: hello", ""])) == [("token", "hello")]
    assert parse_sse(iter(["event:token", "data:  world", ""])) == [("token", " world")]


def test_json_looking_token_kept_as_raw_text():
    # token 事件为纯文本：形如 JSON 也不能被解码（与 wire 协议一致）
    assert parse_sse(iter(["event:token", "data:{}", ""])) == [("token", "{}")]


class _FakeStream:
    def __init__(self, lines):
        self._lines = lines

    def iter_lines(self):
        return iter(self._lines)


def test_consume_reads_iter_lines_stream():
    stream = _FakeStream(["event:done", "data:{}", ""])
    assert consume(stream) == [("done", {})]
