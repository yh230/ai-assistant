import uuid

from playwright.sync_api import expect

from pages.knowledge_page import KnowledgePage


def test_upload_list_delete(admin_page, api, admin_session, tmp_path):
    name = f"ui-e2e-{uuid.uuid4().hex[:8]}.md"
    file_path = tmp_path / name
    file_path.write_text("# UI E2E\n\n用于知识库 UI 自动化测试。\n", encoding="utf-8")

    page = KnowledgePage(admin_page)
    try:
        page.open()
        page.upload(file_path)
        expect(page.row(name)).to_be_visible(timeout=15000)

        page.delete(name)
        expect(page.row(name)).to_have_count(0, timeout=15000)
    finally:
        api.delete_doc_by_name(admin_session.token, name)
