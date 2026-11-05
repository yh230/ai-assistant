import pytest

from config import settings
from pages.patient_chat_page import PatientChatPage

DISCLAIMER = "AI内容仅供参考，不能替代执业医师诊断"


@pytest.mark.ai
@pytest.mark.timeout(600)
def test_triage_stream_and_disclaimer(ai_ready, patient_page, api, admin_session):
    page = PatientChatPage(patient_page)
    page.open()
    page.new_session("TRIAGE")
    page.send("头痛三天，想挂神经内科")
    page.wait_done(settings.llm_timeout_ms)

    text = page.last_assistant().inner_text()
    assert DISCLAIMER in text

    departments = [
        d["name"] for d in api.departments(
            admin_session.token,
            api.hospital_id(settings.hospital1),
        )
    ]
    assert any(name in text for name in departments), f"回答未包含本院真实科室：{text[-200:]}"
