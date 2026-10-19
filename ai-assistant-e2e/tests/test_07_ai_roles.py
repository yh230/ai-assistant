"""AI 角色库化与权限（员工/患者双域）。

依赖 admin（未绑院）与 staff1（已绑院）；患者角色断言使用 admin + 显式 hospitalId。
管理端患者列表会幂等补齐三类默认人设（CHAT/TRIAGE/RAG），患者聊天仍按需惰性建库，
因此患者域用例只需 GET 列表即可拿到完整默认集合，无需自行补建。
"""

import time

from helpers import api


def _headers(token):
    return {"Authorization": f"Bearer {token}"}


def _list_patient_roles(client, token, hospital_id):
    return api.unwrap(client.get("/api/roles/admin",
                                 params={"scope": "PATIENT", "hospitalId": hospital_id},
                                 headers=_headers(token)))


def _triage_role(client, admin, hospital_id):
    roles = _list_patient_roles(client, admin.token, hospital_id)
    return next((r for r in roles if r.get("sessionType") == "TRIAGE"), None)


def test_patient_roles_are_db_backed_per_hospital(client, admin, hospitals, cfg):
    """患者列表按院返回三类默认角色（服务端幂等补齐）"""
    hid = hospitals.get(cfg["hospital1"])
    assert hid, f"公开医院列表缺少「{cfg['hospital1']}」，请核对 .env 与 hospital_db 数据"
    roles = _list_patient_roles(client, admin.token, hid)
    types = {r.get("sessionType") for r in roles}
    assert {"CHAT", "TRIAGE", "RAG"} <= types, f"患者列表缺少默认会话类型: {roles}"
    assert all(r.get("hospitalId") == hid for r in roles), f"患者角色未按院隔离: {roles}"


def test_role_admin_requires_permission(client, staff1):
    """无 ai:role:manage 的普通员工访问角色管理应 403"""
    resp = client.get("/api/roles/admin", params={"scope": "STAFF"},
                      headers=_headers(staff1.token))
    assert resp.status_code == 403, (
        f"预期 403，实际 {resp.status_code}：请确认 .env 中 E2E_STAFF1_* 为未持有 "
        f"ai:role:manage 的普通员工账号"
    )


def test_patient_role_prompt_can_be_updated(client, admin, hospitals, cfg):
    """患者角色提示词可更新并读回（确定性断言），结束后还原原提示词"""
    hid = hospitals.get(cfg["hospital1"])
    assert hid, f"公开医院列表缺少「{cfg['hospital1']}」，请核对 .env 与 hospital_db 数据"
    triage = _triage_role(client, admin, hid)
    assert triage, f"未找到患者导诊角色（hid={hid}）"
    original_prompt = triage.get("systemPrompt")
    marker = f"E2E_ROLE_{int(time.time())}"
    try:
        api.unwrap(client.put(
            f"/api/roles/admin/{triage['id']}",
            json={"systemPrompt": f"导诊人设 {marker}"},
            headers=_headers(admin.token)))
        read_back = api.unwrap(client.get(
            f"/api/roles/admin/{triage['id']}", headers=_headers(admin.token)))
        assert marker in read_back["systemPrompt"], (
            f"更新后未读回标记 {marker}: {read_back.get('systemPrompt')}")
    finally:
        if original_prompt:
            api.unwrap(client.put(
                f"/api/roles/admin/{triage['id']}",
                json={"systemPrompt": original_prompt},
                headers=_headers(admin.token)))
