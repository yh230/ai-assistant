import re

from playwright.sync_api import expect

from config import settings
from pages.staff_login_page import StaffLoginPage


def test_staff_login_success(admin_page):
    login = StaffLoginPage(admin_page)
    login.open()
    login.login(settings.admin_user, settings.admin_password)
    expect(admin_page).to_have_url(re.compile(r"/chat$"))
    expect(admin_page.get_by_test_id("app-menu")).to_be_visible()
