# UI 自动化测试项目实施计划（Playwright + pytest）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `ai-assistant-ui-e2e/`，用 Playwright + pytest 覆盖 6 条关键 UI 用户旅程，并接入截图/trace/视频失败诊断。

**Architecture:** 独立测试项目，通过网关 API 造数与清理，通过浏览器断言页面渲染、路由守卫、Element Plus 交互和窄屏布局；前端只增加 `data-testid`，不改变业务行为。

**Tech Stack:** Python 3.10+、pytest、pytest-playwright、Playwright Chromium、httpx、python-dotenv、pytest-timeout。

**Spec:** `docs/design/ui-e2e-design.md`

## Global Constraints

- 只跑 Chromium；扫码/短信登录放 P1，不进入 MVP。
- 不直连数据库；造数与清理只走网关 API。
- 复用现有 API E2E 的两院账号；UI 项目用自己的 `UI_E2E_*` 环境变量承接。
- 预约测试数据允许残留在 dev 库；用例只断言本次创建的记录，不依赖全局总数。
- 当前没有 nightly 环境，`DEEPSEEK_API_KEY` 配置项留空；AI 用例默认 skip。
- 暂不引入 Docker Compose / lite profile；测试假设完整栈已手工启动。
- 除 `data-testid` 外不修改生产业务逻辑。
- 所有测试串行执行，不启用 pytest-xdist。
- `.env`、`reports/`、`artifacts/`、`.venv/` 不入库。

---

### Task 1: 项目骨架 + 员工登录冒烟

**Files:**
- Create: `ai-assistant-ui-e2e/requirements.txt`
- Create: `ai-assistant-ui-e2e/pytest.ini`
- Create: `ai-assistant-ui-e2e/.env.example`
- Create: `ai-assistant-ui-e2e/config.py`
- Create: `ai-assistant-ui-e2e/api_client.py`
- Create: `ai-assistant-ui-e2e/conftest.py`
- Create: `ai-assistant-ui-e2e/pages/__init__.py`
- Create: `ai-assistant-ui-e2e/pages/base_page.py`
- Create: `ai-assistant-ui-e2e/pages/staff_login_page.py`
- Create: `ai-assistant-ui-e2e/tests/test_01_auth.py`
- Modify: `ai-assistant-frontend/src/views/LoginView.vue`
- Modify: `ai-assistant-frontend/src/layout/MainLayout.vue`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: 网关 `http://localhost:8080`，前端 `http://localhost:5173`。
- Produces: `settings`、`ApiClient`、`Session`、`BasePage`、`StaffLoginPage`、`admin_page` fixture、`anon_page` fixture。

- [ ] **Step 1: 写失败测试**

创建 `ai-assistant-ui-e2e/tests/test_01_auth.py`：

```python
import re

from playwright.sync_api import expect

from config import settings
from pages.staff_login_page import StaffLoginPage


def test_staff_login_success(admin_page):
    login = StaffLoginPage(admin_page)
    login.open()
    login.login(settings.admin_user, settings.admin_password)
    expect(admin_page).to_have_url(re.compile(r"/chat$"))
    expect(admin_page.get_by_test_id("app-menu")).to_be_visible()
```

- [ ] **Step 2: 运行测试，确认失败**

Run:

```bash
cd ai-assistant-ui-e2e
python -m pytest tests/test_01_auth.py -v
```

Expected: FAIL，提示缺少 `config` 模块或 `data-testid` 元素。

- [ ] **Step 3: 创建项目骨架**

`requirements.txt`：

```text
pytest>=8.0
pytest-playwright>=0.6
pytest-timeout>=2.3
playwright>=1.50
httpx>=0.27
python-dotenv>=1.0
```

`pytest.ini`：

```ini
[pytest]
markers =
    ui: UI 自动化用例
    ai: 依赖大模型输出的用例（默认跳过；设置 UI_E2E_AI=1 后运行）
    mobile: 移动端视口用例
    slow: 长等待用例
addopts = --browser chromium --tracing retain-on-failure --screenshot only-on-failure --video retain-on-failure --output artifacts --junitxml reports/ui-e2e-results.xml -m "not ai and not slow" -ra --strict-markers --tb=short
pythonpath = .
timeout = 180
```

`.env.example`：

```text
UI_E2E_BASE_URL=http://localhost:5173
UI_E2E_API_BASE=http://localhost:8080

# 复用现有 API E2E 的两院账号
UI_E2E_ADMIN_USER=admin
UI_E2E_ADMIN_PASSWORD=admin123
UI_E2E_STAFF1_USER=
UI_E2E_STAFF1_PASSWORD=
UI_E2E_STAFF2_USER=
UI_E2E_STAFF2_PASSWORD=

UI_E2E_HOSPITAL1_NAME=
UI_E2E_HOSPITAL2_NAME=

# 1=允许运行 AI UI 用例；0=默认跳过
UI_E2E_AI=0
UI_E2E_FAST_TIMEOUT_MS=15000
UI_E2E_LLM_TIMEOUT_MS=180000
```

