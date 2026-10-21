import re

import pytest
from playwright.sync_api import expect

from api_client import gen_phone
from config import settings
from pages.patient_login_page import PatientLoginPage


def test_patient_register_then_login(anon_page):
    if not settings.hospital1:
        pytest.skip("未配置 UI_E2E_HOSPITAL1_NAME，无法按医院名称选择")
    phone = gen_phone()
    page = PatientLoginPage(anon_page)
    page.open()
    page.register("UIE2E患者", phone, "UiE2e123", settings.hospital1)
    expect(anon_page.get_by_test_id("patient-phone")).to_be_visible()
    page.login(phone, "UiE2e123")
    expect(anon_page).to_have_url(re.compile(r"/patient/chat"))
    expect(anon_page.get_by_test_id("patient-chat")).to_be_visible()
