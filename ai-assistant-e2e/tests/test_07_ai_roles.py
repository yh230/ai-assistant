"""AI 角色库化与权限（员工/患者双域）。

依赖 admin（未绑院）与 staff1（已绑院）；患者角色断言使用 admin + 显式 hospitalId。
"""

import pytest

from helpers import api


def _role_query(client, token, scope, hospital_id=None):
    params = {"scope": scope}
    if hospital_id:
        params["hospitalId"] = hospital_id
    return api.unwrap(client.get("/api/roles/admin", params=params, headers={"Authorization": f"Bearer {token}"}))


def test_patient_roles_are_db_backed_per_hospital(client, admin, hospitals, cfg):
    """患者角色按院返回；首次访问触发惰性建库后至少含 TRIAGE"""
    hid = hospitals[cfg["hospital1"]]
    roles = _role_query(client, admin.token, "PATIENT", hid)
    types = {r.get("sessionType") for r in roles}
    assert "TRIAGE" in types, f"未找到患者导诊角色: {roles}"


def test_role_admin_requires_permission(client, staff1):
    """无 ai:role:manage 的普通员工访问角色管理应 403"""
    resp = client.get("/api/roles/admin", params={"scope": "STAFF"},
                      headers={"Authorization": f"Bearer {staff1.token}"})
    assert resp.status_code == 403


def test_patient_role_prompt_can_be_updated(client, admin, hospitals, cfg):
    """患者角色提示词可更新并读回（确定性断言）"""
    import time
    hid = hospitals[cfg["hospital1"]]
    roles = _role_query(client, admin.token, "PATIENT", hid)
    triage = next(r for r in roles if r.get("sessionType") == "TRIAGE")
    marker = f"E2E_ROLE_{int(time.time())}"
    updated = api.unwrap(client.put(
        f"/api/roles/admin/{triage['id']}",
        json={"systemPrompt": f"导诊人设 {marker}"},
        headers={"Authorization": f"Bearer {admin.token}"}))
    assert marker in updated["systemPrompt"]
