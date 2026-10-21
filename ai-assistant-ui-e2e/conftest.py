import json
from pathlib import Path

import pytest
from playwright.sync_api import Browser

from api_client import ApiClient, Session
from config import settings

Path("reports").mkdir(exist_ok=True)
Path("artifacts").mkdir(exist_ok=True)


def _staff_state(session: Session) -> dict:
    return {
        "cookies": [],
        "origins": [{
            "origin": settings.base_url,
            "localStorage": [
                {"name": "ai_token", "value": session.token},
                {"name": "ai_refresh_token", "value": session.refresh_token},
                {"name": "ai_user", "value": json.dumps(session.user, ensure_ascii=False)},
            ],
        }],
    }


@pytest.fixture(scope="session")
def api():
    client = ApiClient(settings.api_base)
    yield client
    client.close()


@pytest.fixture(scope="session")
def admin_session(api):
    return api.login_staff(settings.admin_user, settings.admin_password)


@pytest.fixture
def admin_page(browser: Browser, admin_session):
    context = browser.new_context(
        storage_state=_staff_state(admin_session),
        viewport={"width": 1440, "height": 900},
    )
    page = context.new_page()
    yield page
    context.close()


@pytest.fixture
def anon_page(browser: Browser):
    context = browser.new_context(viewport={"width": 1440, "height": 900})
    page = context.new_page()
    yield page
    context.close()
