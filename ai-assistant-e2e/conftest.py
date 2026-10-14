"""E2E 全局 fixtures。

导入期不得读取 .env、不得因缺配置报错——helpers/tests 下的纯单测不依赖环境。
"""

import os
from dataclasses import dataclass

import httpx
import pytest
from dotenv import load_dotenv

from helpers import api


@dataclass
class Account:
    username: str
    password: str
    token: str
    user_id: int = 0


def _required(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        pytest.exit(f"[E2E] 缺少配置 {name}：请复制 ai-assistant-e2e/.env.example 为 .env 并填写后重试", returncode=1)
    return value


@pytest.fixture(scope="session")
def cfg() -> dict:
    load_dotenv()
    return {
        "base_url": os.environ.get("E2E_BASE_URL", "http://localhost:8080"),
        "fast_timeout": float(os.environ.get("E2E_FAST_TIMEOUT", "20")),
        "llm_timeout": float(os.environ.get("E2E_LLM_TIMEOUT", "120")),
        "admin": (_required("E2E_ADMIN_USER"), _required("E2E_ADMIN_PASSWORD")),
        "staff1": (_required("E2E_STAFF1_USER"), _required("E2E_STAFF1_PASSWORD")),
        "staff2": (_required("E2E_STAFF2_USER"), _required("E2E_STAFF2_PASSWORD")),
        "hospital1": _required("E2E_HOSPITAL1_NAME"),
        "hospital2": _required("E2E_HOSPITAL2_NAME"),
    }


@pytest.fixture(scope="session")
def client(cfg):
    with api.make_client(cfg["base_url"], cfg["fast_timeout"], cfg["llm_timeout"]) as c:
        yield c


def _login(client: httpx.Client, cfg: dict, key: str) -> Account:
    username, password = cfg[key]
    token = api.login_staff(client, username, password)
    info = api.get_info(client, token)
    return Account(username=username, password=password, token=token, user_id=info["userId"])


@pytest.fixture(scope="session")
def admin(client, cfg) -> Account:
    return _login(client, cfg, "admin")


@pytest.fixture(scope="session")
def staff1(client, cfg) -> Account:
    return _login(client, cfg, "staff1")


@pytest.fixture(scope="session")
def staff2(client, cfg) -> Account:
    return _login(client, cfg, "staff2")


@pytest.fixture(scope="session")
def hospitals(client) -> dict:
    return {h["name"]: h["id"] for h in api.public_hospitals(client)}


def _department_names(client, token: str, hospitals: dict, hospital_name: str) -> set[str]:
    hid = hospitals.get(hospital_name)
    assert hid, f"公开医院列表缺少「{hospital_name}」，请核对 .env 与 hospital_db 数据"
    return {d["name"] for d in api.departments(client, token, hid)}


@pytest.fixture(scope="session")
def departments1(client, staff1, hospitals, cfg) -> set[str]:
    return _department_names(client, staff1.token, hospitals, cfg["hospital1"])


@pytest.fixture(scope="session")
def departments2(client, staff2, hospitals, cfg) -> set[str]:
    return _department_names(client, staff2.token, hospitals, cfg["hospital2"])


@pytest.fixture(scope="session")
def new_patient(client, hospitals):
    """工厂：注册并登录一个绑定指定医院的新患者（手机号随机唯一）"""

    def _create(hospital_name: str) -> Account:
        hid = hospitals.get(hospital_name)
        assert hid, f"公开医院列表缺少「{hospital_name}」"
        phone = api.gen_phone()
        password = "E2eTest123"
        user_id = api.register_patient(client, phone, password, f"E2E患者{phone[-4:]}", hid)
        token = api.login_patient(client, phone, password)
        return Account(username=phone, password=password, token=token, user_id=user_id)

    return _create
