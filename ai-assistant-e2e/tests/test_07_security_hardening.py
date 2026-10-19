"""安全收口回归：管理端越权、CORS、角色下拉提权防护。

全部为确定性 HTTP 断言，不触发大模型。患者账号由 new_patient 工厂按医院 1 注册。

说明：
- 共享 E2E 账号（staff1）实际持有 ai:chat:admin（见 test_02_triage 的管理端会话用例），
  因此本文件不再断言“普通员工访问管理端应 403”；该权限门的覆盖由患者令牌 403
  与控制器权限注解的单元测试承担，也不为本用例新增员工账号。
- 可选环境变量 E2E_HOSPITAL_DIRECTOR_USER / E2E_HOSPITAL_DIRECTOR_PASSWORD：
  配置后角色下拉与跨院读取用例使用该院长账号；未配置则角色下拉回退 staff1，
  跨院读取用例 pytest.skip，不静默通过。
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


def test_allowed_origin_gets_allow_origin(client):
    """白名单 Origin 对 /api/login 的预检应回显 access-control-allow-origin（开发默认 5173）"""
    origin = "http://localhost:5173"
    resp = client.options("/api/login", headers={
        "Origin": origin,
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type",
    })
    assert resp.status_code < 500, (
        f"预检请求不应返回服务端错误：HTTP {resp.status_code}，body={resp.text[:300]}"
    )
    allowed = resp.headers.get("access-control-allow-origin")
    assert allowed == origin, (
        f"白名单 Origin 应获得 CORS 放行，预期 {origin!r}，实际 {allowed!r}（HTTP {resp.status_code}）"
    )


def test_evil_origin_gets_no_allow_origin(client):
    """非白名单 Origin 对 /api/login 的预检不得回显 access-control-allow-origin"""
    resp = client.options("/api/login", headers={
        "Origin": "https://evil.example.com",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type",
    })
    asserted = resp.headers.get("access-control-allow-origin")
    # 先排除服务端错误：5xx 通常也不带 CORS 头，若只查头会让真实故障“假通过”。
    assert resp.status_code < 500, (
        f"预检请求不应返回服务端错误：HTTP {resp.status_code}，body={resp.text[:300]}"
    )
    assert asserted is None, (
        f"非白名单 Origin 不应获得 CORS 放行，实际返回 {asserted!r}（HTTP {resp.status_code}）"
    )


def test_non_admin_role_dropdown_excludes_admin(client, non_admin_operator):
    """非 admin 的员工角色下拉不得出现 admin（防止授予提权）"""
    resp = client.get("/api/hospital/employee/roles", headers=_bearer(non_admin_operator))
    # staff1 可能缺少 hospital:employee:list 且套件未提供 hospital_director fixture：
    # 此时接口直接 403，无法验证下拉收敛，显式 skip 避免“静默通过”。
    if resp.status_code == 403:
        pytest.skip(
            "staff1 lacks hospital:employee:list; set E2E_HOSPITAL_DIRECTOR_USER/PASSWORD "
            "to cover admin exclusion"
        )
    assert resp.status_code == 200, (
        f"角色下拉请求异常：HTTP {resp.status_code}，body={resp.text[:300]}"
    )
    body = resp.json()
    assert body.get("code") == 200, f"角色下拉业务码非 200：{body}"
    roles = body.get("data") or []
    codes = {str(r.get("roleCode")) for r in roles}
    assert "admin" not in codes, f"非 admin 操作者的角色下拉不应包含 admin：{sorted(codes)}"


def test_director_cannot_read_other_hospital(client, cfg, hospitals):
    """院长读取他院详情应 403（数据范围按医院归属强制校验）

    需要可选的 E2E_HOSPITAL_DIRECTOR_USER/PASSWORD；未配置时 skip。
    先读取本院详情作为基准，若本院也 403（角色/权限播种缺失）则 skip，
    避免把“无 hospital:manage 权限”误判为跨院校验通过。
    """
    username = os.environ.get("E2E_HOSPITAL_DIRECTOR_USER", "")
    password = os.environ.get("E2E_HOSPITAL_DIRECTOR_PASSWORD", "")
    if not username or not password:
        pytest.skip("set E2E_HOSPITAL_DIRECTOR_USER/PASSWORD to cover cross-hospital read")

    token = api.login_staff(client, username, password)
    own_id = hospitals.get(cfg["hospital1"])
    other_id = hospitals.get(cfg["hospital2"])
    assert own_id and other_id, "公开医院列表缺少配置的医院，请核对 .env 与数据"

    own = client.get(f"/api/hospital/info/{own_id}", headers=_bearer(token))
    if own.status_code == 403:
        pytest.skip("director lacks hospital:manage; cannot isolate cross-hospital check")
    assert own.status_code == 200, (
        f"院长读取本院详情异常：HTTP {own.status_code}，body={own.text[:300]}"
    )

    resp = client.get(f"/api/hospital/info/{other_id}", headers=_bearer(token))
    assert resp.status_code == 403, (
        f"院长跨院读取医院详情：预期 403，实际 {resp.status_code}，body={resp.text[:300]}"
    )
