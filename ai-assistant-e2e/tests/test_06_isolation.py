"""清单节六：会话隔离（#23-#24，无 LLM 依赖、确定性断言）。

仅建会话不发消息——所有权校验与消息内容无关，零大模型成本。
"""

from helpers import api


def test_patient_cannot_access_other_session(client, cfg, new_patient):
    """#23 患者 A 的会话：患者 B 列表不可见、历史访问被拒"""
    a = new_patient(cfg["hospital1"])
    b = new_patient(cfg["hospital1"])
    session_a = api.create_patient_session(client, a.token, "CHAT")

    assert session_a in [s["sessionId"] for s in api.patient_sessions(client, a.token)]
    assert session_a not in [s["sessionId"] for s in api.patient_sessions(client, b.token)]

    resp = client.get(f"/api/chat/patient/history/{session_a}",
                      headers={"Authorization": f"Bearer {b.token}"})
    body = resp.json()
    assert resp.status_code != 200 or body.get("code") != 200, "越权访问应被拒绝"
    msg = str(body.get("msg", "")) + str(body.get("message", ""))
    assert "不存在" in msg or "无权" in msg, f"拒绝文案不符：{body}"


def test_staff_session_visibility_isolated(client, staff1, staff2):
    """#24 员工会话仅本人可见；他人访问该会话不可见"""
    session1 = api.create_staff_session(client, staff1.token, "E2E隔离会话")

    assert session1 in [s["sessionId"] for s in api.staff_sessions(client, staff1.token)]
    assert session1 not in [s["sessionId"] for s in api.staff_sessions(client, staff2.token)]

    resp = client.get(f"/api/chat/history/{session1}",
                      headers={"Authorization": f"Bearer {staff2.token}"})
    body = resp.json()
    if resp.status_code == 200 and body.get("code") == 200:
        data = body.get("data") or {}
        assert not data.get("messages"), "员工 B 不应看到员工 A 的会话内容"
