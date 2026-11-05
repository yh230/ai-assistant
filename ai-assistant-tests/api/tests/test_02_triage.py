"""清单节二：AI 导诊（#5-#8）。

整文件标记 ai：共享导诊会话链由真实大模型生成内容，离线模式（-m "not ai"）整体跳过。
"""

import pytest

from helpers import api
from helpers.sse import full_text

pytestmark = pytest.mark.ai

DISCLAIMER_KEY = "AI内容仅供参考，不能替代执业医师诊断"


@pytest.fixture(scope="module")
def triage(client, cfg, new_patient):
    """导诊会话链：注册本院患者 → 建 TRIAGE 会话 → 提问一次并缓存事件"""
    patient = new_patient(cfg["hospital1"])
    session_id = api.create_patient_session(client, patient.token, "TRIAGE")
    events = api.chat_patient(client, patient.token, session_id, "头痛三天，想挂神经内科")
    return {
        "patient": patient,
        "session_id": session_id,
        "events": events,
        "answer": full_text(events),
    }


def test_triage_recommends_real_department(triage, departments1):
    """#5 AI 推荐真实科室（与本院科室表数据一致，不存在编造科室）"""
    answer = triage["answer"]
    assert any(name in answer for name in departments1), \
        f"回答未包含任何本院真实科室名：{answer[-200:]}"


def test_disclaimer_present_and_persisted(client, triage):
    """#6 免责文案必现：流式最后一段 + 落库历史同样存在"""
    assert DISCLAIMER_KEY in triage["answer"]

    history = api.patient_history(client, triage["patient"].token, triage["session_id"])
    last = history["messages"][-1]
    assert last["role"] == "assistant"
    assert DISCLAIMER_KEY in last["content"]


def test_triage_offers_slot_query(triage):
    """#7 科室建议后主动询问是否需要查询号源（宽松关键词）"""
    assert any(kw in triage["answer"] for kw in ("号源", "预约", "挂号")), \
        f"回答未见号源引导：{triage['answer'][-200:]}"


def test_triage_session_visible_only_in_own_hospital(client, triage, staff1, staff2):
    """#8 问诊记录按医院可见：本院可见、他院不可见"""
    sid = triage["session_id"]
    ids1 = [s["sessionId"] for s in api.admin_sessions(client, staff1.token, "TRIAGE")["list"]]
    ids2 = [s["sessionId"] for s in api.admin_sessions(client, staff2.token, "TRIAGE")["list"]]
    assert sid in ids1, "本院员工应能看到该导诊会话"
    assert sid not in ids2, "他院员工不应看到该导诊会话"
