from playwright.sync_api import Locator, expect

from pages.base_page import BasePage


class PatientChatPage(BasePage):
    def open(self) -> None:
        self.goto("/patient/chat")

    def new_session(self, session_type: str) -> None:
        self.testid("chat-new").click()
        self.testid(f"chat-type-{session_type}").click()

    def send(self, text: str) -> None:
        self.testid("chat-input").locator("textarea").fill(text)
        self.testid("chat-send").click()

    def wait_done(self, timeout_ms: int) -> None:
        expect(self.testid("chat-message-assistant").last).not_to_have_text("", timeout=timeout_ms)
        expect(self.testid("chat-send")).to_be_visible(timeout=timeout_ms)

    def last_assistant(self) -> Locator:
        return self.testid("chat-message-assistant").last
