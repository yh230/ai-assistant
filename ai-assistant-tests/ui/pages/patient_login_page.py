from playwright.sync_api import Locator

from pages.base_page import BasePage


class PatientLoginPage(BasePage):
    def open(self) -> None:
        self.goto("/patient/login")

    def _field(self, name: str) -> Locator:
        # el-input 设置了 inheritAttrs: false，透传属性会落在内部 input 上；
        # 同时兼容 testid 加在外层容器上的写法
        return self.page.locator(f'[data-testid="{name}"] input, input[data-testid="{name}"]')

    def register(self, name: str, phone: str, password: str, hospital_name: str) -> None:
        self.page.get_by_role("tab", name="注册").click()
        self._field("patient-register-name").fill(name)
        self._field("patient-register-phone").fill(phone)
        self._field("patient-register-password").fill(password)
        self.testid("patient-register-hospital").click()
        self.page.get_by_role("option", name=hospital_name).click()
        self.testid("patient-register-submit").click()

    def login(self, phone: str, password: str) -> None:
        self._field("patient-phone").fill(phone)
        self._field("patient-password").fill(password)
        self.testid("patient-login-submit").click()
