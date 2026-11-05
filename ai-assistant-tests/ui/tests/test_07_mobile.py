import pytest
from playwright.sync_api import expect

from config import settings

pytestmark = pytest.mark.mobile


def test_patient_login_no_horizontal_overflow(mobile_anon_page):
    mobile_anon_page.goto(f"{settings.base_url}/patient/login")
    assert mobile_anon_page.evaluate("window.innerWidth") == 390
    assert mobile_anon_page.evaluate("document.documentElement.scrollWidth") <= 390


def test_patient_chat_no_horizontal_overflow(mobile_patient_page):
    mobile_patient_page.goto(f"{settings.base_url}/patient/chat")
    expect(mobile_patient_page.get_by_test_id("patient-chat")).to_be_visible()
    assert mobile_patient_page.evaluate("window.innerWidth") == 390
    assert mobile_patient_page.evaluate("document.documentElement.scrollWidth") <= 390
