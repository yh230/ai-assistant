import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Settings:
    base_url: str = os.getenv("UI_E2E_BASE_URL", "http://localhost:5173").rstrip("/")
    api_base: str = os.getenv("UI_E2E_API_BASE", "http://localhost:8080").rstrip("/")
    admin_user: str = os.getenv("UI_E2E_ADMIN_USER", "admin")
    admin_password: str = os.getenv("UI_E2E_ADMIN_PASSWORD", "admin123")
    staff1_user: str = os.getenv("UI_E2E_STAFF1_USER", "")
    staff1_password: str = os.getenv("UI_E2E_STAFF1_PASSWORD", "")
    staff2_user: str = os.getenv("UI_E2E_STAFF2_USER", "")
    staff2_password: str = os.getenv("UI_E2E_STAFF2_PASSWORD", "")
    hospital1: str = os.getenv("UI_E2E_HOSPITAL1_NAME", "")
    hospital2: str = os.getenv("UI_E2E_HOSPITAL2_NAME", "")
    fast_timeout_ms: int = int(os.getenv("UI_E2E_FAST_TIMEOUT_MS", "15000"))
    llm_timeout_ms: int = int(os.getenv("UI_E2E_LLM_TIMEOUT_MS", "180000"))


settings = Settings()