`config.py`：

```python
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
```

`api_client.py`：

```python
import random
from dataclasses import dataclass

import httpx


@dataclass
class Session:
    token: str
    refresh_token: str
    user: dict


def gen_phone() -> str:
    return f"13{random.randint(0, 999_999_999):09d}"


class ApiClient:
    def __init__(self, base_url: str, timeout: float = 20):
        self.client = httpx.Client(
            base_url=base_url.rstrip("/"),
            timeout=httpx.Timeout(timeout, read=180),
        )

    def close(self) -> None:
        self.client.close()

    @staticmethod
    def _auth(token: str) -> dict:
        return {"Authorization": f"Bearer {token}"}

    def _unwrap(self, response: httpx.Response):
        try:
            body = response.json()
        except ValueError:
            raise AssertionError(
                f"{response.request.method} {response.request.url} -> "
                f"{response.status_code}: {response.text[:300]}"
            ) from None
        if response.status_code >= 400 or not isinstance(body, dict) or body.get("code") != 200:
            raise AssertionError(
                f"{response.request.method} {response.request.url} -> "
                f"{response.status_code}: {body}"
            )
        return body.get("data")

    def login_staff(self, username: str, password: str) -> Session:
        data = self._unwrap(self.client.post("/api/login", json={
            "username": username,
            "password": password,
        }))
        token = data["token"]
        refresh = data.get("refreshToken", "")
        user = self._unwrap(self.client.get("/api/getInfo", headers=self._auth(token)))
        return Session(token=token, refresh_token=refresh, user=user)

    def login_patient(self, phone: str, password: str) -> Session:
        data = self._unwrap(self.client.post("/api/patient/login", json={
            "username": phone,
            "password": password,
        }))
        token = data["token"]
        refresh = data.get("refreshToken", "")
        # 患者令牌不能访问员工路径 /api/getInfo；前端本身也只缓存 phone
        return Session(token=token, refresh_token=refresh, user={"phone": phone})

    def register_patient(self, phone: str, password: str, name: str, hospital_id: int):
        return self._unwrap(self.client.post("/api/patient/register", json={
            "phone": phone,
            "password": password,
            "name": name,
            "hospitalId": hospital_id,
        }))

    def public_hospitals(self) -> list[dict]:
        return self._unwrap(self.client.get("/api/hospital/public/list"))

    def hospital_id(self, name: str) -> int:
        for hospital in self.public_hospitals():
            if hospital["name"] == name:
                return hospital["id"]
        raise AssertionError(f"公开医院列表缺少「{name}」")
```

`conftest.py`：

```python
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
```

`pages/__init__.py` 为空文件。

`pages/base_page.py`：

```python
from playwright.sync_api import Locator, Page

from config import settings


class BasePage:
    def __init__(self, page: Page):
        self.page = page

    def goto(self, path: str) -> None:
        self.page.goto(f"{settings.base_url}{path}")

    def testid(self, name: str) -> Locator:
        return self.page.get_by_test_id(name)
```

`pages/staff_login_page.py`：

```python
from pages.base_page import BasePage


class StaffLoginPage(BasePage):
    def open(self) -> None:
        self.goto("/login")

    def login(self, username: str, password: str) -> None:
        self.testid("login-username").locator("input").fill(username)
        self.testid("login-password").locator("input").fill(password)
        self.testid("login-submit").click()
```

- [ ] **Step 4: 给前端加测试钩子**

`ai-assistant-frontend/src/views/LoginView.vue`：

```html
<el-input
  v-model="form.username"
  type="text"
  placeholder="请输入账号"
  autocomplete="username"
  data-testid="login-username"
/>
```

```html
<el-input
  v-model="form.password"
  type="password"
  placeholder="请输入密码"
  autocomplete="current-password"
  data-testid="login-password"
/>
```

```html
<div class="form-error" v-if="errorMsg" data-testid="login-error">{{ errorMsg }}</div>
```

```html
<el-button
  class="login-btn"
  type="primary"
  native-type="submit"
  :loading="loading"
  data-testid="login-submit"
>
```

`ai-assistant-frontend/src/layout/MainLayout.vue`：

```html
<el-menu class="sidebar-nav" :default-active="route.path" router data-testid="app-menu">
```

`.gitignore` 追加：

```text
# UI E2E
ai-assistant-ui-e2e/.venv/
ai-assistant-ui-e2e/.env
ai-assistant-ui-e2e/reports/
ai-assistant-ui-e2e/artifacts/
ai-assistant-ui-e2e/.pytest_cache/
```

- [ ] **Step 5: 安装依赖并运行测试**

Run:

```bash
cd ai-assistant-ui-e2e
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe -m playwright install chromium
.venv/Scripts/python.exe -m pytest tests/test_01_auth.py -v
```

