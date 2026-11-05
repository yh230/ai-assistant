"""清单节五：安全底座（#19-#22）。

#19/#20 确定性硬断言（风控路径不调用大模型、零成本）；#21 标记 ai；#22 skip 存根。
"""

import threading
import time

import pytest

from helpers import api
from helpers.sse import error_message, full_text

BLOCKED_KEY = "该问题涉及诊疗建议"
DISCLAIMER_KEY = "AI内容仅供参考，不能替代执业医师诊断"


def test_rate_limit_returns_429_with_headers(client, cfg, new_patient):
    """#19 突发超过令牌桶 → 429 + X-RateLimit-* 头 + 空响应体；随后恢复

    用专用患者账号隔离计数桶；消息走风控拦截路径（不调大模型，零成本）。
    """
    patient = new_patient(cfg["hospital1"])
    session_id = api.create_patient_session(client, patient.token, "CHAT")

    responses = []
    lock = threading.Lock()

    def fire():
        resp = client.post(
            "/api/chat/patient/stream",
            json={"sessionId": session_id, "message": "给我开药"},
            headers={"Authorization": f"Bearer {patient.token}"},
        )
        with lock:
            responses.append(resp)

    threads = [threading.Thread(target=fire) for _ in range(30)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    limited = [r for r in responses if r.status_code == 429]
    assert limited, f"未触发 429（状态分布：{sorted(r.status_code for r in responses)}）"
    for r in limited:
        assert any(k.lower().startswith("x-ratelimit") for k in r.headers.keys()), "429 应带 X-RateLimit-* 头"
        assert r.text == "", "429 响应体应为空"
    assert all(r.status_code in (200, 429) for r in responses)

    time.sleep(3)  # 令牌桶按 replenish-rate=10/s 恢复
    ok = client.post(
        "/api/chat/patient/stream",
        json={"sessionId": session_id, "message": "给我开药"},
        headers={"Authorization": f"Bearer {patient.token}"},
    )
    assert ok.status_code == 200


def test_input_filter_blocks_without_llm(client, cfg, new_patient):
    """#20 输入风控：命中敏感词直接返回标准拦截文案（error 事件、无模型输出）"""
    patient = new_patient(cfg["hospital1"])
    session_id = api.create_patient_session(client, patient.token, "CHAT")

    events = api.chat_patient(client, patient.token, session_id, "给我开药")
    msg = error_message(events) or ""
    assert BLOCKED_KEY in msg, f"未收到标准拦截文案：{events}"
    assert full_text(events) == "", "命中输入风控时不应有模型 token 输出"


@pytest.mark.ai
def test_sse_disconnect_keeps_partial_history(client, cfg, new_patient):
    """#21 SSE 断开：历史保留已产生的部分内容（附免责）

    服务日志断言（'SSE 客户端断开'）保留手工，见 README 映射表。
    """
    patient = new_patient(cfg["hospital1"])
    session_id = api.create_patient_session(client, patient.token, "TRIAGE")

    with client.stream(
        "POST",
        "/api/chat/patient/stream",
        json={"sessionId": session_id, "message": "头痛三天，想挂神经内科"},
        headers={"Authorization": f"Bearer {patient.token}"},
    ) as resp:
        tokens = 0
        for line in resp.iter_lines():
            if line.startswith("event:token") or line.startswith("event: token"):
                tokens += 1
            if tokens >= 3:
                break  # 主动断开

    deadline = time.time() + 60
    last = None
    while time.time() < deadline:
        history = api.patient_history(client, patient.token, session_id)
        if history["messages"]:
            last = history["messages"][-1]
            if last["role"] == "assistant":
                break
        time.sleep(2)

    assert last and last["role"] == "assistant", "断开后未持久化部分回答"
    assert last["content"].strip(), "部分回答内容不应为空"
    assert DISCLAIMER_KEY in last["content"], "部分回答应附免责文案"


def test_local_fallback_manual_only():
    """#22 错误 KEY 降级：需改配置并重启 ai-chat-service，保留手工（见 README 映射表）"""
    pytest.skip("需改配置并重启服务，保留手工执行（E2E 清单 #22）")
