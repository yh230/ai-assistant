"""清单节：手机号验证码与扫码登录（无 LLM 依赖、确定性断言）。

前置：服务已启动；Nacos 短信通道为 console 降级且配置万能码（默认 123456）。
员工验证码登录要求账号已绑定手机号：本套件在运行时经 /api/profile 绑定随机手机号。
"""

from helpers import api

MASTER_CODE = "123456"


def test_sms_login_with_master_code(client, cfg, new_patient, staff1):
    """验证码登录（万能码）：患者与员工均可换回令牌对

    患者账号以手机号为登录名；员工账号先经个人信息接口绑定随机手机号再登录。
    """
    patient = new_patient(cfg["hospital1"])
    api.send_sms_code(client, "patient", patient.username)
    patient_login = api.sms_login(client, "patient", patient.username, MASTER_CODE)
    assert patient_login["token"]
    assert patient_login["refreshToken"]

    staff_phone = api.gen_phone()
    api.update_profile_phone(client, staff1.token, staff_phone)
    api.send_sms_code(client, "staff", staff_phone)
    staff_login = api.sms_login(client, "staff", staff_phone, MASTER_CODE)
    assert staff_login["token"]
    assert staff_login["refreshToken"]
    assert api.get_info(client, staff_login["token"])["userId"] == staff1.user_id


def test_qr_create_poll_waiting_before_confirm(client):
    """扫码未确认时轮询保持 WAITING"""
    scene_id = api.qr_create(client)["sceneId"]
    assert api.qr_poll(client, scene_id)["status"] == "WAITING"


def test_qr_login_full_flow(client, cfg, new_patient):
    """扫码登录全链路：create → 手机端 confirm → poll 取令牌 → 二次 poll 已消费为 EXPIRED"""
    patient = new_patient(cfg["hospital1"])
    scene_id = api.qr_create(client)["sceneId"]

    api.qr_confirm(client, patient.token, scene_id)

    confirmed = api.qr_poll(client, scene_id)
    assert confirmed["status"] == "CONFIRMED"
    assert confirmed["token"]
    assert confirmed["refreshToken"]
    assert api.patient_sessions(client, confirmed["token"]) is not None

    assert api.qr_poll(client, scene_id)["status"] == "EXPIRED"


def test_sms_wrong_code_locks_phone(client):
    """连续错误验证码触发失败计数并锁定（用随机未注册手机号，避免锁死共享账号）"""
    phone = api.gen_phone()
    body = {}
    for _ in range(7):
        body = client.post("/api/login/sms",
                           json={"identity": "patient", "phone": phone, "code": "000000"}).json()
        if "锁定" in str(body.get("msg", "")):
            break
    assert "登录失败次数过多" in str(body.get("msg", "")), f"连续错误未触发锁定：{body}"
