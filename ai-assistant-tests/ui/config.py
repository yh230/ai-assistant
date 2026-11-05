import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


def _shared(ui_key: str, e2e_key: str, default: str = "") -> str:
    """UI 专属变量优先；未设置时复用 API 套件的同名账号/医院配置。"""
    return os.getenv(ui_key) or os.getenv(e2e_key, default)


@dataclass(frozen=True)
class Settings:
    base_url: str = os.getenv("UI_E2E_BASE_URL", "http://localhost:5173").rstrip("/")
    api_base: str = os.getenv("UI_E2E_API_BASE", "http://localhost:8080").rstrip("/")
    admin_user: str = _shared("UI_E2E_ADMIN_USER", "E2E_ADMIN_USER", "admin")
    admin_password: str = _shared("UI_E2E_ADMIN_PASSWORD", "E2E_ADMIN_PASSWORD", "admin123")
    staff1_user: str = _shared("UI_E2E_STAFF1_USER", "E2E_STAFF1_USER")
    staff1_password: str = _shared("UI_E2E_STAFF1_PASSWORD", "E2E_STAFF1_PASSWORD")
    staff2_user: str = _shared("UI_E2E_STAFF2_USER", "E2E_STAFF2_USER")
    staff2_password: str = _shared("UI_E2E_STAFF2_PASSWORD", "E2E_STAFF2_PASSWORD")
    hospital1: str = _shared("UI_E2E_HOSPITAL1_NAME", "E2E_HOSPITAL1_NAME")
    hospital2: str = _shared("UI_E2E_HOSPITAL2_NAME", "E2E_HOSPITAL2_NAME")
    fast_timeout_ms: int = int(os.getenv("UI_E2E_FAST_TIMEOUT_MS", "15000"))
    llm_timeout_ms: int = int(os.getenv("UI_E2E_LLM_TIMEOUT_MS", "180000"))


settings = Settings()