Windows 用 `.venv/Scripts/...`，macOS/Linux 用 `.venv/bin/...`。

Expected: PASS（前提：前后端已启动、admin 账号可登录）。

- [ ] **Step 6: 提交**

```bash
git add ai-assistant-ui-e2e ai-assistant-frontend/src/views/LoginView.vue ai-assistant-frontend/src/layout/MainLayout.vue .gitignore
git commit -m "test(ui-e2e): 搭建 Playwright 项目并覆盖员工登录"
```

---

### Task 2: 员工菜单权限

**Files:**
- Modify: `ai-assistant-ui-e2e/conftest.py`
- Create: `ai-assistant-ui-e2e/tests/test_02_permissions.py`

**Interfaces:**
- Consumes: `admin_page`、`settings`、`app-menu` testid。
- Produces: `staff1_session`、`staff1_page` fixtures。

- [ ] **Step 1: 写失败测试**

`tests/test_02_permissions.py`：

```python
import pytest
from playwright.sync_api import expect

from config import settings


def test_admin_sees_system_management(admin_page):
    admin_page.goto(f"{settings.base_url}/chat")
    expect(admin_page.get_by_test_id("app-menu")).to_be_visible()
    expect(admin_page.get_by_text("系统管理", exact=True)).to_be_visible()


def test_staff1_does_not_see_system_management(staff1_page):
    staff1_page.goto(f"{settings.base_url}/chat")
    expect(staff1_page.get_by_test_id("app-menu")).to_be_visible()
    expect(staff1_page.get_by_text("系统管理", exact=True)).to_have_count(0)
    expect(staff1_page.get_by_text("用户管理", exact=True)).to_have_count(0)
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest tests/test_02_permissions.py -v`

Expected: FAIL，缺少 `staff1_page` fixture。

- [ ] **Step 3: 增加 staff1 fixtures**

`conftest.py` 追加：

```python
@pytest.fixture(scope="session")
def staff1_session(api):
    if not settings.staff1_user or not settings.staff1_password:
        pytest.skip("未配置 UI_E2E_STAFF1_USER / UI_E2E_STAFF1_PASSWORD")
    return api.login_staff(settings.staff1_user, settings.staff1_password)


@pytest.fixture
def staff1_page(browser: Browser, staff1_session):
    context = browser.new_context(
        storage_state=_staff_state(staff1_session),
        viewport={"width": 1440, "height": 900},
    )
    page = context.new_page()
    yield page
    context.close()
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest tests/test_02_permissions.py -v`

Expected: PASS。若 staff1 实际拥有系统管理权限，调整测试账号或断言，不要放宽到无意义断言。

- [ ] **Step 5: 提交**

```bash
git add ai-assistant-ui-e2e/conftest.py ai-assistant-ui-e2e/tests/test_02_permissions.py
git commit -m "test(ui-e2e): 覆盖员工菜单权限"
```

---

### Task 3: 知识库上传 / 列表 / 删除

**Files:**
- Modify: `ai-assistant-frontend/src/views/knowledge/KnowledgeManageView.vue`
- Create: `ai-assistant-ui-e2e/pages/knowledge_page.py`
- Modify: `ai-assistant-ui-e2e/api_client.py`
- Create: `ai-assistant-ui-e2e/tests/test_03_knowledge.py`

**Interfaces:**
- Consumes: `admin_page`、`admin_session`、`api`。
- Produces: `KnowledgePage`、`ApiClient.list_docs/upload_doc/delete_doc/delete_doc_by_name`。

- [ ] **Step 1: 写失败测试**

`tests/test_03_knowledge.py`：

```python
import uuid

from playwright.sync_api import expect

from pages.knowledge_page import KnowledgePage


def test_upload_list_delete(admin_page, api, admin_session, tmp_path):
    name = f"ui-e2e-{uuid.uuid4().hex[:8]}.md"
    file_path = tmp_path / name
    file_path.write_text("# UI E2E\n\n用于知识库 UI 自动化测试。\n", encoding="utf-8")

    page = KnowledgePage(admin_page)
    try:
        page.open()
        page.upload(file_path)
        expect(page.row(name)).to_be_visible(timeout=15000)

        page.delete(name)
        expect(page.row(name)).to_have_count(0, timeout=15000)
    finally:
        api.delete_doc_by_name(admin_session.token, name)
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest tests/test_03_knowledge.py -v`

Expected: FAIL，缺少 `KnowledgePage` 或 `knowledge-file-input`。

- [ ] **Step 3: 前端加测试钩子**

`KnowledgeManageView.vue`：

```html
<el-button type="primary" :disabled="!selectedFile || uploading" @click="doUpload" data-testid="knowledge-upload">
```

```html
<input ref="fileInputRef" type="file" accept=".md,.txt" class="hidden-input" data-testid="knowledge-file-input" @change="onFileChange" />
```

