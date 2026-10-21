from playwright.sync_api import Locator

from pages.base_page import BasePage


class StaffLoginPage(BasePage):
    def open(self) -> None:
        self.goto("/login")

    def _field(self, name: str) -> Locator:
        # el-input 设置了 inheritAttrs: false，透传属性会落在内部 input 上；
        # 同时兼容 testid 加在外层容器上的写法
        return self.page.locator(f'[data-testid="{name}"] input, input[data-testid="{name}"]')

    def login(self, username: str, password: str) -> None:
        self._field("login-username").fill(username)
        self._field("login-password").fill(password)
        self.testid("login-submit").click()
