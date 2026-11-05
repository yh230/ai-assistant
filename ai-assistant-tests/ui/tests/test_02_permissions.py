from playwright.sync_api import expect

from config import settings


def _open_chat(page):
    page.goto(f"{settings.base_url}/chat")
    expect(page.get_by_test_id("app-menu")).to_be_visible()


def test_admin_sees_system_management(admin_page):
    _open_chat(admin_page)
    # 先确认菜单已渲染出公共项，避免“权限项不存在”因页面空白而假通过
    expect(admin_page.get_by_text("智能聊天", exact=True)).to_be_visible()
    expect(admin_page.get_by_text("系统管理", exact=True)).to_be_visible()
    expect(admin_page.get_by_text("用户管理", exact=True)).to_be_visible()


def test_staff1_does_not_see_system_management(staff1_page):
    _open_chat(staff1_page)
    expect(staff1_page.get_by_text("智能聊天", exact=True)).to_be_visible()
    expect(staff1_page.get_by_text("系统管理", exact=True)).to_have_count(0)
    expect(staff1_page.get_by_text("用户管理", exact=True)).to_have_count(0)
    expect(staff1_page.get_by_text("角色权限", exact=True)).to_have_count(0)
