from playwright.sync_api import Locator, expect

from config import settings
from pages.base_page import BasePage


class PatientChatPage(BasePage):
    def open(self) -> None:
        self.goto("/patient/chat")

    def new_session(self, session_type: str) -> None:
        self.testid("chat-new").click()
        with self.page.expect_response(
            lambda r: "/chat/patient/session" in r.url and r.request.method == "POST"
        ):
            self.testid(f"chat-type-{session_type}").click()

    def send(self, text: str) -> None:
        self.testid("chat-input").fill(text)
        self.testid("chat-send").click()

    def wait_done(self, timeout_ms: int) -> None:
        expect(self.testid("chat-message-assistant").last).not_to_have_text("", timeout=timeout_ms)
        expect(self.testid("chat-send")).to_be_visible(timeout=timeout_ms)

    def last_assistant(self) -> Locator:
        return self.testid("chat-message-assistant").last

    def confirm_booking(self) -> str:
        expect(self.testid("confirm-dialog")).to_be_visible(timeout=settings.llm_timeout_ms)
        summary = self.testid("confirm-summary").inner_text()
        self.testid("confirm-ok").click()
        return summary
