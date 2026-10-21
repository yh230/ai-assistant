from playwright.sync_api import Locator, Page

from config import settings


class BasePage:
    def __init__(self, page: Page):
        self.page = page

    def goto(self, path: str) -> None:
        self.page.goto(f"{settings.base_url}{path}")

    def testid(self, name: str) -> Locator:
        return self.page.get_by_test_id(name)
