import re
import uuid

from playwright.sync_api import expect

from config import settings
from pages.staff_login_page import StaffLoginPage


def test_staff_login_success(anon_page):
    login = StaffLoginPage(anon_page)
    login.open()
    login.login(settings.admin_user, settings.admin_password)
    expect(anon_page).to_have_url(re.compile(r"/chat$"))
    expect(anon_page.get_by_test_id("app-menu")).to_be_visible()


def test_staff_login_bad_credentials(anon_page):
    username = f"ui-e2e-missing-{uuid.uuid4().hex[:8]}"
    login = StaffLoginPage(anon_page)
    login.open()
    login.login(username, "WrongPass123")
    expect(anon_page.get_by_test_id("login-error")).to_be_visible()
    expect(anon_page).to_have_url(re.compile(r"/login$"))
