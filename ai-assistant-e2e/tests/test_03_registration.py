"""清单节三：智能挂号二次确认（#9-#14）。

整文件标记 ai：确认状态机由真实大模型工具调用触发。
#13 额外标 slow（5 分钟等待）；#14 以 skip 存根保留手工原因。
"""

import time
from datetime import date, timedelta

import pytest

from helpers import api
from helpers.sse import error_message, full_text

pytestmark = pytest.mark.ai


@pytest.fixture(scope="module")
def booking(client, cfg, staff1, new_patient):
    """预约链：导诊推荐科室 → 发起预约 → 截至 confirm_required，缓存前置快照"""
    patient = new_patient(cfg["hospital1"])
    session_id = api.create_patient_session(client, patient.token, "TRIAGE")
    api.chat_patient(client, patient.token, session_id, "头痛三天，想挂神经内科")
    events = api.chat_patient(client, patient.token, session_id, "帮我挂明天上午的号",
                              until={"confirm_required"})
    confirm_events = [p for n, p in events if n == "confirm_required"]
    assert confirm_events, f"未触发确认事件（模型未按预期调用预约工具）：{full_text(events)[-200:]}"

    tomorrow = (date.today() + timedelta(days=1)).isoformat()
    pool_before = {p["scheduleId"]: p["remainingCount"] for p in api.pools(client, staff1.token, tomorrow)}
    records_before = api.registration_records(client, staff1.token, patient.username)["total"]
    return {
        "patient": patient,
        "session_id": session_id,
        "action_id": confirm_events[0]["actionId"],
        "summary": str(confirm_events[0].get("summary", "")),
        "pool_before": pool_before,
        "records_before": records_before,
        "date": tomorrow,
    }


def test_booking_request_emits_confirm_required(booking):
    """#9 预约先经二次确认：事件含 actionId 与摘要（科室/医生/时段）"""
    assert booking["action_id"]
    assert booking["summary"], "确认摘要不应为空"
    assert any(kw in booking["summary"] for kw in ("上午", "下午")), \
        f"摘要未见时段信息：{booking['summary']}"


def test_confirm_booking_success_and_slot_decrement(client, staff1, booking):
    """#10 确认 → 成功文案；号源余量 -1；挂号记录可按手机号查到"""
    events = api.confirm_action(client, booking["patient"].token, booking["action_id"])
    err = error_message(events)
    if err:
        pytest.fail(f"确认预约失败：{err}")

    text = full_text(events)
    assert any(kw in text for kw in ("成功", "已预约", "预约成功")), f"未见成功文案：{text[-200:]}"

    pool_after = {p["scheduleId"]: p["remainingCount"] for p in api.pools(client, staff1.token, booking["date"])}
    decreased = [
        sid for sid, before in booking["pool_before"].items()
        if pool_after.get(sid) == before - 1
    ]
    assert decreased, f"号源余量未减 1：before={booking['pool_before']} after={pool_after}"

    records = api.registration_records(client, staff1.token, booking["patient"].username)
    assert records["total"] == booking["records_before"] + 1, "挂号记录数未 +1"


def test_duplicate_booking_rejected(client, booking):
    """#11 同日重复预约 → AI 转述业务错误（若仍弹确认则确认后走错误转述）"""
    events = api.chat_patient(client, booking["patient"].token, booking["session_id"],
                              "再帮我挂明天上午的号")
    confirm_events = [p for n, p in events if n == "confirm_required"]
    if confirm_events:  # 模型仍签发确认时，确认后由业务侧拒绝
        events = events + api.confirm_action(client, booking["patient"].token, confirm_events[0]["actionId"])

    combined = full_text(events) + " " + (error_message(events) or "")
    assert any(kw in combined for kw in ("已有预约", "重复预约", "请勿重复")), \
        f"未见重复预约拦截文案：{combined[-300:]}"


def test_cancel_confirm_keeps_state_unchanged(client, staff1, booking):
    """#12 取消确认：无业务变化；同 actionId 再确认被拒（已处理）"""
    events = api.chat_patient(client, booking["patient"].token, booking["session_id"],
                              "再帮我预约一个明天下午的号")
    confirm_events = [p for n, p in events if n == "confirm_required"]
    assert confirm_events, f"未产生新的待确认操作：{full_text(events)[-200:]}"
    action_id = confirm_events[0]["actionId"]

    pool_before = {p["scheduleId"]: p["remainingCount"] for p in api.pools(client, staff1.token, booking["date"])}
    records_before = api.registration_records(client, staff1.token, booking["patient"].username)["total"]

    api.cancel_action(client, booking["patient"].token, action_id)

    pool_after = {p["scheduleId"]: p["remainingCount"] for p in api.pools(client, staff1.token, booking["date"])}
    assert pool_after == pool_before, "取消后号源余量不应变化"
    assert api.registration_records(client, staff1.token, booking["patient"].username)["total"] == records_before

    again = api.confirm_action(client, booking["patient"].token, action_id)
    msg = error_message(again) or ""
    assert any(kw in msg for kw in ("已处理", "已取消", "已失效", "不存在")), f"再确认应被拒：{msg}"


@pytest.mark.slow
@pytest.mark.timeout(600)
def test_confirm_expired_action(client, cfg, new_patient):
    """#13 确认超时：EXPIRY_MINUTES=5 硬编码，等待后确认被拒（默认跳过，-m slow 触发）"""
    patient = new_patient(cfg["hospital1"])
    session_id = api.create_patient_session(client, patient.token, "TRIAGE")
    api.chat_patient(client, patient.token, session_id, "头痛三天，想挂神经内科")
    events = api.chat_patient(client, patient.token, session_id, "帮我挂明天上午的号",
                              until={"confirm_required"})
    confirm_events = [p for n, p in events if n == "confirm_required"]
    assert confirm_events, "未产生待确认操作"

    time.sleep(5 * 60 + 10)

    again = api.confirm_action(client, patient.token, confirm_events[0]["actionId"])
    assert "超时" in (error_message(again) or ""), f"应提示确认已超时：{error_message(again)}"


def test_stop_registration_service_manual_only():
    """#14 停 registration-service 容错：需人为停服，保留手工（见 README 映射表）"""
    pytest.skip("需人为停服验证，保留手工执行（E2E 清单 #14）")