```html
<el-table v-if="list.length > 0" :data="list" stripe data-testid="knowledge-table">
```

```html
<el-button link type="danger" @click="removeDoc(row)" data-testid="knowledge-delete">删除</el-button>
```

```html
<div v-else class="empty-tip" data-testid="knowledge-empty">{{ loading ? '加载中…' : '暂无知识库文档' }}</div>
```

- [ ] **Step 4: 实现 Page Object 与 API 方法**

`pages/knowledge_page.py`：

```python
from pathlib import Path

from playwright.sync_api import Locator

from pages.base_page import BasePage


class KnowledgePage(BasePage):
    def open(self) -> None:
        self.goto("/admin/knowledge")

    def upload(self, file_path: Path) -> None:
        self.testid("knowledge-file-input").set_input_files(str(file_path))
        self.testid("knowledge-upload").click()

    def row(self, name: str) -> Locator:
        return self.page.locator("tr", has_text=name)

    def delete(self, name: str) -> None:
        self.row(name).get_by_test_id("knowledge-delete").click()
        self.page.get_by_role("button", name="确定").click()
```

`api_client.py` 追加：

```python
    def list_docs(self, token: str, page_size: int = 100) -> dict:
        return self._unwrap(self.client.get(
            "/api/chat/admin/knowledge",
            params={"pageNum": 1, "pageSize": page_size},
            headers=self._auth(token),
        ))

    def upload_doc(self, token: str, filename: str, content: bytes):
        resp = self.client.post(
            "/api/chat/admin/knowledge/upload",
            files={"file": (filename, content, "text/markdown")},
            headers=self._auth(token),
        )
        return self._unwrap(resp)

    def delete_doc(self, token: str, doc_id: int) -> None:
        self._unwrap(self.client.delete(
            f"/api/chat/admin/knowledge/{doc_id}",
            headers=self._auth(token),
        ))

    def delete_doc_by_name(self, token: str, filename: str) -> None:
        docs = self.list_docs(token)
        for doc in docs.get("list", []):
            if doc.get("name") == filename:
                try:
                    self.delete_doc(token, doc["id"])
                except Exception:
                    pass
                return
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest tests/test_03_knowledge.py -v`

Expected: PASS。若上传后异步处理导致列表刷新延迟，保留 15s 超时，不要用固定 sleep。

- [ ] **Step 6: 提交**

```bash
git add ai-assistant-ui-e2e ai-assistant-frontend/src/views/knowledge/KnowledgeManageView.vue
git commit -m "test(ui-e2e): 覆盖知识库上传与删除"
```

---

### Task 4: 患者注册 / 登录 / 对话页

**Files:**
- Modify: `ai-assistant-frontend/src/views/patient/PatientLoginView.vue`
- Modify: `ai-assistant-frontend/src/views/patient/PatientChatView.vue`
- Create: `ai-assistant-ui-e2e/pages/patient_login_page.py`
- Create: `ai-assistant-ui-e2e/pages/patient_chat_page.py`
- Modify: `ai-assistant-ui-e2e/conftest.py`
- Create: `ai-assistant-ui-e2e/tests/test_04_patient.py`

**Interfaces:**
- Consumes: `anon_page`、`api`、`settings`。
- Produces: `PatientLoginPage`、`PatientChatPage`、`patient` / `patient_page` fixtures。

- [ ] **Step 1: 写失败测试**

`tests/test_04_patient.py`：

```python
import re

from playwright.sync_api import expect

from api_client import gen_phone
from config import settings
from pages.patient_login_page import PatientLoginPage


def test_patient_register_then_login(anon_page):
    phone = gen_phone()
    page = PatientLoginPage(anon_page)
    page.open()
    page.register("UIE2E患者", phone, "UiE2e123", settings.hospital1)
    page.login(phone, "UiE2e123")
    expect(anon_page).to_have_url(re.compile(r"/patient/chat"))
    expect(anon_page.get_by_test_id("patient-chat")).to_be_visible()
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest tests/test_04_patient.py -v`

Expected: FAIL，缺少 `PatientLoginPage` 或 testid。

- [ ] **Step 3: 前端加测试钩子**

`PatientLoginView.vue` 登录表单：

```html
<el-input v-model="loginForm.phone" type="text" placeholder="请输入手机号" autocomplete="username" data-testid="patient-phone" />
```

```html
<el-input v-model="loginForm.password" type="password" placeholder="请输入密码" autocomplete="current-password" data-testid="patient-password" />
```

```html
<div class="form-error" v-if="errorMsg" data-testid="patient-error">{{ errorMsg }}</div>
```

```html
<el-button class="login-btn" type="primary" native-type="submit" :loading="loading" data-testid="patient-login-submit">
```

注册表单：

```html
<el-input v-model="registerForm.name" type="text" placeholder="请输入姓名" data-testid="patient-register-name" />
```

