"""MCP 排班查询 SSE 端到端测试。

覆盖：SSE 建流、initialize、tools/list、业务错误 isError、无效 Key 401。
需要完整栈、Nacos mcp-shared.yaml 和 MCP_E2E_API_KEY；未配置 Key 时跳过。
"""

from __future__ import annotations

import json
import os
from contextlib import contextmanager
from typing import Iterator
from urllib.parse import urljoin

import httpx
import pytest
from dotenv import load_dotenv

load_dotenv()

BASE = (
    os.environ.get("MCP_E2E_BASE_URL")
    or os.environ.get("E2E_BASE_URL")
    or "http://localhost:8080"
).rstrip("/")
API_KEY = os.environ.get("MCP_E2E_API_KEY", "")
HOSPITAL_ID = os.environ.get("MCP_E2E_HOSPITAL_ID", "")

pytestmark = [
    pytest.mark.mcp,
    pytest.mark.skipif(not API_KEY, reason="未配置 MCP_E2E_API_KEY，跳过 MCP E2E"),
]


def _headers(key: str = API_KEY) -> dict[str, str]:
    return {"X-API-Key": key, "Accept": "text/event-stream"}


def _next_event(lines: Iterator[str]) -> tuple[str, str]:
    event = None
    data: list[str] = []
    for line in lines:
        if line == "":
            if event is not None:
                return event, "\n".join(data)
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].strip())
    raise AssertionError("SSE 流在收到事件前关闭")


@contextmanager
def _mcp_session(base: str, key: str):
    timeout = httpx.Timeout(60.0, connect=5.0, read=60.0, write=10.0, pool=5.0)
    with httpx.Client(timeout=timeout) as client:
        with client.stream("GET", f"{base}/mcp/sse", headers=_headers(key)) as response:
            assert response.status_code == 200, response.text
            assert "text/event-stream" in response.headers.get("content-type", "")
            lines = response.iter_lines()
            event, data = _next_event(lines)
            assert event == "endpoint", (event, data)
            message_url = urljoin(base + "/", data)

            def send(payload: dict) -> None:
                result = client.post(message_url, headers=_headers(key), json=payload)
                assert result.status_code == 200, result.text

            def receive(request_id: int) -> dict:
                while True:
                    event_name, payload = _next_event(lines)
                    if event_name != "message":
                        continue
                    message = json.loads(payload)
                    if message.get("id") == request_id:
                        return message

            yield send, receive


def _initialize(send, receive) -> dict:
    send({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "ai-assistant-e2e", "version": "1.0"},
        },
    })
    message = receive(1)
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})
    return message


def _content_json(message: dict) -> object:
    return json.loads(message["result"]["content"][0]["text"])


def _first_public_schedule(base: str, allowed_ids: set[int]) -> dict | None:
    with httpx.Client(timeout=15.0) as client:
        response = client.get(f"{base}/api/registration/schedule/public/schedules")
    if response.status_code != 200:
        return None
    for schedule in response.json().get("data") or []:
        if schedule.get("scheduleDate") and schedule.get("hospitalId") in allowed_ids:
            return schedule
    return None


@pytest.mark.timeout(90)
def test_sse_handshake_tools_and_error_semantics():
    with _mcp_session(BASE, API_KEY) as (send, receive):
        init = _initialize(send, receive)
        assert init["result"]["serverInfo"]["name"] == "ai-hospital-schedule"

        send({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        tools = receive(2)["result"]["tools"]
        names = {tool["name"] for tool in tools}
        assert {"list_hospitals", "query_doctor_schedules"} <= names

        send({
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {"name": "list_hospitals", "arguments": {}},
        })
        listed = receive(3)
        assert listed["result"].get("isError") is not True
        hospitals = _content_json(listed)
        assert hospitals
        hospital_id = int(HOSPITAL_ID) if HOSPITAL_ID else hospitals[0]["hospitalId"]

        send({
            "jsonrpc": "2.0",
            "id": 4,
            "method": "tools/call",
            "params": {
                "name": "query_doctor_schedules",
                "arguments": {
                    "hospital_id": hospital_id,
                    "date_from": "2099-01-01",
                    "date_to": "2099-01-20",
                },
            },
        })
        invalid = receive(4)
        assert invalid["result"].get("isError") is True
        assert "14" in invalid["result"]["content"][0]["text"]


@pytest.mark.timeout(90)
def test_query_returns_seeded_schedule():
    with _mcp_session(BASE, API_KEY) as (send, receive):
        _initialize(send, receive)
        send({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
              "params": {"name": "list_hospitals", "arguments": {}}})
        hospitals = _content_json(receive(2))
        allowed_ids = {item["hospitalId"] for item in hospitals}
        if HOSPITAL_ID:
            allowed_ids &= {int(HOSPITAL_ID)}
        schedule = _first_public_schedule(BASE, allowed_ids)
        if schedule is None:
            pytest.skip("没有可用于 MCP 查询的已排班数据")

        send({
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {
                "name": "query_doctor_schedules",
                "arguments": {
                    "hospital_id": schedule["hospitalId"],
                    "date_from": schedule["scheduleDate"],
                    "date_to": schedule["scheduleDate"],
                    "department_id": schedule.get("departmentId"),
                },
            },
        })
        result = receive(3)
        assert result["result"].get("isError") is not True
        payload = _content_json(result)
        items = payload.get("items") or []
        assert any(
            item.get("doctorUserId") == schedule.get("doctorUserId")
            and item.get("scheduleDate") == schedule["scheduleDate"]
            for item in items
        ), payload


@pytest.mark.timeout(30)
def test_missing_and_invalid_key_rejected():
    with httpx.Client(timeout=10.0) as client:
        missing = client.get(f"{BASE}/mcp/sse")
        invalid = client.get(f"{BASE}/mcp/sse", headers={"X-API-Key": "wrong"})

    assert missing.status_code == 401
    assert missing.json()["error"] == "invalid_api_key"
    assert invalid.status_code == 401
    assert invalid.json()["error"] == "invalid_api_key"
