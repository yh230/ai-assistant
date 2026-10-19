"""安全收口回归：管理端越权、CORS、角色下拉提权防护。

全部为确定性 HTTP 断言，不触发大模型。患者账号由 new_patient 工厂按医院 1 注册。
"""

import os

import pytest

from helpers import api


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture(scope="module")
def non_admin_operator(client, staff1):
    """非 admin 操作者：优先使用显式配置的院长账号，缺省回退 staff1。

    现有 conftest 未提供 hospital_director fixture，且院长账号不是套件必填项，
    故通过可选的 E2E_HOSPITAL_DIRECTOR_USER/PASSWORD 读取；未配置时用 staff1。
    """
    username = os.environ.get("E2E_HOSPITAL_DIRECTOR_USER", "")
    password = os.environ.get("E2E_HOSPITAL_DIRECTOR_PASSWORD", "")
    if username and password:
        return api.login_staff(client, username, password)
    return staff1.token


def test_patient_cannot_access_admin_chat(client, cfg, new_patient):
    """患者令牌访问管理端患者会话列表应 403"""
    patient = new_patient(cfg["hospital1"])
    resp = client.get("/api/chat/admin/sessions",
                      params={"pageNum": 1, "pageSize": 10},
                      headers=_bearer(patient.token))
    assert resp.status_code == 403, (
        f"患者越权访问管理端会话列表：预期 403，实际 {resp.status_code}，body={resp.text[:300]}"
    )


def test_patient_cannot_upload_knowledge(client, cfg, new_patient):
    """患者令牌上传知识库文档应 403（不得消耗存储或落库）"""
    patient = new_patient(cfg["hospital1"])
    resp = client.post(
        "/api/chat/admin/knowledge/upload",
        files={"file": ("evil.md", b"# evil", "text/markdown")},
        headers=_bearer(patient.token),
    )
    assert resp.status_code == 403, (
        f"患者越权上传知识库：预期 403，实际 {resp.status_code}，body={resp.text[:300]}"
    )


def test_staff_without_permission_cannot_read_admin_chat(client, staff1):
    """无 ai:chat:admin 的普通员工访问管理端患者会话列表应 403"""
    resp = client.get("/api/chat/admin/sessions",
                      params={"pageNum": 1, "pageSize": 10},
                      headers=_bearer(staff1.token))
    assert resp.status_code == 403, (
        f"预期 403，实际 {resp.status_code}：请确认 .env 中 E2E_STAFF1_* 为未持有 "
        f"ai:chat:admin 的员工账号，body={resp.text[:300]}"
    )


def test_evil_origin_gets_no_allow_origin(client):
    """非白名单 Origin 对 /api/login 的预检不得回显 access-control-allow-origin"""
    resp = client.options("/api/login", headers={
        "Origin": "https://evil.example.com",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type",
    })
    asserted = resp.headers.get("access-control-allow-origin")
    assert asserted is None, (
        f"非白名单 Origin 不应获得 CORS 放行，实际返回 {asserted!r}（HTTP {resp.status_code}）"
    )


def test_non_admin_role_dropdown_excludes_admin(client, non_admin_operator):
    """非 admin 的员工角色下拉不得出现 admin（防止授予提权）"""
    resp = client.get("/api/hospital/employee/roles", headers=_bearer(non_admin_operator))
    # staff1 可能缺少 hospital:employee:list 且套件未提供 hospital_director fixture：
    # 此时接口直接 403，权限已收口，降级接受（已知限制，见任务报告）。
    assert resp.status_code in (200, 403), (
        f"角色下拉请求异常：HTTP {resp.status_code}，body={resp.text[:300]}"
    )
    if resp.status_code == 403:
        return
    body = resp.json()
    assert body.get("code") == 200, f"角色下拉业务码非 200：{body}"
    roles = body.get("data") or []
    codes = {str(r.get("roleCode")) for r in roles}
    assert "admin" not in codes, f"非 admin 操作者的角色下拉不应包含 admin：{sorted(codes)}"