```html
<el-input v-model="registerForm.phone" type="text" placeholder="请输入手机号" autocomplete="username" data-testid="patient-register-phone" />
```

```html
<el-input v-model="registerForm.password" type="password" placeholder="6-32 位密码" autocomplete="new-password" data-testid="patient-register-password" />
```

```html
<el-select v-model="registerForm.hospitalId" class="hospital-select" :disabled="hospitalsLoading" :placeholder="hospitalsLoading ? '医院加载中…' : '请选择医院'" data-testid="patient-register-hospital">
```

```html
<el-button class="login-btn" type="primary" native-type="submit" :loading="loading" data-testid="patient-register-submit">
```

`PatientChatView.vue` 根节点与会话列表：

```html
<div class="patient-chat" data-testid="patient-chat">
```

```html
<div class="session-list" data-testid="chat-session-list">
```

- [ ] **Step 4: 实现 Page Object 与患者 fixtures**

`pages/patient_login_page.py`：

```python
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
```

`pages/patient_chat_page.py`：

```python
from pages.base_page import BasePage


class PatientChatPage(BasePage):
    def open(self) -> None:
        self.goto("/patient/chat")
```

`conftest.py` 追加：

```python
from dataclasses import dataclass


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


@pytest.fixture
def patient(api):
    phone = gen_phone()
    password = "UiE2e123"
    hospital_id = api.hospital_id(settings.hospital1)
    api.register_patient(phone, password, f"UIE2E{phone[-4:]}", hospital_id)
    session = api.login_patient(phone, password)
    return PatientAccount(phone=phone, password=password, session=session)


@pytest.fixture
def patient_page(browser: Browser, patient):
    context = browser.new_context(
        storage_state=_patient_state(patient.session),
        viewport={"width": 1440, "height": 900},
    )
    page = context.new_page()
    yield page
    context.close()
```

`conftest.py` 顶部 import 改为：

```python
from api_client import ApiClient, Session, gen_phone
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest tests/test_04_patient.py -v`

Expected: PASS。注册后页面会切回登录 tab，手机号已预填；测试会重新填写手机号与密码。

- [ ] **Step 6: 提交**

```bash
git add ai-assistant-ui-e2e ai-assistant-frontend/src/views/patient/PatientLoginView.vue ai-assistant-frontend/src/views/patient/PatientChatView.vue
git commit -m "test(ui-e2e): 覆盖患者注册登录与对话页"
```

---

### Task 5: AI 导诊 SSE 结构断言

**Files:**
- Modify: `ai-assistant-frontend/src/views/patient/PatientChatView.vue`
- Modify: `ai-assistant-ui-e2e/conftest.py`
- Modify: `ai-assistant-ui-e2e/api_client.py`
- Modify: `ai-assistant-ui-e2e/pages/patient_chat_page.py`
- Create: `ai-assistant-ui-e2e/tests/test_05_triage.py`

**Interfaces:**
- Consumes: `patient_page`、`staff1_session`、`api.departments`。
- Produces: `ai_ready` fixture、`PatientChatPage.new_session/send/wait_done/last_assistant`、`ApiClient.departments`。

- [ ] **Step 1: 写失败测试**

`tests/test_05_triage.py`：

```python
import pytest

from config import settings
from pages.patient_chat_page import PatientChatPage

DISCLAIMER = "AI内容仅供参考，不能替代执业医师诊断"


@pytest.mark.ai
def test_triage_stream_and_disclaimer(patient_page, api, staff1_session, ai_ready):
    page = PatientChatPage(patient_page)
    page.open()
    page.new_session("TRIAGE")
    page.send("头痛三天，想挂神经内科")
    page.wait_done(settings.llm_timeout_ms)

    text = page.last_assistant().inner_text()
    assert DISCLAIMER in text

    departments = [
        d["name"] for d in api.departments(
            staff1_session.token,
            api.hospital_id(settings.hospital1),
        )
    ]
    assert any(name in text for name in departments), f"回答未包含本院真实科室：{text[-200:]}"
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest tests/test_05_triage.py -v`

Expected: FAIL，缺少 `ai_ready` 或 chat testid。

- [ ] **Step 3: 前端加对话测试钩子**

`PatientChatView.vue`：

```html
<el-button type="primary" class="new-chat-btn" :icon="Plus" @click="toggleTypeMenu" data-testid="chat-new">
```

```html
<div v-for="t in SESSION_TYPES" :key="t.value" class="type-item" :data-testid="'chat-type-' + t.value" @click="createSession(t.value)">
```

消息节点：

```html
<div v-else class="message-item" :class="msg.role" :data-testid="'chat-message-' + msg.role">
```

```html
<span v-if="msg.streaming" class="streaming-cursor" data-testid="chat-streaming-cursor"></span>
```

输入区：

