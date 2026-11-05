"""清单节一：身份与越权（#1-#4，无 LLM 依赖、确定性断言）"""

from helpers import api


def test_patient_register_login_and_token_works(client, cfg, hospitals):
    """#1 患者注册 → 登录 → 凭 token 调用患者接口

    浏览器 localStorage / 前端跳转属前端行为，不在本套件范围。
    """
    phone = api.gen_phone()
    user_id = api.register_patient(client, phone, "E2eTest123", "E2E注册患者", hospitals[cfg["hospital1"]])
    assert isinstance(user_id, int) and user_id > 0

    token = api.login_patient(client, phone, "E2eTest123")
    assert api.patient_sessions(client, token) is not None


def test_patient_token_rejected_on_staff_api(client, cfg, new_patient):
    """#2 患者 token 调员工接口 → 403（网关身份路径拦截）"""
    patient = new_patient(cfg["hospital1"])
    resp = client.get("/api/system/user/page", headers={"Authorization": f"Bearer {patient.token}"})
    assert resp.status_code == 403


def test_staff_token_rejected_on_patient_api(client, staff1):
    """#3 员工 token 调患者接口 → 403"""
    resp = client.get("/api/chat/patient/sessions", headers={"Authorization": f"Bearer {staff1.token}"})
    assert resp.status_code == 403


def test_staff_logout_and_kickout_invalidate_token(client, admin, staff1):
    """#4 登出 / 踢下线后旧 token 立即失效，重新登录恢复正常"""
    # 登出用独立新 token，避免影响 session 级 staff1 供后续用例使用
    logout_token = api.login_staff(client, staff1.username, staff1.password)
    api.logout(client, logout_token)
    resp = client.get("/api/getInfo", headers={"Authorization": f"Bearer {logout_token}"})
    assert resp.status_code == 401

    # 踢下线 staff1 当前 token；随后回填新 token 供后续用例继续使用
    api.kickout(client, admin.token, staff1.user_id)
    resp = client.get("/api/getInfo", headers={"Authorization": f"Bearer {staff1.token}"})
    assert resp.status_code == 401

    staff1.token = api.login_staff(client, staff1.username, staff1.password)
    resp = client.get("/api/getInfo", headers={"Authorization": f"Bearer {staff1.token}"})
    assert resp.status_code == 200
