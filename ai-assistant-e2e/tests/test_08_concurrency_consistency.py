"""清单节：并发一致性回归（匿名重复预约 / 扫码一次性消费）。

不依赖大模型，断言均为确定性结果；并发预约用例要求存在可约排班，
缺少排班或号源时以 skip 说明原因，不伪造前置数据。
"""

import random
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta

import pytest

from helpers import api

CONCURRENCY = 2

# 环境侧不可约（排班/号源缺失）时跳过，避免误报为并发缺陷
_UNAVAILABLE_HINTS = ("号源已满", "无有效出诊排班", "该序号不可用")


def _gen_id_card() -> str:
    """生成 18 位测试身份证号（仅占位，接口不做格式校验）"""
    return f"11010119900101{random.randint(0, 9999):04d}"


def _pick_schedule(client, hospital_id: int) -> dict | None:
    """公开排班中挑本院未来 7 天余量最大的排班（余量、ID 排序保证确定性）"""
    candidates: list[dict] = []
    for offset in range(1, 8):
        day = (date.today() + timedelta(days=offset)).isoformat()
        candidates.extend(
            s for s in api.public_schedules(client, day)
            if s.get("hospitalId") == hospital_id and (s.get("remaining") or 0) > 0
        )
    if not candidates:
        return None
    return max(candidates, key=lambda s: (s["remaining"], -s["id"]))


def test_concurrent_anonymous_appointment_is_idempotent(client, cfg, hospitals, staff1):
    """匿名同参并发预约：返回同一单号或被「重复」拦截；库中仅 1 笔预约"""
    hospital_id = hospitals.get(cfg["hospital1"])
    if not hospital_id:
        pytest.skip(f"公开医院列表缺少「{cfg['hospital1']}」")

    schedule = _pick_schedule(client, hospital_id)
    if not schedule:
        pytest.skip(f"「{cfg['hospital1']}」未来 7 天无可约排班，无法验证并发预约")

    phone = api.gen_phone()
    id_card = _gen_id_card()
    payload = {
        "doctorUserId": schedule["doctorUserId"],
        "scheduleDate": schedule["scheduleDate"],
        "period": schedule["period"],
        "patientName": "E2E并发患者",
        "patientPhone": phone,
        "patientIdCard": id_card,
    }

    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        responses = list(pool.map(
            lambda _: client.post("/api/registration/appointments", json=payload),
            range(CONCURRENCY),
        ))
    assert all(r.status_code == 200 for r in responses), \
        f"预约接口 HTTP 异常：{[(r.status_code, r.text[:200]) for r in responses]}"

    bodies = [r.json() for r in responses]
    successes = [b["data"] for b in bodies
                 if b.get("code") == api.SUCCESS_CODE and b.get("data")]
    failures = [str(b.get("msg") or "") for b in bodies
                if b.get("code") != api.SUCCESS_CODE]

    if not successes:
        if any(hint in msg for msg in failures for hint in _UNAVAILABLE_HINTS):
            pytest.skip(f"排班无可约号源，跳过并发验证：{failures}")
        pytest.fail(f"并发预约全部失败：{failures}")

    try:
        if len(successes) == CONCURRENCY:
            assert successes[0].get("id") == successes[1].get("id"), \
                f"并发重复提交产生了不同预约单：{[s.get('id') for s in successes]}"
        else:
            assert any(any(kw in msg for kw in ("重复", "请勿")) for msg in failures), \
                f"失败响应未提示重复提交：{failures}"

        records = api.registration_records(client, staff1.token, phone)
        assert records["total"] == 1, \
            f"同一匿名患者并发预约产生了 {records['total']} 笔记录"
    finally:
        # 尽力释放号源，避免占用共享排班；清理失败不影响断言结论
        for reg_id in {s.get("id") for s in successes if s.get("id")}:
            try:
                client.post(f"/api/registration/appointments/{reg_id}/cancel",
                            json={"patientIdCard": id_card, "reason": "E2E并发用例清理"})
            except Exception:
                pass


def test_qr_scene_consumed_once_under_concurrency(client, staff1):
    """扫码场景并发轮询：至多一个响应携带令牌，其余为无令牌的 EXPIRED/WAITING"""
    scene = api.qr_create(client)["sceneId"]
    api.qr_confirm(client, staff1.token, scene)

    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        polls = list(pool.map(lambda _: api.qr_poll(client, scene), range(CONCURRENCY)))

    holders = [p for p in polls if p.get("token")]
    assert len(holders) <= 1, f"扫码场景被多次消费：{len(holders)} 个响应携带令牌"
    for poll in polls:
        if poll.get("token"):
            assert poll["status"] == "CONFIRMED", f"携带令牌的状态异常：{poll['status']}"
        else:
            assert poll["status"] in ("EXPIRED", "WAITING"), \
                f"未取到令牌的状态异常：{poll['status']}"
