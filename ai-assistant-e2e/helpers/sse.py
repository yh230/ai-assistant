"""SSE 事件流解析：把 text/event-stream 行流还原为 (事件名, 数据) 列表"""

import json
from typing import Iterable


def parse_sse(lines: Iterable[str], until: set[str] | None = None) -> list[tuple[str, object]]:
    """解析 SSE 行流为事件列表。

    与前端解析约定一致：data: 后按 SSE 规范仅剥离一个前导空格（不吞内容自身空白）；
    token 事件为纯文本不做 JSON 解码，其余事件载荷以 { 或 [ 开头时尝试 JSON 解码，
    否则保留原文。遇到 until 中的事件名即停止读取（用于只关心到某事件为止的场景）。
    """
    events: list[tuple[str, object]] = []
    event_name = "message"
    data_lines: list[str] = []

    def flush() -> bool:
        nonlocal event_name, data_lines
        matched = False
        if data_lines:
            raw = "\n".join(data_lines)
            payload: object = raw
            if event_name != "token" and raw[:1] in "{[":
                try:
                    payload = json.loads(raw)
                except json.JSONDecodeError:
                    payload = raw
            events.append((event_name, payload))
            matched = until is not None and event_name in until
        event_name = "message"
        data_lines = []
        return matched

    for line in lines:
        line = line.rstrip("\r")
        if not line:
            if flush():
                break
        elif line.startswith("event:"):
            event_name = line[len("event:"):].strip()
        elif line.startswith("data:"):
            value = line[len("data:"):]
            data_lines.append(value[1:] if value.startswith(" ") else value)
        # id: / retry: / 注释行忽略

    if data_lines:  # 流以未闭合事件结尾时的兜底
        flush()
    return events


def consume(stream, until: set[str] | None = None) -> list[tuple[str, object]]:
    """消费 httpx 流式响应（iter_lines）到流结束或 until 命中。

    超时由 httpx 客户端读超时与 pytest-timeout 兜底控制。
    """
    return parse_sse(stream.iter_lines(), until=until)


def full_text(events: list[tuple[str, object]]) -> str:
    """拼接 token 事件为完整回答文本"""
    return "".join(p for name, p in events if name == "token" and isinstance(p, str))


def error_message(events: list[tuple[str, object]]) -> str | None:
    """取第一个 error 事件的 message 字段（无则 None）"""
    for name, payload in events:
        if name == "error" and isinstance(payload, dict):
            return str(payload.get("message", ""))
    return None