```html
<el-input
  ref="inputRef"
  v-model="inputText"
  type="textarea"
  class="input-textarea"
  :autosize="{ minRows: 1, maxRows: 6 }"
  placeholder="输入消息，按 Enter 发送，Shift+Enter 换行..."
  data-testid="chat-input"
  @keydown="handleKeydown"
/>
```

```html
<el-button v-if="loading" type="danger" class="stop-btn" title="停止生成" :icon="VideoPause" @click="stopGeneration" data-testid="chat-stop">停止</el-button>
```

```html
<el-button v-else type="primary" class="send-btn" :disabled="!inputText.trim()" :icon="Position" @click="sendMessage" data-testid="chat-send" />
```

- [ ] **Step 4: 实现 Page 方法与 AI fixture**

`pages/patient_chat_page.py` 改为：

```python
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
```

`conftest.py` 追加：

```python
import os


@pytest.fixture(scope="session")
def ai_ready():
    if os.getenv("UI_E2E_AI", "0") != "1":
        pytest.skip("设置 UI_E2E_AI=1 后才运行 AI UI 用例")
```

`api_client.py` 追加：

```python
    def departments(self, token: str, hospital_id: int) -> list[dict]:
        return self._unwrap(self.client.get(
            "/api/hospital/department/list",
            params={"hospitalId": hospital_id},
            headers=self._auth(token),
        ))
```

- [ ] **Step 5: 运行测试**

Run:

```bash
cd ai-assistant-ui-e2e
UI_E2E_AI=1 .venv/Scripts/python.exe -m pytest tests/test_05_triage.py -m ai -v
```

Expected: PASS（需要模型可用）。没有模型时，不设置 `UI_E2E_AI=1`，该用例应 skip。

- [ ] **Step 6: 提交**

```bash
git add ai-assistant-ui-e2e ai-assistant-frontend/src/views/patient/PatientChatView.vue
git commit -m "test(ui-e2e): 覆盖 AI 导诊流式渲染与免责"
```

---

### Task 6: AI 挂号确认 + API 记录断言

**Files:**
- Modify: `ai-assistant-frontend/src/views/patient/PatientChatView.vue`
- Modify: `ai-assistant-ui-e2e/pages/patient_chat_page.py`
- Modify: `ai-assistant-ui-e2e/api_client.py`
- Create: `ai-assistant-ui-e2e/tests/test_06_booking.py`

**Interfaces:**
- Consumes: `patient_page`、`patient`、`staff1_session`、`api.registration_records`。
- Produces: `PatientChatPage.confirm_dialog_summary/confirm_booking`、`ApiClient.registration_records`。

- [ ] **Step 1: 写失败测试**

`tests/test_06_booking.py`：

```python
import time

import pytest

from config import settings
from pages.patient_chat_page import PatientChatPage


@pytest.mark.ai
def test_booking_requires_confirm_and_creates_record(
    patient_page, patient, api, staff1_session, ai_ready
):
    page = PatientChatPage(patient_page)
    page.open()
    page.new_session("TRIAGE")
    page.send("头痛三天，想挂神经内科")
    page.wait_done(settings.llm_timeout_ms)

    page.send("帮我挂明天上午的号")
    summary = page.confirm_booking()
    assert any(k in summary for k in ("上午", "下午")), f"确认摘要未见时段：{summary}"

    page.wait_done(settings.llm_timeout_ms)

    records = {}
    for _ in range(30):
        records = api.registration_records(staff1_session.token, patient.phone)
        if records.get("total", 0) >= 1:
            break
        time.sleep(1)
    assert records.get("total", 0) >= 1, f"未查到本次预约记录：{records}"
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest tests/test_06_booking.py -v`

Expected: FAIL，缺少 `confirm_booking` 或确认弹窗 testid。

- [ ] **Step 3: 前端加确认弹窗测试钩子**

`PatientChatView.vue`：

```html
<el-dialog
  v-model="pendingConfirm"
  title="操作确认"
  width="420px"
  class="confirm-dialog"
  :close-on-click-modal="false"
  :close-on-press-escape="false"
  :show-close="false"
  data-testid="confirm-dialog"
>
```

```html
<p class="confirm-summary" data-testid="confirm-summary">{{ pendingConfirm && pendingConfirm.summary }}</p>
```

```html
<el-button :disabled="confirmLoading" @click="cancelConfirm" data-testid="confirm-cancel">取消</el-button>
<el-button type="primary" :disabled="confirmLoading" @click="submitConfirm" data-testid="confirm-ok">确认</el-button>
```

- [ ] **Step 4: 实现确认方法与 API 方法**

`pages/patient_chat_page.py` 追加（顶部补 `from config import settings`）：

```python
    def confirm_booking(self) -> str:
        expect(self.testid("confirm-dialog")).to_be_visible(timeout=settings.llm_timeout_ms)
        summary = self.testid("confirm-summary").inner_text()
        self.testid("confirm-ok").click()
        return summary
```

`api_client.py` 追加：

