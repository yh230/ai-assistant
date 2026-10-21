from pathlib import Path

from playwright.sync_api import Locator

from pages.base_page import BasePage


class KnowledgePage(BasePage):
    def open(self) -> None:
        self.goto("/admin/knowledge")

    def upload(self, file_path: Path) -> None:
        self.testid("knowledge-file-input").set_input_files(str(file_path))
        self.testid("knowledge-upload").click()

    def row(self, name: str) -> Locator:
        return self.page.locator("tr", has_text=name)

    def delete(self, name: str) -> None:
        self.row(name).get_by_test_id("knowledge-delete").click()
        self.page.get_by_role("button", name="确定").click()
