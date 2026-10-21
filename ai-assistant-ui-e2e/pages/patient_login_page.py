from pages.base_page import BasePage


class PatientLoginPage(BasePage):
    def open(self) -> None:
        self.goto("/patient/login")

    def register(self, name: str, phone: str, password: str, hospital_name: str) -> None:
        self.page.get_by_role("tab", name="注册").click()
        self.testid("patient-register-name").locator("input").fill(name)
        self.testid("patient-register-phone").locator("input").fill(phone)
        self.testid("patient-register-password").locator("input").fill(password)
        self.testid("patient-register-hospital").click()
        self.page.get_by_role("option", name=hospital_name).click()
        self.testid("patient-register-submit").click()

    def login(self, phone: str, password: str) -> None:
        self.testid("patient-phone").locator("input").fill(phone)
        self.testid("patient-password").locator("input").fill(password)
        self.testid("patient-login-submit").click()
