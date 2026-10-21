import json
import os
from dataclasses import dataclass
from pathlib import Path

import pytest

from api_client import ApiClient, Session, gen_phone
from config import settings

BASE_DIR = Path(__file__).resolve().parent
(BASE_DIR / "reports").mkdir(exist_ok=True)
(BASE_DIR / "artifacts").mkdir(exist_ok=True)


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
def admin_page(new_context, admin_session):
    context = new_context(
        storage_state=_staff_state(admin_session),
        viewport={"width": 1440, "height": 900},
    )
    page = context.new_page()
    yield page
    context.close()


@pytest.fixture
def anon_page(new_context):
    context = new_context(viewport={"width": 1440, "height": 900})
    page = context.new_page()
    yield page
    context.close()


@dataclass
class PatientAccount:
    phone: str
    password: str
    session: Session


def _patient_state(session: Session) -> dict:
    return {
        "cookies": [],
        "origins": [{
            "origin": settings.base_url,
            "localStorage": [
                {"name": "patient_token", "value": session.token},
                {"name": "patient_refresh_token", "value": session.refresh_token},
                {"name": "patient_user", "value": json.dumps({"phone": session.user.get("phone", "")}, ensure_ascii=False)},
            ],
        }],
    }


@pytest.fixture(scope="session")
def staff1_session(api):
    if not settings.staff1_user or not settings.staff1_password:
        pytest.skip("未配置 UI_E2E_STAFF1_USER / UI_E2E_STAFF1_PASSWORD")
    return api.login_staff(settings.staff1_user, settings.staff1_password)


@pytest.fixture
def staff1_page(new_context, staff1_session):
    context = new_context(
        storage_state=_staff_state(staff1_session),
        viewport={"width": 1440, "height": 900},
    )
    page = context.new_page()
    yield page
    context.close()


@pytest.fixture
def patient(api):
    if not settings.hospital1:
        pytest.skip("未配置 UI_E2E_HOSPITAL1_NAME，无法按医院名称选择")
    phone = gen_phone()
    password = "UiE2e123"
    hospital_id = api.hospital_id(settings.hospital1)
    api.register_patient(phone, password, f"UIE2E{phone[-4:]}", hospital_id)
    session = api.login_patient(phone, password)
    return PatientAccount(phone=phone, password=password, session=session)


@pytest.fixture
def patient_page(new_context, patient):
    context = new_context(
        storage_state=_patient_state(patient.session),
        viewport={"width": 1440, "height": 900},
    )
    page = context.new_page()
    yield page
    context.close()


@pytest.fixture(scope="session")
def ai_ready():
    if os.getenv("UI_E2E_AI", "0") != "1":
        pytest.skip("设置 UI_E2E_AI=1 后才运行 AI UI 用例")


@pytest.fixture
def mobile_anon_page(new_context):
    context = new_context(
        viewport={"width": 390, "height": 844},
        is_mobile=True,
        has_touch=True,
    )
    page = context.new_page()
    yield page
    context.close()


@pytest.fixture
def mobile_patient_page(new_context, patient):
    context = new_context(
        storage_state=_patient_state(patient.session),
        viewport={"width": 390, "height": 844},
        is_mobile=True,
        has_touch=True,
    )
    page = context.new_page()
    yield page
    context.close()
