from pages.base_page import BasePage


class PatientChatPage(BasePage):
    def open(self) -> None:
        self.goto("/patient/chat")
