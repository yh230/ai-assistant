import re

from playwright.sync_api import expect

from config import settings
from pages.staff_login_page import StaffLoginPage


def test_staff_login_success(anon_page):
    login = StaffLoginPage(anon_page)
    login.open()
    login.login(settings.admin_user, settings.admin_password)
    expect(anon_page).to_have_url(re.compile(r"/chat$"))
    expect(anon_page.get_by_test_id("app-menu")).to_be_visible()
