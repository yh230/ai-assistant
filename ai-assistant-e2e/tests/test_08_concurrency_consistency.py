"""清单节：并发一致性回归（匿名重复预约 / 扫码一次性消费）。

不依赖大模型，断言均为确定性结果；并发预约用例要求存在可约排班，
仅在完全没有可约排班或排班被环境变更移除时 skip，不伪造前置数据。
"""

import json
import logging
import random
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta

import pytest

from helpers import api

logger = logging.getLogger(__name__)

CONCURRENCY = 2


def _gen_id_card() -> str:
    """生成 18 位测试身份证号占位值。

    后端目前只按非空/长度处理，未校验出生日期与末位校验码，
    因此这里只保证 18 位数字，不构造合法校验位，避免与真实身份证混淆。
    """
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


def _schedule_still_visible(client, schedule: dict) -> bool:
    """POST 前复查同一排班 ID 是否仍在公开排班列表中"""
    return any(
        s.get("id") == schedule["id"]
        for s in api.public_schedules(client, schedule["scheduleDate"])
    )


def test_concurrent_anonymous_appointment_is_idempotent(client, cfg, hospitals, staff1):
    """匿名同参并发预约：返回同一单号或被「重复」拦截；库中仅 1 笔预约"""
    hospital_id = hospitals.get(cfg["hospital1"])
    if not hospital_id:
        pytest.skip(f"公开医院列表缺少「{cfg['hospital1']}」")

    schedule = _pick_schedule(client, hospital_id)
    if not schedule:
        pytest.skip(f"「{cfg['hospital1']}」未来 7 天无可约排班，无法验证并发预约")

    # 选中排班后、发起预约前立即复查：只有排班整体消失（环境被变更）才跳过，
    # 余量变化或业务侧「号源已满/无有效出诊排班」都必须作为缺陷暴露。
    if not _schedule_still_visible(client, schedule):
        pytest.skip(f"预约前复查发现排班 {schedule['id']} 已从公开列表消失，环境已变更")

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
    successes: list[dict] = []
    failures: list[dict] = []
    for body in bodies:
        if body.get("code") == api.SUCCESS_CODE and body.get("data"):
            successes.append(body["data"])
        else:
            failures.append(body)

    assert successes, (
        "并发预约全部失败；选中排班余量为 "
        f"{schedule.get('remaining')}，不应返回号源不足类错误。响应："
        f"{json.dumps(failures, ensure_ascii=False)}"
    )

    try:
        if len(successes) == CONCURRENCY:
            assert successes[0].get("id") == successes[1].get("id"), \
                f"并发重复提交产生了不同预约单：{[s.get('id') for s in successes]}"
        else:
            messages = [str(b.get("msg") or "") for b in failures]
            assert any(any(kw in msg for kw in ("重复", "请勿")) for msg in messages), \
                f"失败响应未提示重复提交：{messages}"

        records = api.registration_records(client, staff1.token, phone)
        assert records["total"] == 1, \
            f"同一匿名患者并发预约产生了 {records['total']} 笔记录"
    finally:
        # 尽力释放号源，避免占用共享排班；清理失败只告警，不影响断言结论
        for reg_id in {s.get("id") for s in successes if s.get("id")}:
            url = f"/api/registration/appointments/{reg_id}/cancel"
            try:
                resp = client.post(
                    url, json={"patientIdCard": id_card, "reason": "E2E并发用例清理"})
            except Exception as exc:  # noqa: BLE001 - 清理失败不应掩盖原始断言
                logger.warning("清理预约 %s 失败：请求异常 %r", reg_id, exc)
                continue
            try:
                cleaned = (resp.status_code < 400
                           and (resp.json() or {}).get("code") == api.SUCCESS_CODE)
            except ValueError:
                cleaned = False
            if not cleaned:
                logger.warning("清理预约 %s 失败：HTTP %s，响应 %s",
                               reg_id, resp.status_code, resp.text[:500])


def test_qr_scene_consumed_once_under_concurrency(client, staff1):
    """扫码场景并发轮询：恰好一个响应携带令牌且为 CONFIRMED，另一个为 EXPIRED"""
    scene = api.qr_create(client)["sceneId"]
    api.qr_confirm(client, staff1.token, scene)

    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        polls = list(pool.map(lambda _: api.qr_poll(client, scene), range(CONCURRENCY)))

    token_indexes = [i for i, poll in enumerate(polls) if poll.get("token")]
    assert len(token_indexes) == 1, (
        f"扫码场景应恰好被消费一次，实际 {len(token_indexes)} 个响应携带令牌："
        f"{json.dumps(polls, ensure_ascii=False)}"
    )

    winner = polls[token_indexes[0]]
    assert winner.get("status") == "CONFIRMED", \
        f"携带令牌的状态异常：{winner.get('status')}"
    assert winner.get("token"), f"CONFIRMED 响应缺少令牌：{winner}"

    loser = polls[1 - token_indexes[0]]
    assert not loser.get("token"), f"未获令牌的响应不应携带令牌：{loser}"
    assert loser.get("status") == "EXPIRED", \
        f"未获令牌的响应应为 EXPIRED，实际：{loser.get('status')}"
