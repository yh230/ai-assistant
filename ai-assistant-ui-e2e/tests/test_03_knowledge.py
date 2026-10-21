import logging
import uuid

from playwright.sync_api import expect

from pages.knowledge_page import KnowledgePage


def test_upload_list_delete(staff1_page, api, staff1_session, tmp_path):
    name = f"ui-e2e-{uuid.uuid4().hex[:8]}.md"
    file_path = tmp_path / name
    file_path.write_text("# UI E2E\n\n用于知识库 UI 自动化测试。\n", encoding="utf-8")

    page = KnowledgePage(staff1_page)
    try:
        page.open()
        page.upload(file_path)
        expect(page.row(name)).to_be_visible(timeout=15000)

        page.delete(name)
        expect(page.row(name)).to_have_count(0, timeout=15000)
    finally:
        try:
            api.delete_doc_by_name(staff1_session.token, name)
        except Exception as ex:
            logging.warning("知识库测试数据清理失败（忽略）：%s", ex)
