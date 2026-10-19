"""AI 角色库化与权限（员工/患者双域）。

依赖 admin（未绑院）与 staff1（已绑院）；患者角色断言使用 admin + 显式 hospitalId。
管理端列表只读；患者角色（`pat_<hospitalId>_<...>`）仅在首次患者会话发消息时自动建库，
因此患者域用例自足化：列表缺失 TRIAGE 时先经管理端接口补建，再做断言。
"""

import time

from helpers import api


def _headers(token):
    return {"Authorization": f"Bearer {token}"}


def _list_patient_roles(client, token, hospital_id):
    return api.unwrap(client.get("/api/roles/admin",
                                 params={"scope": "PATIENT", "hospitalId": hospital_id},
                                 headers=_headers(token)))


def _ensure_triage_role(client, admin, hospital_id):
    roles = _list_patient_roles(client, admin.token, hospital_id)
    if not any(r.get("sessionType") == "TRIAGE" for r in roles):
        api.unwrap(client.post("/api/roles/admin", json={
            "id": f"pat_{hospital_id}_TRIAGE",
            "name": "AI 导诊",
            "systemPrompt": "E2E 初始化导诊人设",
            "scope": "PATIENT",
            "sessionType": "TRIAGE",
            "hospitalId": hospital_id,
            "enabled": True,
        }, headers=_headers(admin.token)))
        roles = _list_patient_roles(client, admin.token, hospital_id)
    return roles


def test_patient_roles_are_db_backed_per_hospital(client, admin, hospitals, cfg):
    """患者角色按院返回且含 TRIAGE；缺失时用例自建，不依赖其他用例先行"""
    hid = hospitals.get(cfg["hospital1"])
    assert hid, f"公开医院列表缺少「{cfg['hospital1']}」，请核对 .env 与 hospital_db 数据"
    roles = _ensure_triage_role(client, admin, hid)
    types = {r.get("sessionType") for r in roles}
    assert "TRIAGE" in types, f"未找到患者导诊角色: {roles}"
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
    roles = _ensure_triage_role(client, admin, hid)
    triage = next((r for r in roles if r.get("sessionType") == "TRIAGE"), None)
    assert triage, f"未找到患者导诊角色: {roles}"
    original_prompt = triage["systemPrompt"]
    marker = f"E2E_ROLE_{int(time.time())}"
    try:
        api.unwrap(client.put(
            f"/api/roles/admin/{triage['id']}",
            json={"systemPrompt": f"导诊人设 {marker}"},
            headers=_headers(admin.token)))
        read_back = api.unwrap(client.get(
            f"/api/roles/admin/{triage['id']}", headers=_headers(admin.token)))
        assert marker in read_back["systemPrompt"]
    finally:
        api.unwrap(client.put(
            f"/api/roles/admin/{triage['id']}",
            json={"systemPrompt": original_prompt},
            headers=_headers(admin.token)))
