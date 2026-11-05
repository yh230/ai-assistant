import time

import pytest

from config import settings
from pages.patient_chat_page import PatientChatPage


@pytest.mark.ai
@pytest.mark.timeout(600)
def test_booking_requires_confirm_and_creates_record(
    ai_ready, patient_page, patient, api, staff1_session
):
    page = PatientChatPage(patient_page)
    page.open()
    page.new_session("TRIAGE")
    page.send("头痛三天，想挂神经内科")
    page.wait_done(settings.llm_timeout_ms)

    page.send("帮我挂明天上午的号")
    page.wait_confirm_dialog()
    before = api.registration_records(staff1_session.token, patient.phone)
    assert before.get("total", 0) == 0, f"确认前已存在预约记录：{before}"

    summary = page.confirm_booking()
    assert any(k in summary for k in ("上午", "下午")), f"确认摘要未见时段：{summary}"

    page.wait_done(settings.llm_timeout_ms)

    records = {}
    for _ in range(30):
        records = api.registration_records(staff1_session.token, patient.phone)
        if records.get("total", 0) >= 1:
            break
        time.sleep(1)
    assert records.get("total", 0) >= 1, f"未查到本次预约记录：{records}"