```python
    def registration_records(self, token: str, patient_phone: str) -> dict:
        return self._unwrap(self.client.get(
            "/api/registration/records",
            params={"patientPhone": patient_phone, "pageNum": 1, "pageSize": 50},
            headers=self._auth(token),
        ))
```

- [ ] **Step 5: 运行测试**

Run:

```bash
cd ai-assistant-ui-e2e
UI_E2E_AI=1 .venv/Scripts/python.exe -m pytest tests/test_06_booking.py -m ai -v
```

Expected: PASS（需要模型与 T+1 可约号源）。若模型未触发预约工具，测试失败并输出确认摘要，不用固定问答掩盖。

- [ ] **Step 6: 提交**

```bash
git add ai-assistant-ui-e2e ai-assistant-frontend/src/views/patient/PatientChatView.vue
git commit -m "test(ui-e2e): 覆盖挂号确认与记录校验"
```

---

### Task 7: 患者端 390px 窄屏

**Files:**
- Modify: `ai-assistant-ui-e2e/conftest.py`
- Create: `ai-assistant-ui-e2e/tests/test_07_mobile.py`

**Interfaces:**
- Consumes: `settings`、`patient`、`patient-chat` testid。
- Produces: `mobile_anon_page`、`mobile_patient_page` fixtures。

- [ ] **Step 1: 写失败测试**

`tests/test_07_mobile.py`：

```python
from playwright.sync_api import expect

from config import settings


def test_patient_login_no_horizontal_overflow(mobile_anon_page):
    mobile_anon_page.goto(f"{settings.base_url}/patient/login")
    assert mobile_anon_page.evaluate("window.innerWidth") == 390
    assert mobile_anon_page.evaluate("document.documentElement.scrollWidth") <= 390


def test_patient_chat_no_horizontal_overflow(mobile_patient_page):
    mobile_patient_page.goto(f"{settings.base_url}/patient/chat")
    expect(mobile_patient_page.get_by_test_id("patient-chat")).to_be_visible()
    assert mobile_patient_page.evaluate("window.innerWidth") == 390
    assert mobile_patient_page.evaluate("document.documentElement.scrollWidth") <= 390
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest tests/test_07_mobile.py -v`

Expected: FAIL，缺少 mobile fixtures。

- [ ] **Step 3: 增加移动端 fixtures**

`conftest.py` 追加：

```python
@pytest.fixture
def mobile_anon_page(browser: Browser):
    context = browser.new_context(
        viewport={"width": 390, "height": 844},
        is_mobile=True,
        has_touch=True,
    )
    page = context.new_page()
    yield page
    context.close()


@pytest.fixture
def mobile_patient_page(browser: Browser, patient):
    context = browser.new_context(
        storage_state=_patient_state(patient.session),
        viewport={"width": 390, "height": 844},
        is_mobile=True,
        has_touch=True,
    )
    page = context.new_page()
    yield page
    context.close()
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest tests/test_07_mobile.py -v`

Expected: PASS。若失败，按实际 DOM 修复前端布局，不要用 `overflow-x: hidden` 遮盖。

- [ ] **Step 5: 提交**

```bash
git add ai-assistant-ui-e2e/conftest.py ai-assistant-ui-e2e/tests/test_07_mobile.py
git commit -m "test(ui-e2e): 覆盖患者端 390px 布局"
```

---

### Task 8: preflight、README 与运行说明

**Files:**
- Create: `ai-assistant-ui-e2e/preflight.py`
- Create: `ai-assistant-ui-e2e/README.md`
- Modify: `ai-assistant-ui-e2e/api_client.py`
- Modify: `ai-assistant-ui-e2e/.env.example`（如 Task 1 未包含则补齐）
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `settings`、`ApiClient`。
- Produces: `python preflight.py` 命令与项目运行文档。

- [ ] **Step 1: 写 preflight 脚本**

`api_client.py` 追加：

```python
    def public_schedules(self, date: str | None = None) -> list[dict]:
        params = {"date": date} if date else {}
        return self._unwrap(self.client.get("/api/registration/schedule/public/schedules", params=params))
```

`preflight.py`：

```python
import sys
from datetime import date, timedelta

import httpx

from api_client import ApiClient
from config import settings


def main() -> int:
    results: list[tuple[str, bool, str]] = []

    def check(name: str, fn) -> None:
        try:
            results.append((name, True, str(fn())))
        except Exception as ex:
            results.append((name, False, f"{type(ex).__name__}: {ex}"))

    def http_ok(url: str) -> str:
        resp = httpx.get(url, timeout=10)
        if resp.status_code != 200:
            raise RuntimeError(f"HTTP {resp.status_code}")
        return url

    api = ApiClient(settings.api_base)
    try:
        check("前端", lambda: http_ok(settings.base_url))
        check("网关健康检查", lambda: http_ok(f"{settings.api_base}/api/health"))

        admin = api.login_staff(settings.admin_user, settings.admin_password)
        check("admin 登录", lambda: admin.user.get("userId", "OK"))

        hospitals = {h["name"]: h["id"] for h in api.public_hospitals()}
        check("两家演示医院", lambda: (
            hospitals[settings.hospital1],
            hospitals[settings.hospital2],
        ))

        hid = hospitals[settings.hospital1]
        departments = api.departments(admin.token, hid)
        check("医院 1 科室", lambda: f"{len(departments)} 个")

        tomorrow = (date.today() + timedelta(days=1)).isoformat()
        schedules = api.public_schedules(tomorrow)
        bookable = [s for s in schedules if (s.get("remaining") or 0) > 0]
        check("明日可约排班", lambda: f"{len(bookable)} 条")
    finally:
        api.close()

    for name, ok, detail in results:
        print(f"[{'OK  ' if ok else 'FAIL'}] {name}：{detail}")

    failed = [r for r in results if not r[1]]
    if failed:
        print(f"\n[UI E2E] 前置检查失败 {len(failed)}/{len(results)} 项，请先修复环境。")
        return 1
    print(f"\n[UI E2E] 前置检查通过 {len(results)}/{len(results)} 项。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: 写 README**

`README.md` 至少包含以下内容：

```markdown
# ai-assistant-ui-e2e

AI 医院助手 UI 自动化测试（Playwright + pytest），覆盖登录、菜单权限、患者注册、AI 导诊、挂号确认、知识库与 390px 窄屏。

## 前置条件

- 前端已启动：`http://localhost:5173`
- 后端网关已启动：`http://localhost:8080`
- 两家演示医院、T+1 可约排班已存在
- 复用 `ai-assistant-e2e/.env` 的两院账号，填入本目录 `.env`

## 安装

```bash
cd ai-assistant-ui-e2e
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe -m playwright install chromium
cp .env.example .env
```

macOS/Linux 使用 `.venv/bin/python`。

## 运行

```bash
# 确定性套件（默认跳过 ai/slow）
.venv/Scripts/python.exe -m pytest

# 只跑移动端
.venv/Scripts/python.exe -m pytest -m mobile

# AI 用例（需要后端模型可用）
UI_E2E_AI=1 .venv/Scripts/python.exe -m pytest -m ai
```

## 失败诊断

- JUnit：`reports/ui-e2e-results.xml`
- trace / 截图 / 视频：`artifacts/`

## 已知限制

- 只跑 Chromium；扫码/短信登录未纳入 MVP。
- 预约测试数据会残留在 dev 库，用例只断言本次创建的记录。
- 当前没有 nightly 环境，AI 用例需手工设置 `UI_E2E_AI=1`。
- 暂不依赖 Docker Compose，需手动启动六进程。
- 不直连数据库，造数/清理走网关 API。
```

- [ ] **Step 3: 运行 preflight 与收集检查**

Run:

```bash
cd ai-assistant-ui-e2e
.venv/Scripts/python.exe preflight.py
.venv/Scripts/python.exe -m pytest --collect-only -q
```

Expected: preflight 全部 OK；collect-only 能列出测试文件且无导入错误。

- [ ] **Step 4: 跑默认套件并检查报告**

Run: `cd ai-assistant-ui-e2e && .venv/Scripts/python.exe -m pytest`

Expected: 默认确定性套件通过；`reports/ui-e2e-results.xml` 存在；失败时 `artifacts/` 有 trace/截图/视频。

- [ ] **Step 5: 提交**

```bash
git add ai-assistant-ui-e2e .gitignore
git commit -m "docs(ui-e2e): 补充 preflight、README 与运行说明"
```

---

## Self-Review

**Spec coverage:**

- 6 条 MVP 用例：Task 1/2（登录+菜单）、Task 4（患者注册登录）、Task 5（导诊）、Task 6（挂号确认）、Task 3（知识库）、Task 7（390px）。
- 技术选型与目录：Task 1。
- 测试钩子：Task 1/3/4/5/6。
- 登录态与 storageState：Task 1/4。
- 数据策略：Task 3/4/6/8。
- AI 非确定性：Task 5/6 的结构断言与 `ai` 标记。
- 报告与诊断：Task 1 的 pytest 配置、Task 8 的 README。
- CI/nightly 暂缓：Task 8 文档说明。
- 已确认的 5 个决策：Global Constraints 与 README 已体现。

**Placeholder scan:** 无 TBD/TODO；所有代码步骤均给出实际代码或命令。

**Type consistency:**

- `Session`、`ApiClient`、`settings`、`PatientAccount` 在前后任务中名称一致。
- `patient_page` 使用 `_patient_state`，Task 4 定义，Task 7 复用。
- `staff1_session` 在 Task 2 定义，Task 5/6 复用。
- `chat-send`、`chat-message-assistant`、`confirm-*` testid 在 Task 5/6 中保持一致。
