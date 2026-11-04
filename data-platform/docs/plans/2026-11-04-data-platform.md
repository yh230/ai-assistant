# 医院数据治理与运营大屏平台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `data-platform/` 内交付一套可静态验证的 Python 数据治理平台代码，覆盖 MySQL 增量抽取、Kafka 消费、ClickHouse 分层建模、质量规则、告警、大屏 API 与 Vue 3 页面；Kafka、ClickHouse、Flink 不下载、不部署。

**Architecture:** 模块化单体 + 双进程。FastAPI 提供治理与大屏 API，APScheduler 负责调度；`etl` 提供抽取、消费、转换、质量执行入口。Kafka、ClickHouse、数据源全部通过端口接口隔离，生产适配器与内存 Fake 并存，缺少外部依赖时仍可运行纯逻辑单测。

**Tech Stack:** Python 3.11+、FastAPI、SQLAlchemy 2、APScheduler、Pydantic Settings、confluent-kafka（可选）、clickhouse-connect（可选）、PyFlink（仅代码）、Vue 3、Vite、ECharts、Element Plus。

**Spec:** `data-platform/docs/2026-11-04-design.md`

## Global Constraints

- 不下载、不安装、不启动 Kafka、ClickHouse、Flink；不执行真实连接、集成测试或 E2E。
- 所有代码位于 `data-platform/`，不修改 `ai-assistant-backend/` 与 `ai-assistant-frontend/` 现有文件。
- Python 3.11+；纯逻辑单测使用标准库 `unittest`，依赖缺失时相关测试必须 `skip`，不得伪报通过。
- 不提交密钥、本机绝对路径、`.env` 文件；密码只存密文。
- 所有数据时间口径为 `Asia/Shanghai`；接口响应统一 `{code, message, data}`。
- 姓名、手机号、身份证号、聊天正文不进入 ODS/DWD/ADS，不写入日志。
- 每个任务结束执行 `git add` + 本地 `git commit`，不 push。
- 验证命令在 `data-platform/` 目录执行：
  - `python -m compileall -q app etl tests`
  - `python -m unittest discover -s tests/unit -v`
  - `node --test web/tests/chartOptions.test.mjs`

## File Structure

```text
data-platform/
├── pyproject.toml
├── .env.example
├── README.md
├── app/
│   ├── __init__.py
│   ├── main.py
│   ├── core/{config.py,db.py,response.py,exceptions.py,crypto.py,security.py}
│   ├── models/metadata.py
│   ├── schemas/{datasource.py,sync.py,quality.py,alert.py,screen.py}
│   ├── api/{deps.py,datasources.py,metadata.py,sync_tasks.py,quality.py,alerts.py,screen.py}
│   ├── services/{datasource_service.py,sync_service.py,quality_service.py,alert_service.py,screen_service.py}
│   └── scheduler.py
├── etl/
│   ├── __init__.py
│   ├── ports/{event_bus.py,olap_store.py,source_repository.py,run_store.py}
│   ├── adapters/{inmemory_event_bus.py,inmemory_olap_store.py,kafka_event_bus.py,clickhouse_olap_store.py,mysql_source_repository.py,factory.py}
│   ├── envelope.py
│   ├── watermark.py
│   ├── cleaning.py
│   ├── extractor.py
│   ├── consumer.py
│   ├── transform.py
│   ├── quality.py
│   ├── alerting.py
│   └── sql/{ddl/clickhouse.sql,dwd/*.sql,ads/*.sql}
├── flink/
│   ├── jobs/realtime_registration_agg.py
│   ├── sql/realtime_registration_agg.sql
│   ├── Dockerfile
│   └── README.md
├── web/
│   ├── package.json
│   ├── vite.config.js
│   ├── index.html
│   ├── src/{main.js,App.vue,router/index.js,api/client.js,mock/screen.js,utils/chartOptions.js}
│   ├── src/views/{ScreenView.vue,DatasourcesView.vue,SyncTasksView.vue,QualityView.vue,AlertsView.vue}
│   └── tests/chartOptions.test.mjs
├── tests/
│   ├── unit/{test_config.py,test_metadata_models.py,test_identifiers.py,test_crypto.py,test_datasource_service.py,test_envelope.py,test_extractor.py,test_watermark.py,test_cleaning.py,test_consumer.py,test_factory.py,test_transform_runner.py,test_quality_rules.py,test_alerting.py,test_screen_service.py,test_api_health.py}
│   └── integration/README.md
└── docs/{2026-11-04-design.md,plans/2026-11-04-data-platform.md,verification-2026-11-04.md}
```

---

### Task 1: 工程骨架、配置与元数据模型

**Files:**
- Create: `pyproject.toml`, `.env.example`, `app/__init__.py`, `app/core/config.py`, `app/core/db.py`, `app/core/response.py`, `app/core/exceptions.py`, `app/models/metadata.py`, `app/main.py`
- Test: `tests/unit/test_config.py`, `tests/unit/test_metadata_models.py`, `tests/unit/test_api_health.py`

**Interfaces:**
- Produces: `Settings`、`get_settings()`、`Base`、`SessionLocal`、`get_db()`、`ok()`、`fail()`、`create_app()`。
- Later tasks import `Base` from `app.core.db` and response helpers from `app.core.response`.

- [ ] **Step 1: 写失败测试**

```python
# tests/unit/test_config.py
import unittest

try:
    from app.core.config import Settings
except ImportError:
    Settings = None


@unittest.skipIf(Settings is None, "pydantic-settings not installed")
class SettingsTest(unittest.TestCase):
    def test_defaults(self):
        settings = Settings(_env_file=None)
        self.assertEqual(settings.kafka_consumer_group, "dp-ods-loader")
        self.assertEqual(settings.kafka_topic_prefix, "dp.ods")
        self.assertEqual(settings.timezone, "Asia/Shanghai")
        self.assertEqual(settings.event_bus, "memory")
```

```python
# tests/unit/test_metadata_models.py
import unittest

try:
    from app.core.db import Base
    import app.models.metadata  # noqa: F401
except ImportError:
    Base = None


@unittest.skipIf(Base is None, "sqlalchemy not installed")
class MetadataModelTest(unittest.TestCase):
    def test_tables_registered(self):
        names = set(Base.metadata.tables)
        for name in ("dp_datasource", "dp_sync_task", "dp_sync_run", "dp_quality_rule", "dp_alert_record"):
            self.assertIn(name, names)
```

```python
# tests/unit/test_api_health.py
import importlib.util
import unittest

HAS_STACK = all(importlib.util.find_spec(name) for name in ("fastapi", "sqlalchemy", "pydantic_settings"))


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class HealthApiTest(unittest.TestCase):
    def test_health(self):
        from fastapi.testclient import TestClient
        from app.main import create_app

        response = TestClient(create_app()).get("/api/health")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["code"], 0)
        self.assertEqual(body["data"]["status"], "ok")
```

- [ ] **Step 2: 运行测试，确认失败或跳过**

Run: `python -m unittest tests.unit.test_config tests.unit.test_metadata_models tests.unit.test_api_health -v`
Expected: FAIL（模块不存在）或 SKIP（依赖未安装）。

- [ ] **Step 3: 写最小实现**

`pyproject.toml`：

```toml
[project]
name = "data-platform"
version = "0.1.0"
description = "Hospital data governance and operations dashboard"
requires-python = ">=3.11"
dependencies = [
  "fastapi>=0.115,<1",
  "uvicorn[standard]>=0.30,<1",
  "sqlalchemy>=2.0,<3",
  "pymysql>=1.1,<2",
  "pydantic-settings>=2.4,<3",
  "apscheduler>=3.10,<4",
  "cryptography>=43,<46",
  "pyjwt>=2.9,<3",
  "httpx>=0.27,<1",
]

[project.optional-dependencies]
kafka = ["confluent-kafka>=2.5,<3"]
clickhouse = ["clickhouse-connect>=0.8,<1"]
dev = ["ruff>=0.6,<1"]

[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"

[tool.ruff]
line-length = 100
```

`.env.example`：

```dotenv
APP_ENV=dev
METADATA_DB_URL=sqlite:///./data-platform.db
SOURCE_DB_URL=mysql+pymysql://readonly:change-me@127.0.0.1:3306
JWT_SECRET=change-me
DP_SECRET_KEY=
EVENT_BUS=memory
OLAP_STORE=memory
KAFKA_BOOTSTRAP=127.0.0.1:9092
KAFKA_CONSUMER_GROUP=dp-ods-loader
KAFKA_TOPIC_PREFIX=dp.ods
CLICKHOUSE_URL=http://127.0.0.1:8123
CLICKHOUSE_DATABASE=data_platform
```

`app/core/config.py`：

```python
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_name: str = "data-platform"
    env: str = "dev"
    metadata_db_url: str = "sqlite:///./data-platform.db"
    source_db_url: str = ""
    jwt_secret: str = ""
    jwt_algorithm: str = "HS256"
    dp_secret_key: str = ""
    event_bus: str = "memory"
    olap_store: str = "memory"
    kafka_bootstrap: str = "127.0.0.1:9092"
    kafka_consumer_group: str = "dp-ods-loader"
    kafka_topic_prefix: str = "dp.ods"
    clickhouse_url: str = "http://127.0.0.1:8123"
    clickhouse_database: str = "data_platform"
    timezone: str = "Asia/Shanghai"


def get_settings() -> Settings:
    return Settings()
```

`app/core/db.py`：

```python
from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from app.core.config import get_settings


class Base(DeclarativeBase):
    pass


settings = get_settings()
engine = create_engine(settings.metadata_db_url, pool_pre_ping=True)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
```

`app/core/response.py`：

```python
from typing import Any


def ok(data: Any = None) -> dict[str, Any]:
    return {"code": 0, "message": "ok", "data": data}


def fail(code: int, message: str, data: Any = None) -> dict[str, Any]:
    return {"code": code, "message": message, "data": data}
```

`app/core/exceptions.py`：

```python
class DataPlatformError(Exception):
    def __init__(self, code: int, message: str):
        self.code = code
        self.message = message
        super().__init__(message)
```

`app/models/metadata.py`（使用 SQLAlchemy 2 声明式模型，表名与设计文档一致）：

```python
from datetime import datetime

from sqlalchemy import Boolean, DateTime, Float, Integer, JSON, Numeric, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.core.db import Base


class DataSource(Base):
    __tablename__ = "dp_datasource"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(100), unique=True)
    db_type: Mapped[str] = mapped_column(String(20), default="mysql")
    host: Mapped[str] = mapped_column(String(255))
    port: Mapped[int] = mapped_column(Integer, default=3306)
    database_name: Mapped[str] = mapped_column(String(100))
    username: Mapped[str] = mapped_column(String(100))
    password_cipher: Mapped[str] = mapped_column(Text)
    status: Mapped[int] = mapped_column(Integer, default=1)
    last_test_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    update_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class SyncTask(Base):
    __tablename__ = "dp_sync_task"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    datasource_id: Mapped[int] = mapped_column(Integer, index=True)
    source_schema: Mapped[str] = mapped_column(String(100))
    source_table: Mapped[str] = mapped_column(String(100))
    target_table: Mapped[str] = mapped_column(String(100))
    primary_key: Mapped[str] = mapped_column(String(50), default="id")
    watermark_column: Mapped[str | None] = mapped_column(String(50), nullable=True)
    watermark_type: Mapped[str] = mapped_column(String(20), default="datetime")
    batch_size: Mapped[int] = mapped_column(Integer, default=500)
    overlap_seconds: Mapped[int] = mapped_column(Integer, default=60)
    cron: Mapped[str] = mapped_column(String(50), default="*/10 * * * *")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    full_load_done: Mapped[bool] = mapped_column(Boolean, default=False)
    last_watermark: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    last_run_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class SyncRun(Base):
    __tablename__ = "dp_sync_run"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    task_id: Mapped[int] = mapped_column(Integer, index=True)
    run_type: Mapped[str] = mapped_column(String(20))
    status: Mapped[str] = mapped_column(String(20))
    started_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    rows_read: Mapped[int] = mapped_column(Integer, default=0)
    rows_written: Mapped[int] = mapped_column(Integer, default=0)
    error_msg: Mapped[str | None] = mapped_column(Text, nullable=True)
    checkpoint_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)


class TableMeta(Base):
    __tablename__ = "dp_table_meta"
    __table_args__ = (UniqueConstraint("datasource_id", "table_schema", "table_name", name="uk_table_meta"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    datasource_id: Mapped[int] = mapped_column(Integer, index=True)
    table_schema: Mapped[str] = mapped_column(String(100))
    table_name: Mapped[str] = mapped_column(String(100))
    table_comment: Mapped[str] = mapped_column(String(255), default="")
    row_estimate: Mapped[int | None] = mapped_column(Integer, nullable=True)
    last_introspected_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class ColumnMeta(Base):
    __tablename__ = "dp_column_meta"
    __table_args__ = (UniqueConstraint("table_meta_id", "column_name", name="uk_column_meta"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    table_meta_id: Mapped[int] = mapped_column(Integer, index=True)
    column_name: Mapped[str] = mapped_column(String(100))
    data_type: Mapped[str] = mapped_column(String(100))
    nullable: Mapped[bool] = mapped_column(Boolean, default=True)
    column_comment: Mapped[str] = mapped_column(String(255), default="")
    ordinal: Mapped[int] = mapped_column(Integer)


class DpQualityRule(Base):
    __tablename__ = "dp_quality_rule"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    target_table: Mapped[str] = mapped_column(String(100))
    rule_type: Mapped[str] = mapped_column(String(30))
    column_name: Mapped[str | None] = mapped_column(String(100), nullable=True)
    params_json: Mapped[dict] = mapped_column(JSON, default=dict)
    threshold: Mapped[float] = mapped_column(Float, default=1.0)
    severity: Mapped[str] = mapped_column(String(10), default="P1")
    cron: Mapped[str] = mapped_column(String(50), default="0 * * * *")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class DpQualityResult(Base):
    __tablename__ = "dp_quality_result"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    rule_id: Mapped[int] = mapped_column(Integer, index=True)
    run_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    total_rows: Mapped[int] = mapped_column(Integer, default=0)
    bad_rows: Mapped[int] = mapped_column(Integer, default=0)
    pass_rate: Mapped[float] = mapped_column(Float, default=1.0)
    status: Mapped[str] = mapped_column(String(20), default="PASS")
    sample_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    checked_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class DpAlertRule(Base):
    __tablename__ = "dp_alert_rule"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    metric_type: Mapped[str] = mapped_column(String(30))
    operator: Mapped[str] = mapped_column(String(5), default=">")
    threshold: Mapped[float] = mapped_column(Float)
    window_minutes: Mapped[int] = mapped_column(Integer, default=5)
    consecutive_periods: Mapped[int] = mapped_column(Integer, default=1)
    severity: Mapped[str] = mapped_column(String(10), default="P1")
    channel: Mapped[str] = mapped_column(String(20), default="webhook")
    cooldown_minutes: Mapped[int] = mapped_column(Integer, default=30)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)


class DpAlertRecord(Base):
    __tablename__ = "dp_alert_record"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    rule_id: Mapped[int] = mapped_column(Integer, index=True)
    fingerprint: Mapped[str] = mapped_column(String(64), index=True)
    severity: Mapped[str] = mapped_column(String(10))
    title: Mapped[str] = mapped_column(String(200))
    content: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(20), default="OPEN")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    ack_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
```

`app/main.py`：

```python
from fastapi import FastAPI

from app.core.db import Base, engine
from app.core.response import ok
import app.models.metadata  # noqa: F401


def create_app() -> FastAPI:
    app = FastAPI(title="data-platform")
    Base.metadata.create_all(bind=engine)

    @app.get("/api/health")
    def health():
        return ok({"status": "ok"})

    return app


app = create_app()
```

- [ ] **Step 4: 运行测试**

Run: `python -m unittest tests.unit.test_config tests.unit.test_metadata_models tests.unit.test_api_health -v`
Expected: 依赖已安装时 PASS；依赖缺失时 SKIP，不允许 FAIL。

- [ ] **Step 5: 静态编译检查**

Run: `python -m compileall -q app etl tests`
Expected: 无输出，退出码 0。

- [ ] **Step 6: 提交**

```bash
git add data-platform/pyproject.toml data-platform/.env.example data-platform/app data-platform/tests/unit/test_config.py data-platform/tests/unit/test_metadata_models.py data-platform/tests/unit/test_api_health.py
git commit -m "feat(data-platform): scaffold backend and metadata models"
```

---

### Task 2: 数据源、加密与元数据采集

**Files:**
- Create: `app/core/crypto.py`, `app/schemas/datasource.py`, `app/services/datasource_service.py`, `app/api/deps.py`, `app/api/datasources.py`, `app/api/metadata.py`, `etl/ports/source_repository.py`, `etl/adapters/mysql_source_repository.py`
- Test: `tests/unit/test_identifiers.py`, `tests/unit/test_crypto.py`, `tests/unit/test_datasource_service.py`

**Interfaces:**
- Produces: `validate_identifier()`、`SecretBox`、`SourceTable`、`SourceColumn`、`SourceRepository`、`MySQLSourceRepository`、`DatasourceService`。
- Consumes: `DataSource`、`TableMeta`、`ColumnMeta`、`get_db`。

- [ ] **Step 1: 写失败测试**

```python
# tests/unit/test_identifiers.py
import unittest

try:
    from etl.adapters.mysql_source_repository import validate_identifier
except ImportError:
    validate_identifier = None


@unittest.skipIf(validate_identifier is None, "sqlalchemy not installed")
class IdentifierTest(unittest.TestCase):
    def test_accepts_safe_identifier(self):
        self.assertEqual(validate_identifier("registration_db"), "registration_db")

    def test_rejects_injection(self):
        with self.assertRaises(ValueError):
            validate_identifier("registration; drop table x")
```

```python
# tests/unit/test_crypto.py
import importlib.util
import unittest

HAS_CRYPTO = importlib.util.find_spec("cryptography") is not None


@unittest.skipUnless(HAS_CRYPTO, "cryptography not installed")
class SecretBoxTest(unittest.TestCase):
    def test_roundtrip(self):
        from app.core.crypto import SecretBox, generate_key

        box = SecretBox(generate_key())
        token = box.encrypt("p@ssw0rd")
        self.assertNotEqual(token, "p@ssw0rd")
        self.assertEqual(box.decrypt(token), "p@ssw0rd")
```

```python
# tests/unit/test_datasource_service.py
import importlib.util
import unittest

HAS_STACK = all(importlib.util.find_spec(name) for name in ("sqlalchemy", "pydantic_settings", "cryptography"))


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class DatasourceServiceTest(unittest.TestCase):
    def test_create_encrypts_password(self):
        from sqlalchemy import create_engine
        from sqlalchemy.orm import sessionmaker

        from app.core.crypto import generate_key
        from app.core.db import Base
        from app.models.metadata import DataSource
        from app.services.datasource_service import DatasourceService

        engine = create_engine("sqlite:///:memory:")
        Base.metadata.create_all(engine)
        db = sessionmaker(bind=engine)()
        service = DatasourceService(db, generate_key())
        entity = service.create(
            name="hospital",
            host="127.0.0.1",
            port=3306,
            database_name="hospital_db",
            username="readonly",
            password="secret",
        )
        self.assertTrue(entity.password_cipher)
        self.assertNotIn("secret", entity.password_cipher)
        stored = db.get(DataSource, entity.id)
        self.assertNotEqual(stored.password_cipher, "secret")
```

- [ ] **Step 2: 运行测试，确认失败或跳过**

Run: `python -m unittest tests.unit.test_identifiers tests.unit.test_crypto tests.unit.test_datasource_service -v`
Expected: FAIL（模块不存在）或 SKIP（依赖未安装）。

- [ ] **Step 3: 写最小实现**

`app/core/crypto.py`：

```python
import base64

from cryptography.fernet import Fernet


def generate_key() -> str:
    return Fernet.generate_key().decode("ascii")


class SecretBox:
    def __init__(self, key: str):
        if not key:
            raise ValueError("DP_SECRET_KEY is required to store datasource passwords")
        self._fernet = Fernet(key.encode("ascii"))

    def encrypt(self, plain: str) -> str:
        return self._fernet.encrypt(plain.encode("utf-8")).decode("ascii")

    def decrypt(self, token: str) -> str:
        return self._fernet.decrypt(token.encode("ascii")).decode("utf-8")
```

`etl/ports/source_repository.py`：

```python
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Protocol


@dataclass(frozen=True)
class SourceTable:
    schema: str
    name: str
    comment: str


@dataclass(frozen=True)
class SourceColumn:
    name: str
    data_type: str
    nullable: bool
    comment: str
    ordinal: int


class SourceRepository(Protocol):
    def test_connection(self) -> bool: ...
    def list_tables(self, schema: str) -> list[SourceTable]: ...
    def list_columns(self, schema: str, table: str) -> list[SourceColumn]: ...
    def fetch_full_batch(self, schema: str, table: str, columns: list[str], primary_key: str, after_pk: int | None, limit: int) -> list[dict[str, Any]]: ...
    def fetch_incremental_batch(self, schema: str, table: str, columns: list[str], primary_key: str, watermark_column: str, after_watermark: datetime, after_pk: int | None, limit: int) -> list[dict[str, Any]]: ...
    def max_watermark(self, schema: str, table: str, watermark_column: str) -> datetime | None: ...
```

`etl/adapters/mysql_source_repository.py`：

```python
import re
from datetime import datetime
from typing import Any

from sqlalchemy import create_engine, text

from etl.ports.source_repository import SourceColumn, SourceTable

_IDENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def validate_identifier(value: str) -> str:
    if not _IDENT.match(value):
        raise ValueError(f"invalid identifier: {value}")
    return value


class MySQLSourceRepository:
    def __init__(self, url: str):
        self._engine = create_engine(url, pool_pre_ping=True)

    def test_connection(self) -> bool:
        with self._engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        return True

    def list_tables(self, schema: str) -> list[SourceTable]:
        schema = validate_identifier(schema)
        sql = text(
            "SELECT table_name, table_comment FROM information_schema.tables "
            "WHERE table_schema = :schema AND table_type = 'BASE TABLE' ORDER BY table_name"
        )
        with self._engine.connect() as conn:
            rows = conn.execute(sql, {"schema": schema}).mappings().all()
        return [SourceTable(schema=schema, name=row["table_name"], comment=row["table_comment"] or "") for row in rows]

    def list_columns(self, schema: str, table: str) -> list[SourceColumn]:
        schema = validate_identifier(schema)
        table = validate_identifier(table)
        sql = text(
            "SELECT column_name, column_type, is_nullable, column_comment, ordinal_position "
            "FROM information_schema.columns WHERE table_schema = :schema AND table_name = :table "
            "ORDER BY ordinal_position"
        )
        with self._engine.connect() as conn:
            rows = conn.execute(sql, {"schema": schema, "table": table}).mappings().all()
        return [
            SourceColumn(
                name=row["column_name"],
                data_type=row["column_type"],
                nullable=row["is_nullable"] == "YES",
                comment=row["column_comment"] or "",
                ordinal=int(row["ordinal_position"]),
            )
            for row in rows
        ]

    def fetch_full_batch(self, schema: str, table: str, columns: list[str], primary_key: str, after_pk: int | None, limit: int) -> list[dict[str, Any]]:
        schema = validate_identifier(schema)
        table = validate_identifier(table)
        primary_key = validate_identifier(primary_key)
        column_sql = ", ".join(validate_identifier(column) for column in columns)
        where = f"WHERE {primary_key} > :after_pk" if after_pk is not None else ""
        sql = text(f"SELECT {column_sql} FROM {schema}.{table} {where} ORDER BY {primary_key} LIMIT :limit")
        params = {"limit": limit}
        if after_pk is not None:
            params["after_pk"] = after_pk
        with self._engine.connect() as conn:
            rows = conn.execute(sql, params).mappings().all()
        return [dict(row) for row in rows]

    def fetch_incremental_batch(self, schema: str, table: str, columns: list[str], primary_key: str, watermark_column: str, after_watermark: datetime, after_pk: int | None, limit: int) -> list[dict[str, Any]]:
        schema = validate_identifier(schema)
        table = validate_identifier(table)
        primary_key = validate_identifier(primary_key)
        watermark_column = validate_identifier(watermark_column)
        column_sql = ", ".join(validate_identifier(column) for column in columns)
        if after_pk is None:
            where = f"WHERE {watermark_column} >= :after_watermark"
        else:
            where = f"WHERE ({watermark_column}, {primary_key}) > (:after_watermark, :after_pk)"
        sql = text(f"SELECT {column_sql} FROM {schema}.{table} {where} ORDER BY {watermark_column}, {primary_key} LIMIT :limit")
        params = {"after_watermark": after_watermark, "limit": limit}
        if after_pk is not None:
            params["after_pk"] = after_pk
        with self._engine.connect() as conn:
            rows = conn.execute(sql, params).mappings().all()
        return [dict(row) for row in rows]

    def max_watermark(self, schema: str, table: str, watermark_column: str) -> datetime | None:
        schema = validate_identifier(schema)
        table = validate_identifier(table)
        watermark_column = validate_identifier(watermark_column)
        sql = text(f"SELECT MAX({watermark_column}) AS max_value FROM {schema}.{table}")
        with self._engine.connect() as conn:
            value = conn.execute(sql).scalar()
        return value
```

`app/services/datasource_service.py`：

```python
from datetime import datetime

from sqlalchemy.orm import Session

from app.core.crypto import SecretBox, generate_key
from app.models.metadata import ColumnMeta, DataSource, TableMeta
from etl.adapters.mysql_source_repository import MySQLSourceRepository


class DatasourceService:
    def __init__(self, db: Session, secret_key: str):
        self.db = db
        self.box = SecretBox(secret_key)

    def create(self, name: str, host: str, port: int, database_name: str, username: str, password: str) -> DataSource:
        entity = DataSource(
            name=name,
            host=host,
            port=port,
            database_name=database_name,
            username=username,
            password_cipher=self.box.encrypt(password),
        )
        self.db.add(entity)
        self.db.commit()
        self.db.refresh(entity)
        return entity

    def test_connection(self, entity: DataSource) -> bool:
        repo = self._repo(entity)
        ok = repo.test_connection()
        entity.last_test_at = datetime.utcnow()
        self.db.commit()
        return ok

    def introspect(self, entity: DataSource, schema: str, table: str) -> int:
        repo = self._repo(entity)
        table_meta = TableMeta(datasource_id=entity.id, table_schema=schema, table_name=table)
        self.db.add(table_meta)
        self.db.flush()
        count = 0
        for column in repo.list_columns(schema, table):
            self.db.add(ColumnMeta(
                table_meta_id=table_meta.id,
                column_name=column.name,
                data_type=column.data_type,
                nullable=column.nullable,
                column_comment=column.comment,
                ordinal=column.ordinal,
            ))
            count += 1
        self.db.commit()
        return count

    def _repo(self, entity: DataSource) -> MySQLSourceRepository:
        return MySQLSourceRepository(self.url_for(entity))

    def url_for(self, entity: DataSource) -> str:
        from sqlalchemy.engine import URL

        password = self.box.decrypt(entity.password_cipher)
        return URL.create(
            "mysql+pymysql",
            username=entity.username,
            password=password,
            host=entity.host,
            port=entity.port,
            database=entity.database_name,
        ).render_as_string(hide_password=False)
```

`app/api/deps.py`：

```python
from fastapi import Depends, Header, HTTPException
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.core.db import get_db


def get_session(db: Session = Depends(get_db)) -> Session:
    return db


def require_staff(authorization: str | None = Header(default=None)) -> dict:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="missing token")
    settings = get_settings()
    if not settings.jwt_secret:
        raise HTTPException(status_code=500, detail="JWT secret not configured")
    token = authorization.removeprefix("Bearer ").strip()
    try:
        import jwt

        payload = jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
    except Exception as exc:
        raise HTTPException(status_code=401, detail="invalid token") from exc
    if payload.get("identity") != "staff":
        raise HTTPException(status_code=403, detail="staff identity required")
    return payload
```

`app/schemas/datasource.py`：

```python
from pydantic import BaseModel


class DatasourceCreate(BaseModel):
    name: str
    host: str
    port: int = 3306
    database_name: str
    username: str
    password: str


class IntrospectRequest(BaseModel):
    schema_name: str
    table: str
```

`app/api/datasources.py`：

```python
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.api.deps import get_session, require_staff
from app.core.config import get_settings
from app.core.response import ok
from app.models.metadata import DataSource
from app.schemas.datasource import DatasourceCreate, IntrospectRequest
from app.services.datasource_service import DatasourceService

router = APIRouter(prefix="/datasources", tags=["datasources"], dependencies=[Depends(require_staff)])


def _service(db: Session) -> DatasourceService:
    return DatasourceService(db, get_settings().dp_secret_key)


@router.post("")
def create(payload: DatasourceCreate, db: Session = Depends(get_session)):
    entity = _service(db).create(
        payload.name, payload.host, payload.port, payload.database_name, payload.username, payload.password
    )
    return ok({"id": entity.id, "name": entity.name})


@router.get("")
def list_datasources(db: Session = Depends(get_session)):
    rows = db.query(DataSource).order_by(DataSource.id.desc()).all()
    return ok([
        {"id": row.id, "name": row.name, "host": row.host, "database_name": row.database_name, "status": row.status}
        for row in rows
    ])


@router.post("/{datasource_id}/test")
def test_connection(datasource_id: int, db: Session = Depends(get_session)):
    entity = db.get(DataSource, datasource_id)
    if entity is None:
        raise HTTPException(status_code=404, detail="datasource not found")
    return ok({"connected": _service(db).test_connection(entity)})


@router.post("/{datasource_id}/introspect")
def introspect(datasource_id: int, payload: IntrospectRequest, db: Session = Depends(get_session)):
    entity = db.get(DataSource, datasource_id)
    if entity is None:
        raise HTTPException(status_code=404, detail="datasource not found")
    count = _service(db).introspect(entity, payload.schema_name, payload.table)
    return ok({"columns": count})
```

`app/api/metadata.py`：

```python
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_session, require_staff
from app.core.response import ok
from app.models.metadata import ColumnMeta, TableMeta

router = APIRouter(prefix="/metadata", tags=["metadata"], dependencies=[Depends(require_staff)])


@router.get("/tables")
def list_tables(db: Session = Depends(get_session)):
    rows = db.query(TableMeta).order_by(TableMeta.id.desc()).all()
    return ok([
        {"id": row.id, "schema": row.table_schema, "table": row.table_name, "comment": row.table_comment}
        for row in rows
    ])


@router.get("/tables/{table_id}/columns")
def list_columns(table_id: int, db: Session = Depends(get_session)):
    rows = (
        db.query(ColumnMeta)
        .filter(ColumnMeta.table_meta_id == table_id)
        .order_by(ColumnMeta.ordinal)
        .all()
    )
    return ok([
        {"name": row.column_name, "data_type": row.data_type, "nullable": row.nullable, "comment": row.column_comment}
        for row in rows
    ])
```

- [ ] **Step 4: 运行测试**

Run: `python -m unittest tests.unit.test_identifiers tests.unit.test_crypto tests.unit.test_datasource_service -v`
Expected: 依赖可用时 PASS；缺失时 SKIP。

- [ ] **Step 5: 静态编译检查**

Run: `python -m compileall -q app etl tests`
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add data-platform/app data-platform/etl data-platform/tests/unit/test_identifiers.py data-platform/tests/unit/test_crypto.py data-platform/tests/unit/test_datasource_service.py
git commit -m "feat(data-platform): add datasource and metadata services"
```

---

### Task 3: 同步任务、事件信封与 Extractor

**Files:**
- Create: `app/schemas/sync.py`, `app/services/sync_service.py`, `app/api/sync_tasks.py`, `etl/envelope.py`, `etl/watermark.py`, `etl/ports/event_bus.py`, `etl/ports/run_store.py`, `etl/extractor.py`
- Test: `tests/unit/test_envelope.py`, `tests/unit/test_extractor.py`

**Interfaces:**
- Produces: `Envelope`、`SyncTaskSpec`、`ExtractResult`、`RunStore`、`Extractor.run(task, mode)`、`lower_bound()`、`advance_watermark()`、`EventPublisher`。
- Consumes: `SourceRepository`。

- [ ] **Step 1: 写失败测试**

```python
# tests/unit/test_envelope.py
import unittest

from etl.envelope import Envelope


class EnvelopeTest(unittest.TestCase):
    def test_roundtrip(self):
        original = Envelope(
            event_id="e-1",
            event_type="ODS_ROW",
            occurred_at=1730000000000,
            payload={"source_db": "registration_db", "source_table": "registration", "row": {"id": 1}},
        )
        restored = Envelope.from_bytes(original.to_bytes())
        self.assertEqual(restored.event_id, "e-1")
        self.assertEqual(restored.payload["source_table"], "registration")

    def test_rejects_missing_fields(self):
        with self.assertRaises(ValueError):
            Envelope.from_bytes(b'{"eventId":"e-1"}')
```

```python
# tests/unit/test_extractor.py
import unittest
from datetime import datetime

from etl.envelope import Envelope
from etl.extractor import ExtractResult, Extractor, SyncTaskSpec


class FakeSource:
    def __init__(self, batches):
        self.batches = list(batches)
        self.calls = 0

    def fetch_full_batch(self, schema, table, columns, primary_key, after_pk, limit):
        self.calls += 1
        return self.batches.pop(0) if self.batches else []

    def fetch_incremental_batch(self, schema, table, columns, primary_key, watermark_column, after_watermark, after_pk, limit):
        self.calls += 1
        return self.batches.pop(0) if self.batches else []

    def max_watermark(self, schema, table, watermark_column):
        return datetime(2026, 11, 4, 12, 0, 0)


class FakePublisher:
    def __init__(self):
        self.messages = []

    def publish(self, topic, key, value):
        self.messages.append((topic, key, value))

    def flush(self):
        pass


class FakeRunStore:
    def __init__(self):
        self.started = []
        self.checkpoints = []
        self.finished = []

    def start_run(self, task_id, run_type):
        self.started.append((task_id, run_type))
        return 1

    def checkpoint(self, run_id, rows_read, rows_written, cursor):
        self.checkpoints.append((rows_read, rows_written, cursor))

    def finish_run(self, run_id, status, rows_read, rows_written, error=None):
        self.finished.append((status, rows_read, rows_written, error))


class ExtractorTest(unittest.TestCase):
    def test_full_load_publishes_rows_and_finishes(self):
        task = SyncTaskSpec(
            id=1,
            source_schema="registration_db",
            source_table="registration",
            target_table="ods_registration",
            primary_key="id",
            watermark_column="update_time",
            batch_size=2,
        )
        source = FakeSource([[{"id": 1, "update_time": "2026-11-04 10:00:00"}, {"id": 2, "update_time": "2026-11-04 10:00:01"}]])
        publisher = FakePublisher()
        run_store = FakeRunStore()

        result = Extractor(source, publisher, "dp.ods").run(task, "FULL", run_store)

        self.assertEqual(result.rows_read, 2)
        self.assertEqual(len(publisher.messages), 2)
        self.assertEqual(run_store.finished[0][0], "SUCCESS")
        self.assertEqual(Envelope.from_bytes(publisher.messages[0][2]).event_type, "ODS_ROW")

    def test_incremental_uses_watermark_and_checkpoint(self):
        task = SyncTaskSpec(
            id=2,
            source_schema="chat_db",
            source_table="chat_session",
            target_table="ods_chat_session",
            primary_key="id",
            watermark_column="updated_at",
            batch_size=100,
            full_load_done=True,
            last_watermark=datetime(2026, 11, 4, 10, 0, 0),
        )
        source = FakeSource([[{"id": 5, "updated_at": datetime(2026, 11, 4, 10, 1, 0)}]])
        publisher = FakePublisher()
        run_store = FakeRunStore()

        result = Extractor(source, publisher, "dp.ods").run(task, "INCREMENTAL", run_store)

        self.assertEqual(result.rows_read, 1)
        self.assertEqual(run_store.started[0][1], "INCREMENTAL")
        self.assertTrue(run_store.checkpoints)
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `python -m unittest tests.unit.test_envelope tests.unit.test_extractor -v`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 写最小实现**

`etl/envelope.py`：

```python
import json
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class Envelope:
    event_id: str
    event_type: str
    occurred_at: int
    payload: dict[str, Any]

    def to_bytes(self) -> bytes:
        body = {
            "eventId": self.event_id,
            "eventType": self.event_type,
            "occurredAt": self.occurred_at,
            "payload": self.payload,
        }
        return json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    @classmethod
    def from_bytes(cls, raw: bytes) -> "Envelope":
        try:
            body = json.loads(raw.decode("utf-8"))
            return cls(
                event_id=body["eventId"],
                event_type=body["eventType"],
                occurred_at=int(body["occurredAt"]),
                payload=body["payload"],
            )
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise ValueError("invalid envelope") from exc
```

`etl/ports/run_store.py`：

```python
from typing import Any, Protocol


class RunStore(Protocol):
    def start_run(self, task_id: int, run_type: str) -> int: ...
    def checkpoint(self, run_id: int, rows_read: int, rows_written: int, cursor: dict[str, Any]) -> None: ...
    def finish_run(self, run_id: int, status: str, rows_read: int, rows_written: int, error: str | None = None) -> None: ...
```

`etl/ports/event_bus.py`：

```python
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class ConsumedEvent:
    topic: str
    key: str | None
    value: bytes
    partition: int = 0
    offset: int = 0


class EventPublisher(Protocol):
    def publish(self, topic: str, key: str, value: bytes) -> None: ...
    def flush(self) -> None: ...


class EventConsumer(Protocol):
    def subscribe(self, topics: list[str]) -> None: ...
    def poll(self, timeout_seconds: float = 1.0, max_records: int = 500) -> list[ConsumedEvent]: ...
    def commit(self) -> None: ...
    def close(self) -> None: ...
```

`etl/watermark.py`：

```python
from datetime import datetime, timedelta


def lower_bound(last_watermark: datetime | None, overlap_seconds: int) -> datetime | None:
    if last_watermark is None:
        return None
    return last_watermark - timedelta(seconds=overlap_seconds)


def advance_watermark(current: datetime | None, candidate: datetime | None) -> datetime | None:
    if candidate is None:
        return current
    if current is None or candidate > current:
        return candidate
    return current
```

`etl/extractor.py`：

```python
from dataclasses import dataclass
from datetime import datetime
from uuid import uuid4

from etl.envelope import Envelope
from etl.ports.event_bus import EventPublisher
from etl.ports.run_store import RunStore
from etl.ports.source_repository import SourceRepository
from etl.watermark import advance_watermark, lower_bound


@dataclass(frozen=True)
class SyncTaskSpec:
    id: int
    source_schema: str
    source_table: str
    target_table: str
    primary_key: str
    watermark_column: str | None
    batch_size: int = 500
    overlap_seconds: int = 60
    full_load_done: bool = False
    last_watermark: datetime | None = None


@dataclass(frozen=True)
class ExtractResult:
    rows_read: int
    rows_written: int
    last_watermark: datetime | None


class Extractor:
    def __init__(self, source: SourceRepository, publisher: EventPublisher, topic_prefix: str):
        self.source = source
        self.publisher = publisher
        self.topic_prefix = topic_prefix

    def run(self, task: SyncTaskSpec, mode: str, run_store: RunStore) -> ExtractResult:
        run_type = "FULL" if mode == "FULL" or not task.full_load_done else "INCREMENTAL"
        run_id = run_store.start_run(task.id, run_type)
        rows_read = 0
        rows_written = 0
        last_watermark = task.last_watermark
        try:
            if run_type == "FULL":
                after_pk = None
                while True:
                    rows = self.source.fetch_full_batch(
                        task.source_schema, task.source_table, ["*"], task.primary_key, after_pk, task.batch_size
                    )
                    if not rows:
                        break
                    self._publish_rows(task, rows)
                    rows_read += len(rows)
                    rows_written += len(rows)
                    after_pk = rows[-1][task.primary_key]
                    run_store.checkpoint(run_id, rows_read, rows_written, {"after_pk": after_pk})
                if task.watermark_column:
                    last_watermark = self.source.max_watermark(
                        task.source_schema, task.source_table, task.watermark_column
                    )
            else:
                if not task.watermark_column or task.last_watermark is None:
                    raise ValueError("incremental task requires watermark column and last watermark")
                cursor_watermark = lower_bound(task.last_watermark, task.overlap_seconds) or task.last_watermark
                cursor_pk = None
                while True:
                    rows = self.source.fetch_incremental_batch(
                        task.source_schema,
                        task.source_table,
                        ["*"],
                        task.primary_key,
                        task.watermark_column,
                        cursor_watermark,
                        cursor_pk,
                        task.batch_size,
                    )
                    if not rows:
                        break
                    self._publish_rows(task, rows)
                    rows_read += len(rows)
                    rows_written += len(rows)
                    cursor_watermark = rows[-1][task.watermark_column]
                    cursor_pk = rows[-1][task.primary_key]
                    last_watermark = advance_watermark(last_watermark, cursor_watermark)
                    run_store.checkpoint(
                        run_id,
                        rows_read,
                        rows_written,
                        {"after_watermark": cursor_watermark.isoformat(), "after_pk": cursor_pk},
                    )
                    if len(rows) < task.batch_size:
                        break
            run_store.finish_run(run_id, "SUCCESS", rows_read, rows_written)
            return ExtractResult(rows_read, rows_written, last_watermark)
        except Exception as exc:
            run_store.finish_run(run_id, "FAILED", rows_read, rows_written, str(exc))
            raise

    def _publish_rows(self, task: SyncTaskSpec, rows: list[dict]) -> None:
        topic = f"{self.topic_prefix}.{task.source_schema}.{task.source_table}"
        for row in rows:
            envelope = Envelope(
                event_id=str(uuid4()),
                event_type="ODS_ROW",
                occurred_at=int(datetime.now().timestamp() * 1000),
                payload={
                    "source_db": task.source_schema,
                    "source_table": task.source_table,
                    "row": row,
                },
            )
            self.publisher.publish(topic, str(row.get(task.primary_key, "")), envelope.to_bytes())
        self.publisher.flush()
```

`app/services/sync_service.py`：

```python
from datetime import datetime

from sqlalchemy.orm import Session

from app.models.metadata import SyncRun, SyncTask
from etl.extractor import ExtractResult, Extractor, SyncTaskSpec
from etl.ports.event_bus import EventPublisher
from etl.ports.source_repository import SourceRepository


class SqlRunStore:
    def __init__(self, db: Session):
        self.db = db

    def start_run(self, task_id: int, run_type: str) -> int:
        run = SyncRun(task_id=task_id, run_type=run_type, status="RUNNING")
        self.db.add(run)
        self.db.commit()
        self.db.refresh(run)
        return run.id

    def checkpoint(self, run_id: int, rows_read: int, rows_written: int, cursor: dict) -> None:
        run = self.db.get(SyncRun, run_id)
        run.rows_read = rows_read
        run.rows_written = rows_written
        run.checkpoint_json = cursor
        self.db.commit()

    def finish_run(self, run_id: int, status: str, rows_read: int, rows_written: int, error: str | None = None) -> None:
        run = self.db.get(SyncRun, run_id)
        run.status = status
        run.rows_read = rows_read
        run.rows_written = rows_written
        run.error_msg = error
        run.finished_at = datetime.utcnow()
        self.db.commit()


class SyncService:
    def __init__(self, db: Session):
        self.db = db

    def create_task(self, datasource_id: int, source_schema: str, source_table: str, target_table: str, primary_key: str = "id", watermark_column: str | None = None, cron: str = "*/10 * * * *") -> SyncTask:
        task = SyncTask(
            datasource_id=datasource_id,
            source_schema=source_schema,
            source_table=source_table,
            target_table=target_table,
            primary_key=primary_key,
            watermark_column=watermark_column,
            cron=cron,
        )
        self.db.add(task)
        self.db.commit()
        self.db.refresh(task)
        return task

    def get_spec(self, task_id: int) -> SyncTaskSpec:
        return self._to_spec(self._get(task_id))

    def run(self, task_id: int, mode: str, source: SourceRepository, publisher: EventPublisher) -> ExtractResult:
        task = self._get(task_id)
        spec = self._to_spec(task)
        result = Extractor(source, publisher, "dp.ods").run(spec, mode, SqlRunStore(self.db))
        task.full_load_done = True
        task.last_watermark = result.last_watermark
        task.last_run_at = datetime.utcnow()
        self.db.commit()
        return result

    def _get(self, task_id: int) -> SyncTask:
        task = self.db.get(SyncTask, task_id)
        if task is None:
            raise ValueError(f"sync task not found: {task_id}")
        return task

    def _to_spec(self, task: SyncTask) -> SyncTaskSpec:
        return SyncTaskSpec(
            id=task.id,
            source_schema=task.source_schema,
            source_table=task.source_table,
            target_table=task.target_table,
            primary_key=task.primary_key,
            watermark_column=task.watermark_column,
            batch_size=task.batch_size,
            overlap_seconds=task.overlap_seconds,
            full_load_done=task.full_load_done,
            last_watermark=task.last_watermark,
        )
```

`app/schemas/sync.py`：

```python
from pydantic import BaseModel


class SyncTaskCreate(BaseModel):
    datasource_id: int
    source_schema: str
    source_table: str
    target_table: str
    primary_key: str = "id"
    watermark_column: str | None = None
    cron: str = "*/10 * * * *"
```

`app/api/sync_tasks.py`：

```python
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.api.deps import get_session, require_staff
from app.core.response import ok
from app.services.sync_service import SyncService

router = APIRouter(prefix="/sync-tasks", tags=["sync-tasks"], dependencies=[Depends(require_staff)])


@router.get("")
def list_tasks(db: Session = Depends(get_session)):
    from app.models.metadata import SyncTask

    rows = db.query(SyncTask).order_by(SyncTask.id.desc()).all()
    return ok([{"id": row.id, "source_table": row.source_table, "target_table": row.target_table, "enabled": row.enabled} for row in rows])


@router.post("")
def create_task(payload: dict, db: Session = Depends(get_session)):
    task = SyncService(db).create_task(
        datasource_id=int(payload["datasource_id"]),
        source_schema=payload["source_schema"],
        source_table=payload["source_table"],
        target_table=payload["target_table"],
        primary_key=payload.get("primary_key", "id"),
        watermark_column=payload.get("watermark_column"),
        cron=payload.get("cron", "*/10 * * * *"),
    )
    return ok({"id": task.id})


@router.post("/{task_id}/run")
def run_task(task_id: int, mode: str = "INCREMENTAL", db: Session = Depends(get_session)):
    from app.core.config import get_settings
    from app.models.metadata import DataSource, SyncTask
    from app.services.datasource_service import DatasourceService
    from etl.adapters.factory import build_event_bus
    from etl.adapters.mysql_source_repository import MySQLSourceRepository

    task = db.get(SyncTask, task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="sync task not found")
    datasource = db.get(DataSource, task.datasource_id)
    if datasource is None:
        raise HTTPException(status_code=404, detail="datasource not found")
    settings = get_settings()
    source = MySQLSourceRepository(DatasourceService(db, settings.dp_secret_key).url_for(datasource))
    publisher = build_event_bus(settings.event_bus, settings.kafka_bootstrap, settings.kafka_consumer_group)
    result = SyncService(db).run(task_id, mode, source, publisher)
    return ok({"rows_read": result.rows_read, "rows_written": result.rows_written})
```

- [ ] **Step 4: 运行测试**

Run: `python -m unittest tests.unit.test_envelope tests.unit.test_extractor -v`
Expected: PASS。

- [ ] **Step 5: 静态编译检查**

Run: `python -m compileall -q app etl tests`
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add data-platform/app data-platform/etl data-platform/tests/unit/test_envelope.py data-platform/tests/unit/test_extractor.py
git commit -m "feat(data-platform): add sync task extraction pipeline"
```

---

### Task 4: 清洗、幂等消费与隔离

**Files:**
- Create: `etl/ports/olap_store.py`, `etl/adapters/inmemory_event_bus.py`, `etl/adapters/inmemory_olap_store.py`, `etl/cleaning.py`, `etl/consumer.py`
- Test: `tests/unit/test_watermark.py`, `tests/unit/test_cleaning.py`, `tests/unit/test_consumer.py`

**Interfaces:**
- Produces: `OlapStore`、`InMemoryEventBus`、`InMemoryOlapStore`、`CleanResult`、`clean_row()`、`ConsumerPipeline`。
- Consumes: `Envelope`、`ConsumedEvent`、`EventPublisher`、`EventConsumer`（Task 3 已定义）。

- [ ] **Step 1: 写失败测试**

```python
# tests/unit/test_watermark.py
import unittest
from datetime import datetime

from etl.watermark import advance_watermark, lower_bound


class WatermarkTest(unittest.TestCase):
    def test_lower_bound_applies_overlap(self):
        value = lower_bound(datetime(2026, 11, 4, 12, 0, 0), 60)
        self.assertEqual(value, datetime(2026, 11, 4, 11, 59, 0))

    def test_advance_keeps_maximum(self):
        current = datetime(2026, 11, 4, 12, 0, 0)
        self.assertEqual(advance_watermark(current, datetime(2026, 11, 4, 11, 0, 0)), current)
        self.assertEqual(advance_watermark(current, datetime(2026, 11, 4, 13, 0, 0)), datetime(2026, 11, 4, 13, 0, 0))
```

```python
# tests/unit/test_cleaning.py
import unittest

from etl.cleaning import clean_registration


class CleaningTest(unittest.TestCase):
    def test_masks_pii_and_normalizes(self):
        result = clean_registration({
            "id": 1,
            "registration_no": "R001",
            "hospital_id": 1,
            "department_id": 2,
            "doctor_user_id": 3,
            "schedule_date": "2026-11-04",
            "period": 1,
            "patient_name": "张三",
            "patient_phone": "13800138000",
            "patient_id_card": "110101199001011234",
            "source_type": 1,
            "pay_status": 1,
            "pay_amount": "10.50",
            "status": 3,
            "create_time": "2026-11-04 09:00:00",
            "update_time": "2026-11-04 09:30:00",
        })
        self.assertEqual(result.errors, ())
        self.assertEqual(result.row["patient_name_masked"], "张*")
        self.assertEqual(result.row["patient_phone_masked"], "138****8000")
        self.assertNotIn("patient_id_card", result.row)
        self.assertEqual(result.row["pay_amount"], 10.5)

    def test_rejects_invalid_status(self):
        result = clean_registration({"id": 1, "hospital_id": 1, "status": 99, "schedule_date": "2026-11-04"})
        self.assertTrue(any("status" in error for error in result.errors))
```

```python
# tests/unit/test_consumer.py
import unittest
from datetime import datetime

from etl.adapters.inmemory_event_bus import InMemoryEventBus
from etl.adapters.inmemory_olap_store import InMemoryOlapStore
from etl.consumer import ConsumerPipeline
from etl.envelope import Envelope


def make_event(table: str, row: dict, event_id: str = "e-1") -> bytes:
    return Envelope(
        event_id=event_id,
        event_type="ODS_ROW",
        occurred_at=int(datetime(2026, 11, 4, 12, 0, 0).timestamp() * 1000),
        payload={"source_db": "registration_db", "source_table": table, "row": row},
    ).to_bytes()


class ConsumerTest(unittest.TestCase):
    def test_inserts_valid_rows_and_masks_pii(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        bus.publish("dp.ods.registration_db.registration", "1", make_event("registration", {
            "id": 1, "hospital_id": 1, "status": 3, "schedule_date": "2026-11-04", "period": 1,
            "patient_name": "张三", "patient_phone": "13800138000", "pay_amount": 10,
        }))
        result = ConsumerPipeline(bus, olap).run_once()
        self.assertEqual(result.inserted, 1)
        rows = olap.tables["ods_registration"]
        self.assertEqual(rows[0]["patient_name_masked"], "张*")
        self.assertTrue(bus.committed)

    def test_duplicate_event_is_idempotent(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        payload = make_event("registration", {"id": 1, "hospital_id": 1, "status": 3, "schedule_date": "2026-11-04", "period": 1})
        bus.publish("dp.ods", "1", payload)
        bus.publish("dp.ods", "1", payload)
        result = ConsumerPipeline(bus, olap).run_once()
        self.assertEqual(result.inserted, 1)
        self.assertEqual(olap.count("ods_registration"), 1)

    def test_bad_row_quarantined(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        bus.publish("dp.ods", "1", make_event("registration", {"id": 1, "hospital_id": 1, "status": 99}))
        result = ConsumerPipeline(bus, olap).run_once()
        self.assertEqual(result.quarantined, 1)
        self.assertEqual(olap.count("ops_quarantine"), 1)
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `python -m unittest tests.unit.test_watermark tests.unit.test_cleaning tests.unit.test_consumer -v`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 写最小实现**

`etl/ports/olap_store.py`：

```python
from typing import Any, Protocol


class OlapStore(Protocol):
    def insert_json(self, table: str, rows: list[dict[str, Any]]) -> None: ...
    def execute(self, sql: str) -> None: ...
    def query(self, sql: str) -> list[dict[str, Any]]: ...
    def count(self, table: str) -> int: ...
```

`etl/adapters/inmemory_event_bus.py`：

```python
from etl.ports.event_bus import ConsumedEvent


class InMemoryEventBus:
    def __init__(self):
        self.pending: list[ConsumedEvent] = []
        self.committed = False
        self.closed = False
        self.subscribed_topics: list[str] = []

    def subscribe(self, topics: list[str]) -> None:
        self.subscribed_topics = list(topics)

    def publish(self, topic: str, key: str, value: bytes) -> None:
        self.pending.append(ConsumedEvent(topic=topic, key=key, value=value))

    def flush(self) -> None:
        pass

    def poll(self, timeout_seconds: float = 1.0, max_records: int = 500) -> list[ConsumedEvent]:
        batch = self.pending[:max_records]
        self.pending = self.pending[max_records:]
        return batch

    def commit(self) -> None:
        self.committed = True

    def close(self) -> None:
        self.closed = True
```

`etl/adapters/inmemory_olap_store.py`：

```python
from typing import Any


class InMemoryOlapStore:
    def __init__(self):
        self.tables: dict[str, list[dict[str, Any]]] = {}
        self.executed: list[str] = []
        self._event_ids: set[str] = set()

    def insert_json(self, table: str, rows: list[dict[str, Any]]) -> None:
        for row in rows:
            event_id = row.get("_event_id")
            if event_id and event_id in self._event_ids:
                continue
            if event_id:
                self._event_ids.add(event_id)
            self.tables.setdefault(table, []).append(dict(row))

    def execute(self, sql: str) -> None:
        self.executed.append(sql)

    def query(self, sql: str) -> list[dict[str, Any]]:
        return []

    def count(self, table: str) -> int:
        return len(self.tables.get(table, []))
```

`etl/cleaning.py`：

```python
import re
from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class CleanResult:
    row: dict[str, Any] | None
    errors: tuple[str, ...] = ()


def mask_name(value: str | None) -> str:
    if not value:
        return ""
    return value[0] + "*" * max(len(value) - 1, 1)


def mask_phone(value: str | None) -> str:
    digits = re.sub(r"\D", "", value or "")
    if len(digits) < 7:
        return "*" * len(digits)
    return digits[:3] + "*" * (len(digits) - 7) + digits[-4:]


def clean_registration(row: dict[str, Any]) -> CleanResult:
    errors: list[str] = []
    try:
        entity_id = int(row["id"])
        hospital_id = int(row["hospital_id"])
    except (KeyError, TypeError, ValueError):
        return CleanResult(None, ("id and hospital_id are required",))
    if not row.get("schedule_date"):
        errors.append("schedule_date is required")
    status = int(row.get("status", 0))
    if status not in (1, 2, 3, 4, 5, 6):
        errors.append(f"status out of range: {status}")
    period = int(row.get("period", 0))
    if period not in (1, 2):
        errors.append(f"period invalid: {period}")
    pay_amount = float(row.get("pay_amount") or 0)
    if pay_amount < 0:
        errors.append("pay_amount must be >= 0")
    if errors:
        return CleanResult(None, tuple(errors))
    cleaned = {
        "id": entity_id,
        "registration_no": str(row.get("registration_no", "")),
        "hospital_id": hospital_id,
        "department_id": int(row.get("department_id") or 0),
        "doctor_user_id": int(row.get("doctor_user_id") or 0),
        "schedule_date": row["schedule_date"],
        "period": period,
        "patient_name_masked": mask_name(row.get("patient_name")),
        "patient_phone_masked": mask_phone(row.get("patient_phone")),
        "source_type": int(row.get("source_type") or 0),
        "pay_status": int(row.get("pay_status") or 0),
        "pay_amount": pay_amount,
        "status": status,
        "create_time": row.get("create_time"),
        "update_time": row.get("update_time"),
    }
    return CleanResult(cleaned)


def clean_chat_message(row: dict[str, Any]) -> CleanResult:
    content = row.get("content") or ""
    role = row.get("role")
    if role not in ("user", "assistant"):
        return CleanResult(None, (f"invalid role: {role}",))
    return CleanResult({
        "id": int(row["id"]),
        "session_id": str(row.get("session_id", "")),
        "role": role,
        "content_length": len(content),
        "has_reference": bool(row.get("reference_json")),
        "created_at": row.get("created_at"),
    })


def _pick(row: dict[str, Any], fields: tuple[str, ...]) -> CleanResult:
    if "id" not in row:
        return CleanResult(None, ("id is required",))
    return CleanResult({field: row.get(field) for field in fields})


def clean_chat_session(row: dict[str, Any]) -> CleanResult:
    return _pick(row, ("id", "session_id", "user_id", "identity", "session_type", "hospital_id", "role_id", "created_at", "updated_at"))


def clean_doctor_schedule(row: dict[str, Any]) -> CleanResult:
    return _pick(row, ("id", "hospital_id", "department_id", "doctor_user_id", "schedule_date", "period", "total_limit", "registered_count", "status", "update_time"))


def clean_number_source(row: dict[str, Any]) -> CleanResult:
    return _pick(row, ("id", "hospital_id", "department_id", "doctor_user_id", "schedule_id", "schedule_date", "period", "seq_no", "registration_id", "status", "create_time"))


def clean_hospital(row: dict[str, Any]) -> CleanResult:
    return _pick(row, ("id", "name", "address", "phone", "status", "update_time"))


def clean_department(row: dict[str, Any]) -> CleanResult:
    return _pick(row, ("id", "hospital_id", "name", "director_user_id", "status", "update_time"))


_CLEANERS: dict[str, Callable[[dict[str, Any]], CleanResult]] = {
    "registration": clean_registration,
    "chat_message": clean_chat_message,
    "chat_session": clean_chat_session,
    "doctor_schedule": clean_doctor_schedule,
    "number_source": clean_number_source,
    "hospital": clean_hospital,
    "department": clean_department,
}


def clean_row(table: str, row: dict[str, Any]) -> CleanResult:
    cleaner = _CLEANERS.get(table)
    if cleaner is None:
        return CleanResult(None, (f"unknown source table: {table}",))
    return cleaner(row)
```

`etl/consumer.py`：

```python
import json
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from uuid import uuid4

from etl.cleaning import clean_row
from etl.envelope import Envelope
from etl.ports.event_bus import EventConsumer, EventPublisher
from etl.ports.olap_store import OlapStore


@dataclass(frozen=True)
class ConsumeResult:
    consumed: int
    inserted: int
    quarantined: int
    failed: int


class ConsumerPipeline:
    def __init__(self, consumer: EventConsumer, olap: OlapStore, dlt_publisher: EventPublisher | None = None, max_retries: int = 3):
        self.consumer = consumer
        self.olap = olap
        self.dlt_publisher = dlt_publisher
        self.max_retries = max_retries

    def run_once(self, max_records: int = 500) -> ConsumeResult:
        events = self.consumer.poll(max_records=max_records)
        batch_id = str(uuid4())
        inserted = 0
        quarantined = 0
        failed = 0
        audit: dict[str, int] = {}
        for event in events:
            try:
                envelope = Envelope.from_bytes(event.value)
                table = envelope.payload["source_table"]
                row = envelope.payload["row"]
                result = clean_row(table, row)
                if result.errors:
                    self.olap.insert_json("ops_quarantine", [{
                        "_event_id": envelope.event_id,
                        "source_table": table,
                        "errors": "; ".join(result.errors),
                        "sample": json.dumps(row, ensure_ascii=False, default=str),
                        "_ingested_at": datetime.now(),
                    }])
                    quarantined += 1
                    continue
                cleaned = dict(result.row)
                cleaned["_event_id"] = envelope.event_id
                cleaned["_source"] = envelope.payload["source_db"]
                cleaned["_ingested_at"] = datetime.now()
                self._insert_with_retry(f"ods_{table}", [cleaned])
                inserted += 1
                audit[table] = audit.get(table, 0) + 1
            except Exception:
                if self.dlt_publisher is not None:
                    self.dlt_publisher.publish("dp.ods.DLT", event.key or "", event.value)
                failed += 1
        for table, rows_ingested in audit.items():
            self.olap.insert_json("ops_ingest_audit", [{
                "batch_id": batch_id,
                "source_table": table,
                "rows_ingested": rows_ingested,
                "_ingested_at": datetime.now(),
            }])
        self.consumer.commit()
        return ConsumeResult(consumed=len(events), inserted=inserted, quarantined=quarantined, failed=failed)

    def _insert_with_retry(self, table: str, rows: list[dict[str, Any]]) -> None:
        last_error: Exception | None = None
        for _ in range(self.max_retries):
            try:
                self.olap.insert_json(table, rows)
                return
            except Exception as exc:
                last_error = exc
        if last_error is not None:
            raise last_error
```

- [ ] **Step 4: 运行测试**

Run: `python -m unittest tests.unit.test_watermark tests.unit.test_cleaning tests.unit.test_consumer -v`
Expected: PASS。

- [ ] **Step 5: 静态编译检查**

Run: `python -m compileall -q app etl tests`
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add data-platform/etl data-platform/tests/unit/test_watermark.py data-platform/tests/unit/test_cleaning.py data-platform/tests/unit/test_consumer.py
git commit -m "feat(data-platform): add cleaning and idempotent consumer"
```

---

### Task 5: Kafka / ClickHouse 适配器与工厂

**Files:**
- Create: `etl/adapters/kafka_event_bus.py`, `etl/adapters/clickhouse_olap_store.py`, `etl/adapters/factory.py`, `etl/consumer_main.py`, `app/scheduler.py`
- Test: `tests/unit/test_factory.py`

**Interfaces:**
- Produces: `KafkaEventBus`、`ClickHouseOlapStore`、`build_event_bus(kind, bootstrap, group_id)`、`build_olap_store(kind, url, database)`、`python -m etl.consumer_main`。
- Consumes: `EventPublisher`、`EventConsumer`、`OlapStore` 协议。

- [ ] **Step 1: 写失败测试**

```python
# tests/unit/test_factory.py
import importlib.util
import unittest

from etl.adapters.factory import build_event_bus, build_olap_store
from etl.adapters.inmemory_event_bus import InMemoryEventBus
from etl.adapters.inmemory_olap_store import InMemoryOlapStore


class FactoryTest(unittest.TestCase):
    def test_memory_event_bus(self):
        self.assertIsInstance(build_event_bus("memory", "127.0.0.1:9092", "g"), InMemoryEventBus)

    def test_memory_olap_store(self):
        self.assertIsInstance(build_olap_store("memory", "http://127.0.0.1:8123", "db"), InMemoryOlapStore)

    @unittest.skipIf(importlib.util.find_spec("confluent_kafka") is not None, "confluent-kafka installed")
    def test_kafka_adapter_reports_missing_dependency(self):
        with self.assertRaises(RuntimeError):
            build_event_bus("kafka", "127.0.0.1:9092", "g")

    @unittest.skipIf(importlib.util.find_spec("clickhouse_connect") is not None, "clickhouse-connect installed")
    def test_clickhouse_adapter_reports_missing_dependency(self):
        with self.assertRaises(RuntimeError):
            build_olap_store("clickhouse", "http://127.0.0.1:8123", "db")
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `python -m unittest tests.unit.test_factory -v`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 写最小实现**

`etl/adapters/kafka_event_bus.py`：

```python
from etl.ports.event_bus import ConsumedEvent

_MISSING = "confluent-kafka is not installed; install the optional 'kafka' dependency group"


class KafkaEventBus:
    def __init__(self, bootstrap: str, group_id: str):
        try:
            from confluent_kafka import Consumer, Producer
        except ImportError as exc:
            raise RuntimeError(_MISSING) from exc
        self._producer = Producer({"bootstrap.servers": bootstrap, "enable.idempotence": True, "acks": "all"})
        self._consumer = Consumer({
            "bootstrap.servers": bootstrap,
            "group.id": group_id,
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        })

    def publish(self, topic: str, key: str, value: bytes) -> None:
        self._producer.produce(topic, key=key, value=value)

    def flush(self) -> None:
        self._producer.flush()

    def subscribe(self, topics: list[str]) -> None:
        self._consumer.subscribe(topics)

    def poll(self, timeout_seconds: float = 1.0, max_records: int = 500) -> list[ConsumedEvent]:
        events: list[ConsumedEvent] = []
        for _ in range(max_records):
            message = self._consumer.poll(timeout_seconds if not events else 0)
            if message is None:
                break
            if message.error():
                continue
            events.append(ConsumedEvent(
                topic=message.topic(),
                key=message.key().decode("utf-8") if message.key() else None,
                value=message.value(),
                partition=message.partition(),
                offset=message.offset(),
            ))
        return events

    def commit(self) -> None:
        self._consumer.commit(asynchronous=False)

    def close(self) -> None:
        self._consumer.close()
```

`etl/adapters/clickhouse_olap_store.py`：

```python
from typing import Any
from urllib.parse import urlparse

_MISSING = "clickhouse-connect is not installed; install the optional 'clickhouse' dependency group"


class ClickHouseOlapStore:
    def __init__(self, url: str, database: str):
        try:
            import clickhouse_connect
        except ImportError as exc:
            raise RuntimeError(_MISSING) from exc
        parsed = urlparse(url)
        self.database = database
        self.client = clickhouse_connect.get_client(
            host=parsed.hostname or "127.0.0.1",
            port=parsed.port or 8123,
            username=parsed.username or "default",
            password=parsed.password or "",
            database=database,
        )

    def insert_json(self, table: str, rows: list[dict[str, Any]]) -> None:
        if not rows:
            return
        columns = list(rows[0].keys())
        data = [[row.get(column) for column in columns] for row in rows]
        self.client.insert(f"{self.database}.{table}", data, column_names=columns)

    def execute(self, sql: str) -> None:
        self.client.command(sql)

    def query(self, sql: str) -> list[dict[str, Any]]:
        result = self.client.query(sql)
        return [dict(zip(result.column_names, row)) for row in result.result_rows]

    def count(self, table: str) -> int:
        result = self.client.query(f"SELECT count() FROM {self.database}.{table}")
        return int(result.result_rows[0][0])
```

`etl/adapters/factory.py`：

```python
from etl.adapters.inmemory_event_bus import InMemoryEventBus
from etl.adapters.inmemory_olap_store import InMemoryOlapStore


def build_event_bus(kind: str, bootstrap: str, group_id: str):
    if kind == "memory":
        return InMemoryEventBus()
    if kind == "kafka":
        from etl.adapters.kafka_event_bus import KafkaEventBus

        return KafkaEventBus(bootstrap, group_id)
    raise ValueError(f"unknown event bus: {kind}")


def build_olap_store(kind: str, url: str, database: str):
    if kind == "memory":
        return InMemoryOlapStore()
    if kind == "clickhouse":
        from etl.adapters.clickhouse_olap_store import ClickHouseOlapStore

        return ClickHouseOlapStore(url, database)
    raise ValueError(f"unknown olap store: {kind}")
```

`etl/consumer_main.py`：

```python
import time

from app.core.config import get_settings
from etl.adapters.factory import build_event_bus, build_olap_store
from etl.consumer import ConsumerPipeline

SOURCE_TABLES = (
    "registration",
    "chat_session",
    "chat_message",
    "doctor_schedule",
    "number_source",
    "hospital",
    "department",
)
SOURCE_SCHEMAS = {
    "registration": "registration_db",
    "chat_session": "chat_db",
    "chat_message": "chat_db",
    "doctor_schedule": "registration_db",
    "number_source": "registration_db",
    "hospital": "hospital_db",
    "department": "hospital_db",
}


def main() -> None:
    settings = get_settings()
    consumer = build_event_bus(settings.event_bus, settings.kafka_bootstrap, settings.kafka_consumer_group)
    olap = build_olap_store(settings.olap_store, settings.clickhouse_url, settings.clickhouse_database)
    topics = [f"{settings.kafka_topic_prefix}.{SOURCE_SCHEMAS[table]}.{table}" for table in SOURCE_TABLES]
    consumer.subscribe(topics)
    pipeline = ConsumerPipeline(consumer, olap)
    try:
        while True:
            result = pipeline.run_once()
            if result.consumed == 0:
                time.sleep(1)
    except KeyboardInterrupt:
        consumer.close()


if __name__ == "__main__":
    main()
```

`app/scheduler.py`（默认导入不启动，由 `app/main.py` 或手工调用 `start_scheduler()` 启动）：

```python
from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger

from app.core.config import get_settings
from app.core.db import SessionLocal
from app.models.metadata import DataSource, SyncTask
from app.services.datasource_service import DatasourceService
from app.services.sync_service import SyncService
from etl.adapters.factory import build_event_bus
from etl.adapters.mysql_source_repository import MySQLSourceRepository


def run_task(task_id: int) -> None:
    db = SessionLocal()
    try:
        task = db.get(SyncTask, task_id)
        if task is None:
            return
        datasource = db.get(DataSource, task.datasource_id)
        if datasource is None:
            return
        settings = get_settings()
        source = MySQLSourceRepository(DatasourceService(db, settings.dp_secret_key).url_for(datasource))
        publisher = build_event_bus(settings.event_bus, settings.kafka_bootstrap, settings.kafka_consumer_group)
        SyncService(db).run(task_id, "INCREMENTAL", source, publisher)
    finally:
        db.close()


def build_scheduler() -> BackgroundScheduler:
    scheduler = BackgroundScheduler(timezone="Asia/Shanghai")
    db = SessionLocal()
    try:
        for task in db.query(SyncTask).filter(SyncTask.enabled.is_(True)).all():
            scheduler.add_job(
                run_task,
                CronTrigger.from_crontab(task.cron, timezone="Asia/Shanghai"),
                args=[task.id],
                id=f"sync-{task.id}",
                replace_existing=True,
            )
    finally:
        db.close()
    return scheduler


def start_scheduler() -> BackgroundScheduler:
    scheduler = build_scheduler()
    scheduler.start()
    return scheduler
```
Expected: memory 测试 PASS；依赖缺失测试 PASS（RuntimeError）；依赖已安装时对应测试 SKIP。

- [ ] **Step 5: 静态编译检查**

Run: `python -m compileall -q app etl tests`
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add data-platform/etl/adapters data-platform/tests/unit/test_factory.py
git commit -m "feat(data-platform): add kafka and clickhouse adapters"
```

---

### Task 6: ClickHouse DDL 与转换 SQL

**Files:**
- Create: `etl/sql/ddl/clickhouse.sql`, `etl/sql/dwd/dwd_registration.sql`, `etl/sql/dwd/dwd_ai_session.sql`, `etl/sql/dwd/dwd_ai_message.sql`, `etl/sql/ads/ads_hospital_daily.sql`, `etl/sql/ads/ads_department_daily.sql`, `etl/sql/ads/ads_ai_daily.sql`, `etl/sql/ads/ads_pipeline_health.sql`, `etl/transform.py`
- Test: `tests/unit/test_transform_runner.py`, `tests/unit/test_sql_files.py`

**Interfaces:**
- Produces: `TransformRunner.run_stage(stage, params)`、`render_sql()`。
- SQL 模板变量：`{{ds}}`（日期，`YYYY-MM-DD`）。

- [ ] **Step 1: 写失败测试**

```python
# tests/unit/test_transform_runner.py
import tempfile
import unittest
from pathlib import Path

from etl.transform import TransformRunner, render_sql


class FakeOlap:
    def __init__(self):
        self.executed = []

    def execute(self, sql):
        self.executed.append(sql)


class TransformRunnerTest(unittest.TestCase):
    def test_render_replaces_ds(self):
        self.assertEqual(render_sql("WHERE d = '{{ds}}'", {"ds": "2026-11-04"}), "WHERE d = '2026-11-04'")

    def test_run_stage_executes_sql_files_in_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "dwd").mkdir()
            (root / "dwd" / "b.sql").write_text("SELECT 2", encoding="utf-8")
            (root / "dwd" / "a.sql").write_text("SELECT 1", encoding="utf-8")
            olap = FakeOlap()
            TransformRunner(olap, root).run_stage("dwd", {"ds": "2026-11-04"})
            self.assertEqual(olap.executed, ["SELECT 1", "SELECT 2"])
```

```python
# tests/unit/test_sql_files.py
import unittest
from pathlib import Path


class SqlFilesTest(unittest.TestCase):
    def test_core_sql_files_exist(self):
        root = Path(__file__).resolve().parents[2] / "etl" / "sql"
        for relative in (
            "ddl/clickhouse.sql",
            "dwd/dwd_registration.sql",
            "dwd/dwd_ai_session.sql",
            "dwd/dwd_ai_message.sql",
            "ads/ads_hospital_daily.sql",
            "ads/ads_department_daily.sql",
            "ads/ads_ai_daily.sql",
            "ads/ads_pipeline_health.sql",
        ):
            self.assertTrue((root / relative).exists(), relative)
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `python -m unittest tests.unit.test_transform_runner tests.unit.test_sql_files -v`
Expected: FAIL（模块和 SQL 文件不存在）。

- [ ] **Step 3: 写最小实现**

`etl/transform.py`：

```python
import re
from pathlib import Path

from etl.ports.olap_store import OlapStore

_TEMPLATE = re.compile(r"\{\{(\w+)\}\}")


def render_sql(sql: str, params: dict[str, str]) -> str:
    def replace(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in params:
            raise ValueError(f"missing SQL parameter: {key}")
        return params[key]

    return _TEMPLATE.sub(replace, sql)


class TransformRunner:
    def __init__(self, olap: OlapStore, sql_root: Path):
        self.olap = olap
        self.sql_root = Path(sql_root)

    def run_stage(self, stage: str, params: dict[str, str]) -> list[str]:
        executed: list[str] = []
        for path in sorted((self.sql_root / stage).glob("*.sql")):
            self.olap.execute(render_sql(path.read_text(encoding="utf-8"), params))
            executed.append(path.name)
        return executed
```

`etl/sql/ddl/clickhouse.sql`（核心表，全部使用 `IF NOT EXISTS`）：

```sql
CREATE DATABASE IF NOT EXISTS data_platform;

CREATE TABLE IF NOT EXISTS data_platform.ods_registration (
  id UInt64, registration_no String, hospital_id UInt64, department_id UInt64,
  doctor_user_id UInt64, schedule_date Date, period UInt8,
  patient_name_masked String, patient_phone_masked String,
  source_type UInt8, pay_status UInt8, pay_amount Decimal(10,2), status UInt8,
  create_time DateTime, update_time DateTime,
  _event_id String, _source String, _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(schedule_date)
ORDER BY (hospital_id, schedule_date, id)
TTL toDateTime(schedule_date) + INTERVAL 2 YEAR;

CREATE TABLE IF NOT EXISTS data_platform.ods_chat_session (
  id UInt64, session_id String, user_id Nullable(UInt64), identity Nullable(String),
  session_type Nullable(String), hospital_id Nullable(UInt64), role_id Nullable(String),
  created_at Nullable(DateTime), updated_at Nullable(DateTime),
  _event_id String, _source String, _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (hospital_id, id);

CREATE TABLE IF NOT EXISTS data_platform.ods_chat_message (
  id UInt64, session_id String, role String, content_length UInt32,
  has_reference UInt8, created_at Nullable(DateTime),
  _event_id String, _source String, _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (session_id, id);

CREATE TABLE IF NOT EXISTS data_platform.ods_doctor_schedule (
  id UInt64, hospital_id UInt64, department_id UInt64, doctor_user_id UInt64,
  schedule_date Date, period UInt8, total_limit UInt32, registered_count UInt32,
  status UInt8, update_time DateTime, _event_id String, _source String, _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(schedule_date)
ORDER BY (hospital_id, schedule_date, id);

CREATE TABLE IF NOT EXISTS data_platform.ods_number_source (
  id UInt64, hospital_id UInt64, department_id UInt64, doctor_user_id UInt64,
  schedule_id UInt64, schedule_date Date, period UInt8, seq_no UInt32,
  registration_id Nullable(UInt64), status UInt8, create_time DateTime,
  _event_id String, _source String, _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(schedule_date)
ORDER BY (hospital_id, schedule_date, id);

CREATE TABLE IF NOT EXISTS data_platform.ods_hospital (
  id UInt64, name String, address String, phone String, status UInt8,
  update_time DateTime, _event_id String, _source String, _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
ORDER BY id;

CREATE TABLE IF NOT EXISTS data_platform.ods_department (
  id UInt64, hospital_id UInt64, name String, director_user_id Nullable(UInt64),
  status UInt8, update_time DateTime, _event_id String, _source String, _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
ORDER BY (hospital_id, id);

CREATE TABLE IF NOT EXISTS data_platform.dwd_registration (
  id UInt64, registration_no String, hospital_id UInt64, department_id UInt64,
  doctor_user_id UInt64, schedule_date Date, period UInt8, source_type UInt8,
  pay_status UInt8, pay_amount Decimal(10,2), status UInt8,
  create_time DateTime, update_time DateTime, _event_id String, _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(schedule_date)
ORDER BY (hospital_id, schedule_date, id);

CREATE TABLE IF NOT EXISTS data_platform.dwd_ai_session (
  id UInt64, session_id String, hospital_id Nullable(UInt64), session_type Nullable(String),
  identity Nullable(String), created_at Nullable(DateTime), updated_at Nullable(DateTime),
  _event_id String, _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (hospital_id, id);

CREATE TABLE IF NOT EXISTS data_platform.dwd_ai_message (
  id UInt64, session_id String, role String, content_length UInt32,
  has_reference UInt8, created_at Nullable(DateTime), _event_id String, _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (session_id, id);

CREATE VIEW IF NOT EXISTS data_platform.dwd_hospital AS
SELECT id, name, address, phone, status, update_time
FROM data_platform.ods_hospital FINAL WHERE status = 1;

CREATE VIEW IF NOT EXISTS data_platform.dwd_department AS
SELECT id, hospital_id, name, director_user_id, status, update_time
FROM data_platform.ods_department FINAL WHERE status = 1;

CREATE VIEW IF NOT EXISTS data_platform.dwd_schedule AS
SELECT id, hospital_id, department_id, doctor_user_id, schedule_date, period,
       total_limit, registered_count, status, update_time
FROM data_platform.ods_doctor_schedule FINAL;

CREATE TABLE IF NOT EXISTS data_platform.ads_hospital_daily (
  metric_date Date, hospital_id UInt64, reg_count UInt64, paid_amount Decimal(12,2),
  cancel_rate Float64, visit_rate Float64, ai_sessions UInt64, rag_hit_rate Float64,
  updated_at DateTime
) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(metric_date)
ORDER BY (metric_date, hospital_id);

CREATE TABLE IF NOT EXISTS data_platform.ads_department_daily (
  metric_date Date, hospital_id UInt64, department_id UInt64,
  reg_count UInt64, visit_count UInt64, cancel_count UInt64, updated_at DateTime
) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(metric_date)
ORDER BY (metric_date, hospital_id, department_id);

CREATE TABLE IF NOT EXISTS data_platform.ads_ai_daily (
  metric_date Date, hospital_id Nullable(UInt64), session_count UInt64,
  message_count UInt64, rag_hit_rate Float64, updated_at DateTime
) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(metric_date)
ORDER BY (metric_date, hospital_id);

CREATE TABLE IF NOT EXISTS data_platform.ads_pipeline_health (
  metric_time DateTime, source_table String, rows_ingested UInt64,
  lag_seconds Int64, updated_at DateTime
) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(metric_time)
ORDER BY (metric_time, source_table);

CREATE TABLE IF NOT EXISTS data_platform.ads_quality_daily (
  metric_date Date, rule_id UInt64, rule_name String, total_rows UInt64,
  bad_rows UInt64, pass_rate Float64, severity String, status String, checked_at DateTime
) ENGINE = ReplacingMergeTree(checked_at)
PARTITION BY toYYYYMM(metric_date)
ORDER BY (metric_date, rule_id);

CREATE TABLE IF NOT EXISTS data_platform.ops_quarantine (
  _event_id String, source_table String, errors String, sample String, _ingested_at DateTime
) ENGINE = MergeTree
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (_ingested_at, source_table);

CREATE TABLE IF NOT EXISTS data_platform.ops_ingest_audit (
  batch_id String, source_table String, rows_ingested UInt64, _ingested_at DateTime
) ENGINE = MergeTree
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (_ingested_at, source_table);

CREATE TABLE IF NOT EXISTS data_platform.ads_realtime_registration_5m (
  window_start DateTime, window_end DateTime, hospital_id UInt64,
  event_type String, cnt UInt64
) ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(window_start)
ORDER BY (window_start, hospital_id, event_type);
```

`etl/sql/dwd/dwd_registration.sql`：

```sql
INSERT INTO data_platform.dwd_registration
SELECT id, registration_no, hospital_id, department_id, doctor_user_id,
       schedule_date, period, source_type, pay_status, pay_amount, status,
       create_time, update_time, _event_id, now()
FROM data_platform.ods_registration FINAL
WHERE schedule_date = '{{ds}}';
```

`etl/sql/dwd/dwd_ai_session.sql`：

```sql
INSERT INTO data_platform.dwd_ai_session
SELECT id, session_id, hospital_id, session_type, identity, created_at, updated_at, _event_id, now()
FROM data_platform.ods_chat_session FINAL
WHERE toDate(created_at) = '{{ds}}';
```

`etl/sql/dwd/dwd_ai_message.sql`：

```sql
INSERT INTO data_platform.dwd_ai_message
SELECT id, session_id, role, content_length, has_reference, created_at, _event_id, now()
FROM data_platform.ods_chat_message FINAL
WHERE toDate(created_at) = '{{ds}}';
```

`etl/sql/ads/ads_hospital_daily.sql`：

```sql
INSERT INTO data_platform.ads_hospital_daily
SELECT
  r.schedule_date AS metric_date,
  r.hospital_id,
  count() AS reg_count,
  sumIf(r.pay_amount, r.pay_status = 1) AS paid_amount,
  countIf(r.status IN (4, 5, 6)) / count() AS cancel_rate,
  countIf(r.status = 3) / nullIf(countIf(r.status IN (2, 3)), 0) AS visit_rate,
  s.session_count AS ai_sessions,
  s.rag_hit_rate AS rag_hit_rate,
  now() AS updated_at
FROM data_platform.dwd_registration r
LEFT JOIN (
  SELECT s.hospital_id,
         uniqExact(s.session_id) AS session_count,
         countIf(m.role = 'assistant' AND m.has_reference = 1) / nullIf(countIf(m.role = 'assistant'), 0) AS rag_hit_rate
  FROM data_platform.dwd_ai_session s
  LEFT JOIN data_platform.dwd_ai_message m ON m.session_id = s.session_id
  WHERE toDate(s.created_at) = '{{ds}}'
  GROUP BY s.hospital_id
) s ON s.hospital_id = r.hospital_id
WHERE r.schedule_date = '{{ds}}'
GROUP BY r.schedule_date, r.hospital_id, s.session_count, s.rag_hit_rate;
```

`etl/sql/ads/ads_department_daily.sql`：

```sql
INSERT INTO data_platform.ads_department_daily
SELECT schedule_date AS metric_date, hospital_id, department_id,
       count() AS reg_count,
       countIf(status = 3) AS visit_count,
       countIf(status IN (4, 5, 6)) AS cancel_count,
       now() AS updated_at
FROM data_platform.dwd_registration
WHERE schedule_date = '{{ds}}'
GROUP BY schedule_date, hospital_id, department_id;
```

`etl/sql/ads/ads_ai_daily.sql`：

```sql
INSERT INTO data_platform.ads_ai_daily
SELECT toDate(s.created_at) AS metric_date, s.hospital_id,
       uniqExact(s.session_id) AS session_count,
       countIf(m.role <> '') AS message_count,
       countIf(m.role = 'assistant' AND m.has_reference = 1) / nullIf(countIf(m.role = 'assistant'), 0) AS rag_hit_rate,
       now() AS updated_at
FROM data_platform.dwd_ai_session s
LEFT JOIN data_platform.dwd_ai_message m ON m.session_id = s.session_id
WHERE toDate(s.created_at) = '{{ds}}'
GROUP BY toDate(s.created_at), s.hospital_id;
```

`etl/sql/ads/ads_pipeline_health.sql`：

```sql
INSERT INTO data_platform.ads_pipeline_health
SELECT now() AS metric_time, source_table, sum(rows_ingested) AS rows_ingested,
       dateDiff('second', max(_ingested_at), now()) AS lag_seconds, now() AS updated_at
FROM data_platform.ops_ingest_audit
WHERE toDate(_ingested_at) = '{{ds}}'
GROUP BY source_table;
```

- [ ] **Step 4: 运行测试**

Run: `python -m unittest tests.unit.test_transform_runner tests.unit.test_sql_files -v`
Expected: PASS。

- [ ] **Step 5: 静态编译检查**

Run: `python -m compileall -q app etl tests`
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add data-platform/etl/sql data-platform/etl/transform.py data-platform/tests/unit/test_transform_runner.py data-platform/tests/unit/test_sql_files.py
git commit -m "feat(data-platform): add clickhouse schema and transform sql"
```

---

### Task 7: 数据质量规则

**Files:**
- Create: `etl/quality.py`, `app/services/quality_service.py`, `app/api/quality.py`, `app/schemas/quality.py`
- Test: `tests/unit/test_quality_rules.py`

**Interfaces:**
- Produces: `QualityRule`、`QualityResult`、`build_rule_sql(rule)`、`evaluate_rule(rule, olap)`、`weighted_score(results)`。
- Consumes: `OlapStore`。

- [ ] **Step 1: 写失败测试**

```python
# tests/unit/test_quality_rules.py
import unittest

from etl.quality import QualityRule, build_rule_sql, weighted_score


class QualityRuleTest(unittest.TestCase):
    def test_not_null_sql(self):
        rule = QualityRule(id=1, name="phone not null", table="dwd_registration", rule_type="NOT_NULL", column="registration_no", params={}, threshold=0.99, severity="P0")
        sql = build_rule_sql(rule)
        self.assertIn("countIf(registration_no IS NULL)", sql)
        self.assertIn("dwd_registration", sql)

    def test_enum_sql(self):
        rule = QualityRule(id=2, name="status enum", table="dwd_registration", rule_type="ENUM", column="status", params={"values": [1, 2, 3, 4, 5, 6]}, threshold=1.0, severity="P0")
        sql = build_rule_sql(rule)
        self.assertIn("status NOT IN (1, 2, 3, 4, 5, 6)", sql)

    def test_weighted_score(self):
        from etl.quality import QualityResult

        results = [
            QualityResult(rule_id=1, total_rows=100, bad_rows=0, pass_rate=1.0, status="PASS", severity="P0"),
            QualityResult(rule_id=2, total_rows=100, bad_rows=10, pass_rate=0.9, status="FAIL", severity="P2"),
        ]
        self.assertAlmostEqual(weighted_score(results), (1.0 * 3 + 0.9 * 1) / 4)
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `python -m unittest tests.unit.test_quality_rules -v`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 写最小实现**

`etl/quality.py`：

```python
from dataclasses import dataclass, field
from typing import Any

from etl.ports.olap_store import OlapStore


@dataclass(frozen=True)
class QualityRule:
    id: int
    name: str
    table: str
    rule_type: str
    column: str | None
    params: dict[str, Any] = field(default_factory=dict)
    threshold: float = 1.0
    severity: str = "P1"


@dataclass(frozen=True)
class QualityResult:
    rule_id: int
    total_rows: int
    bad_rows: int
    pass_rate: float
    status: str
    severity: str


def build_rule_sql(rule: QualityRule) -> str:
    table = rule.table
    column = rule.column or ""
    if rule.rule_type == "NOT_NULL":
        return f"SELECT count() AS total_rows, countIf({column} IS NULL) AS bad_rows FROM {table}"
    if rule.rule_type == "UNIQUE":
        return f"SELECT count() AS total_rows, count() - uniqExact({column}) AS bad_rows FROM {table}"
    if rule.rule_type == "RANGE":
        minimum = rule.params["min"]
        maximum = rule.params["max"]
        return f"SELECT count() AS total_rows, countIf({column} < {minimum} OR {column} > {maximum}) AS bad_rows FROM {table}"
    if rule.rule_type == "ENUM":
        values = ", ".join(str(value) for value in rule.params["values"])
        return f"SELECT count() AS total_rows, countIf({column} NOT IN ({values})) AS bad_rows FROM {table}"
    if rule.rule_type == "FRESHNESS":
        return f"SELECT count() AS total_rows, dateDiff('second', max({column}), now()) AS lag_seconds FROM {table}"
    if rule.rule_type == "VOLUME":
        date_column = rule.params.get("date_column", "created_at")
        return (
            f"SELECT toDate({date_column}) AS metric_date, count() AS total_rows FROM {table} "
            f"WHERE {date_column} >= today() - 7 GROUP BY metric_date ORDER BY metric_date"
        )
    if rule.rule_type == "CUSTOM_SQL":
        return rule.params["sql"]
    raise ValueError(f"unknown rule type: {rule.rule_type}")


def evaluate_rule(rule: QualityRule, olap: OlapStore) -> QualityResult:
    rows = olap.query(build_rule_sql(rule))
    if rule.rule_type == "FRESHNESS":
        lag_seconds = int(rows[0]["lag_seconds"]) if rows else 0
        passed = lag_seconds <= rule.threshold
        return QualityResult(rule.id, 1, 0 if passed else 1, 1.0 if passed else 0.0, "PASS" if passed else "FAIL", rule.severity)
    if rule.rule_type == "VOLUME":
        counts = [int(row["total_rows"]) for row in rows if row.get("metric_date")]
        today_count = counts[-1] if counts else 0
        baseline = sum(counts[:-1]) / len(counts[:-1]) if len(counts) > 1 else today_count
        deviation = abs(today_count - baseline) / baseline if baseline else 0.0
        passed = deviation <= rule.threshold
        return QualityResult(rule.id, today_count, 0 if passed else 1, 1.0 if passed else 0.0, "PASS" if passed else "FAIL", rule.severity)
    total = int(rows[0]["total_rows"]) if rows else 0
    bad = int(rows[0]["bad_rows"]) if rows else 0
    pass_rate = 1.0 if total == 0 else (total - bad) / total
    status = "PASS" if pass_rate >= rule.threshold else "FAIL"
    return QualityResult(rule.id, total, bad, pass_rate, status, rule.severity)


def weighted_score(results: list[QualityResult]) -> float:
    weights = {"P0": 3, "P1": 2, "P2": 1}
    total_weight = sum(weights.get(result.severity, 1) for result in results)
    if total_weight == 0:
        return 1.0
    return sum(result.pass_rate * weights.get(result.severity, 1) for result in results) / total_weight
```

`app/services/quality_service.py` 负责规则 CRUD、调用 `evaluate_rule`、写入 `DpQualityResult` 和 `ads_quality_daily`；`app/api/quality.py` 提供规则与结果端点：

```python
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_session, require_staff
from app.core.response import ok
from app.models.metadata import DpQualityResult, DpQualityRule

router = APIRouter(prefix="/quality", tags=["quality"], dependencies=[Depends(require_staff)])


@router.get("/rules")
def list_rules(db: Session = Depends(get_session)):
    rows = db.query(DpQualityRule).order_by(DpQualityRule.id.desc()).all()
    return ok([
        {"id": row.id, "name": row.name, "target_table": row.target_table, "rule_type": row.rule_type, "enabled": row.enabled}
        for row in rows
    ])


@router.post("/rules")
def create_rule(payload: dict, db: Session = Depends(get_session)):
    row = DpQualityRule(
        name=payload["name"],
        target_table=payload["target_table"],
        rule_type=payload["rule_type"],
        column_name=payload.get("column_name"),
        params_json=payload.get("params", {}),
        threshold=float(payload.get("threshold", 1.0)),
        severity=payload.get("severity", "P1"),
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return ok({"id": row.id})


@router.get("/results")
def list_results(db: Session = Depends(get_session)):
    rows = db.query(DpQualityResult).order_by(DpQualityResult.id.desc()).limit(100).all()
    return ok([
        {"id": row.id, "rule_id": row.rule_id, "pass_rate": row.pass_rate, "status": row.status, "checked_at": row.checked_at.isoformat()}
        for row in rows
    ])
```

质量结果写入 ClickHouse 的语句：

```sql
INSERT INTO data_platform.ads_quality_daily
VALUES ('{{ds}}', rule_id, rule_name, total_rows, bad_rows, pass_rate, severity, status, now())
```

- [ ] **Step 4: 运行测试**

Run: `python -m unittest tests.unit.test_quality_rules -v`
Expected: PASS。

- [ ] **Step 5: 静态编译检查**

Run: `python -m compileall -q app etl tests`
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add data-platform/etl/quality.py data-platform/app/services/quality_service.py data-platform/app/api/quality.py data-platform/app/schemas/quality.py data-platform/tests/unit/test_quality_rules.py
git commit -m "feat(data-platform): add data quality rules"
```

---

### Task 8: 告警引擎

**Files:**
- Create: `etl/alerting.py`, `app/services/alert_service.py`, `app/api/alerts.py`, `app/schemas/alert.py`
- Test: `tests/unit/test_alerting.py`

**Interfaces:**
- Produces: `AlertRule`、`AlertEvent`、`fingerprint()`、`should_fire()`、`build_webhook_payload()`。
- Consumes: `DpAlertRule`、`DpAlertRecord`。

- [ ] **Step 1: 写失败测试**

```python
# tests/unit/test_alerting.py
import unittest
from datetime import datetime, timedelta

from etl.alerting import AlertRule, build_webhook_payload, fingerprint, should_fire


class AlertingTest(unittest.TestCase):
    def test_fingerprint_is_stable(self):
        self.assertEqual(fingerprint(1, "hospital:1"), fingerprint(1, "hospital:1"))
        self.assertNotEqual(fingerprint(1, "hospital:1"), fingerprint(1, "hospital:2"))

    def test_cooldown_blocks_duplicate(self):
        rule = AlertRule(id=1, name="lag", metric_type="PIPELINE_LAG", operator=">", threshold=300, window_minutes=5, consecutive_periods=1, severity="P0", cooldown_minutes=30)
        now = datetime(2026, 11, 4, 12, 0, 0)
        self.assertTrue(should_fire(rule, 600, 1, None, now))
        self.assertFalse(should_fire(rule, 600, 1, now - timedelta(minutes=10), now))
        self.assertTrue(should_fire(rule, 600, 1, now - timedelta(minutes=31), now))

    def test_consecutive_periods_required(self):
        rule = AlertRule(id=1, name="lag", metric_type="PIPELINE_LAG", operator=">", threshold=300, window_minutes=5, consecutive_periods=3, severity="P0", cooldown_minutes=30)
        now = datetime(2026, 11, 4, 12, 0, 0)
        self.assertFalse(should_fire(rule, 600, 2, None, now))
        self.assertTrue(should_fire(rule, 600, 3, None, now))

    def test_webhook_payload_contains_title(self):
        event = AlertEvent(rule_id=1, fingerprint="abc", severity="P0", title="链路延迟", content="lag=600s")
        payload = build_webhook_payload(event)
        self.assertIn("链路延迟", payload["text"])
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `python -m unittest tests.unit.test_alerting -v`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 写最小实现**

`etl/alerting.py`：

```python
import hashlib
import operator
from dataclasses import dataclass
from datetime import datetime, timedelta


@dataclass(frozen=True)
class AlertRule:
    id: int
    name: str
    metric_type: str
    operator: str
    threshold: float
    window_minutes: int
    consecutive_periods: int
    severity: str
    cooldown_minutes: int


@dataclass(frozen=True)
class AlertEvent:
    rule_id: int
    fingerprint: str
    severity: str
    title: str
    content: str


_OPERATORS = {
    ">": operator.gt,
    ">=": operator.ge,
    "<": operator.lt,
    "<=": operator.le,
    "==": operator.eq,
}


def fingerprint(rule_id: int, dimension: str) -> str:
    return hashlib.sha256(f"{rule_id}:{dimension}".encode("utf-8")).hexdigest()[:16]


def should_fire(rule: AlertRule, value: float, breach_count: int, last_fired_at: datetime | None, now: datetime) -> bool:
    compare = _OPERATORS.get(rule.operator)
    if compare is None:
        raise ValueError(f"unknown operator: {rule.operator}")
    if not compare(value, rule.threshold):
        return False
    if breach_count < rule.consecutive_periods:
        return False
    if last_fired_at is not None and now - last_fired_at < timedelta(minutes=rule.cooldown_minutes):
        return False
    return True


def build_webhook_payload(event: AlertEvent) -> dict:
    return {
        "msgtype": "text",
        "text": {
            "content": f"[{event.severity}] {event.title}\n{event.content}\nfingerprint={event.fingerprint}"
        },
    }
```

`app/services/alert_service.py` 负责规则 CRUD、按 `fingerprint` 查询最近告警、写入 `DpAlertRecord`、调用 Webhook；`app/api/alerts.py` 提供列表、ack、close 端点：

```python
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.api.deps import get_session, require_staff
from app.core.response import ok
from app.models.metadata import DpAlertRecord

router = APIRouter(prefix="/alerts", tags=["alerts"], dependencies=[Depends(require_staff)])


@router.get("")
def list_alerts(db: Session = Depends(get_session)):
    rows = db.query(DpAlertRecord).order_by(DpAlertRecord.id.desc()).limit(100).all()
    return ok([
        {"id": row.id, "severity": row.severity, "title": row.title, "status": row.status, "created_at": row.created_at.isoformat()}
        for row in rows
    ])


@router.post("/{alert_id}/ack")
def ack(alert_id: int, db: Session = Depends(get_session)):
    row = db.get(DpAlertRecord, alert_id)
    if row is None:
        raise HTTPException(status_code=404, detail="alert not found")
    row.status = "ACKED"
    row.ack_at = datetime.utcnow()
    db.commit()
    return ok({"id": row.id, "status": row.status})


@router.post("/{alert_id}/close")
def close(alert_id: int, db: Session = Depends(get_session)):
    row = db.get(DpAlertRecord, alert_id)
    if row is None:
        raise HTTPException(status_code=404, detail="alert not found")
    row.status = "CLOSED"
    row.closed_at = datetime.utcnow()
    db.commit()
    return ok({"id": row.id, "status": row.status})
```

Webhook 使用 `httpx.post(url, json=payload, timeout=5)`，失败只记录日志。

- [ ] **Step 4: 运行测试**

Run: `python -m unittest tests.unit.test_alerting -v`
Expected: PASS。

- [ ] **Step 5: 静态编译检查**

Run: `python -m compileall -q app etl tests`
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add data-platform/etl/alerting.py data-platform/app/services/alert_service.py data-platform/app/api/alerts.py data-platform/app/schemas/alert.py data-platform/tests/unit/test_alerting.py
git commit -m "feat(data-platform): add alert engine"
```

---

### Task 9: 大屏 API 与 SSE

**Files:**
- Create: `app/services/screen_service.py`, `app/api/screen.py`, `app/schemas/screen.py`
- Test: `tests/unit/test_screen_service.py`

**Interfaces:**
- Produces: `ScreenRepository` 协议、`ScreenService`、`format_sse()`。
- Consumes: 真实实现 `ClickHouseScreenRepository` 放在 `app/services/screen_service.py` 内，使用 `OlapStore.query`。

- [ ] **Step 1: 写失败测试**

```python
# tests/unit/test_screen_service.py
import unittest

from app.services.screen_service import ScreenService, format_sse


class FakeScreenRepository:
    def overview(self):
        return {"reg_count": 10, "ai_sessions": 3}

    def trend(self, days):
        return [{"date": "2026-11-04", "value": 10}]

    def hospital_ranking(self, limit):
        return [{"hospital_id": 1, "name": "第一医院", "value": 10}]

    def department_ranking(self, limit):
        return [{"department_id": 2, "name": "内科", "value": 8}]

    def quality_health(self):
        return {"quality_score": 0.98, "lag_seconds": 12, "open_alerts": 1}

    def recent_alerts(self, limit):
        return [{"severity": "P0", "title": "链路延迟"}]


class ScreenServiceTest(unittest.TestCase):
    def test_overview_contains_kpis(self):
        service = ScreenService(FakeScreenRepository())
        data = service.overview()
        self.assertEqual(data["kpis"][0]["key"], "reg_count")

    def test_sse_format(self):
        self.assertEqual(format_sse({"a": 1}), 'data: {"a": 1}\n\n')
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `python -m unittest tests.unit.test_screen_service -v`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 写最小实现**

`app/services/screen_service.py`：

```python
import json
from typing import Any, Protocol

from etl.ports.olap_store import OlapStore


class ScreenRepository(Protocol):
    def overview(self) -> dict[str, Any]: ...
    def trend(self, days: int) -> list[dict[str, Any]]: ...
    def hospital_ranking(self, limit: int) -> list[dict[str, Any]]: ...
    def department_ranking(self, limit: int) -> list[dict[str, Any]]: ...
    def quality_health(self) -> dict[str, Any]: ...
    def recent_alerts(self, limit: int) -> list[dict[str, Any]]: ...


class ScreenService:
    def __init__(self, repository: ScreenRepository):
        self.repository = repository

    def overview(self) -> dict[str, Any]:
        raw = self.repository.overview()
        return {
            "kpis": [
                {"key": "reg_count", "label": "今日挂号量", "value": raw.get("reg_count", 0)},
                {"key": "ai_sessions", "label": "今日 AI 会话", "value": raw.get("ai_sessions", 0)},
            ],
            "raw": raw,
        }

    def trend(self, days: int = 30) -> list[dict[str, Any]]:
        return self.repository.trend(days)

    def ranking(self, limit: int = 10) -> dict[str, Any]:
        return {
            "hospitals": self.repository.hospital_ranking(limit),
            "departments": self.repository.department_ranking(limit),
        }

    def quality(self) -> dict[str, Any]:
        return {
            "health": self.repository.quality_health(),
            "alerts": self.repository.recent_alerts(10),
        }


def format_sse(data: dict[str, Any]) -> str:
    return f"data: {json.dumps(data, ensure_ascii=False, separators=(',', ':'))}\n\n"


class ClickHouseScreenRepository:
    def __init__(self, olap: OlapStore):
        self.olap = olap

    def overview(self) -> dict[str, Any]:
        rows = self.olap.query(
            "SELECT sum(reg_count) AS reg_count, sum(ai_sessions) AS ai_sessions "
            "FROM data_platform.ads_hospital_daily FINAL WHERE metric_date = today()"
        )
        return rows[0] if rows else {}

    def trend(self, days: int) -> list[dict[str, Any]]:
        return self.olap.query(
            "SELECT metric_date AS date, sum(reg_count) AS value "
            "FROM data_platform.ads_hospital_daily FINAL "
            f"WHERE metric_date >= today() - {int(days)} GROUP BY metric_date ORDER BY metric_date"
        )

    def hospital_ranking(self, limit: int) -> list[dict[str, Any]]:
        return self.olap.query(
            "SELECT hospital_id, sum(reg_count) AS value FROM data_platform.ads_hospital_daily FINAL "
            f"WHERE metric_date = today() GROUP BY hospital_id ORDER BY value DESC LIMIT {int(limit)}"
        )

    def department_ranking(self, limit: int) -> list[dict[str, Any]]:
        return self.olap.query(
            "SELECT department_id, sum(reg_count) AS value FROM data_platform.ads_department_daily FINAL "
            f"WHERE metric_date = today() GROUP BY department_id ORDER BY value DESC LIMIT {int(limit)}"
        )

    def quality_health(self) -> dict[str, Any]:
        rows = self.olap.query(
            "SELECT avg(pass_rate) AS quality_score FROM data_platform.ads_quality_daily FINAL "
            "WHERE metric_date = today()"
        )
        return rows[0] if rows else {"quality_score": 0.0}

    def recent_alerts(self, limit: int) -> list[dict[str, Any]]:
        return self.olap.query(
            "SELECT severity, rule_name AS title, checked_at FROM data_platform.ads_quality_daily FINAL "
            f"WHERE status = 'FAIL' ORDER BY checked_at DESC LIMIT {int(limit)}"
        )
```

`app/api/screen.py`：

```python
import asyncio

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from app.core.config import get_settings
from app.core.response import ok
from app.services.screen_service import ClickHouseScreenRepository, ScreenService, format_sse
from etl.adapters.factory import build_olap_store

router = APIRouter(prefix="/screen", tags=["screen"])


def _service() -> ScreenService:
    settings = get_settings()
    olap = build_olap_store(settings.olap_store, settings.clickhouse_url, settings.clickhouse_database)
    return ScreenService(ClickHouseScreenRepository(olap))


@router.get("/overview")
def overview():
    return ok(_service().overview())


@router.get("/trend")
def trend(days: int = 30):
    return ok(_service().trend(days))


@router.get("/ranking")
def ranking(limit: int = 10):
    return ok(_service().ranking(limit))


@router.get("/quality")
def quality():
    return ok(_service().quality())


@router.get("/stream")
async def stream():
    async def events():
        while True:
            yield format_sse(_service().overview())
            await asyncio.sleep(5)

    return StreamingResponse(events(), media_type="text/event-stream")
```

`app/main.py` 追加路由注册（创建 `app/api/__init__.py`）：

```python
from fastapi import FastAPI

from app.api import alerts, datasources, metadata, quality, screen, sync_tasks
from app.core.db import Base, engine
from app.core.response import ok
import app.models.metadata  # noqa: F401


def create_app() -> FastAPI:
    app = FastAPI(title="data-platform")
    Base.metadata.create_all(bind=engine)
    for router in (datasources.router, metadata.router, sync_tasks.router, quality.router, alerts.router, screen.router):
        app.include_router(router, prefix="/api")

    @app.get("/api/health")
    def health():
        return ok({"status": "ok"})

    return app


app = create_app()
```

- [ ] **Step 4: 运行测试**

Run: `python -m unittest tests.unit.test_screen_service -v`
Expected: PASS。

- [ ] **Step 5: 静态编译检查**

Run: `python -m compileall -q app etl tests`
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add data-platform/app/services/screen_service.py data-platform/app/api/screen.py data-platform/app/schemas/screen.py data-platform/tests/unit/test_screen_service.py
git commit -m "feat(data-platform): add dashboard api and sse"
```

---

### Task 10: Vue 3 大屏与治理后台

**Files:**
- Create: `web/package.json`, `web/vite.config.js`, `web/index.html`, `web/src/main.js`, `web/src/App.vue`, `web/src/router/index.js`, `web/src/api/client.js`, `web/src/mock/screen.js`, `web/src/utils/chartOptions.js`, `web/src/views/ScreenView.vue`, `web/src/views/DatasourcesView.vue`, `web/src/views/SyncTasksView.vue`, `web/src/views/QualityView.vue`, `web/src/views/AlertsView.vue`
- Test: `web/tests/chartOptions.test.mjs`

**Interfaces:**
- Produces: `buildTrendOption(rows)`、`buildRankingOption(rows)`、`formatMetric(value, digits)`；页面路由 `/screen`、`/datasources`、`/tasks`、`/quality`、`/alerts`。
- 所有 API 调用经 `web/src/api/client.js`，mock 模式由 `VITE_USE_MOCK=true` 控制。

- [ ] **Step 1: 写失败测试**

```javascript
// web/tests/chartOptions.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';

import { buildRankingOption, buildTrendOption, formatMetric } from '../src/utils/chartOptions.js';

test('buildTrendOption maps rows into categories and values', () => {
  const option = buildTrendOption([{ date: '2026-11-03', value: 8 }, { date: '2026-11-04', value: 12 }]);
  assert.deepEqual(option.xAxis.data, ['2026-11-03', '2026-11-04']);
  assert.deepEqual(option.series[0].data, [8, 12]);
});

test('buildRankingOption sorts and maps names', () => {
  const option = buildRankingOption([{ name: '内科', value: 12 }, { name: '外科', value: 8 }]);
  assert.deepEqual(option.yAxis.data, ['内科', '外科']);
  assert.deepEqual(option.series[0].data, [12, 8]);
});

test('formatMetric applies digits and fallback', () => {
  assert.equal(formatMetric(1234.5, 1), '1234.5');
  assert.equal(formatMetric(null), '0');
});
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `node --test web/tests/chartOptions.test.mjs`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 写最小实现**

`web/src/utils/chartOptions.js`：

```javascript
export function buildTrendOption(rows) {
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: rows.map((row) => row.date) },
    yAxis: { type: 'value' },
    series: [{ type: 'line', smooth: true, areaStyle: {}, data: rows.map((row) => row.value) }],
  };
}

export function buildRankingOption(rows) {
  const sorted = [...rows].sort((a, b) => b.value - a.value);
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 80, right: 20, top: 20, bottom: 20 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: sorted.map((row) => row.name) },
    series: [{ type: 'bar', data: sorted.map((row) => row.value) }],
  };
}

export function formatMetric(value, digits = 0) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return '0';
  }
  return Number(value).toFixed(digits);
}
```

`web/package.json`：

```json
{
  "name": "data-platform-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "test": "node --test tests/chartOptions.test.mjs"
  },
  "dependencies": {
    "axios": "^1.7.0",
    "echarts": "^5.5.0",
    "element-plus": "^2.8.0",
    "vue": "^3.4.0",
    "vue-router": "^4.3.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "vite": "^5.2.0"
  }
}
```

`web/src/mock/screen.js`：

```javascript
export const overview = {
  kpis: [
    { key: 'reg_count', label: '今日挂号量', value: 128 },
    { key: 'ai_sessions', label: '今日 AI 会话', value: 56 },
    { key: 'quality_score', label: '数据质量分', value: 98.6 },
  ],
};

export const trend = [
  { date: '2026-11-03', value: 96 },
  { date: '2026-11-04', value: 128 },
];

export const ranking = {
  hospitals: [{ hospital_id: 1, name: '第一医院', value: 128 }],
  departments: [{ department_id: 2, name: '内科', value: 64 }],
};

export const quality = {
  health: { quality_score: 0.986, lag_seconds: 12, open_alerts: 1 },
  alerts: [{ severity: 'P0', title: '链路延迟', checked_at: '2026-11-04 12:00:00' }],
};
```

`web/src/api/client.js`：

```javascript
import axios from 'axios';
import { overview, quality, ranking, trend } from '../mock/screen.js';

const http = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL || '/api' });
const useMock = import.meta.env.VITE_USE_MOCK === 'true';

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export async function fetchOverview() {
  if (useMock) return overview;
  return (await http.get('/screen/overview')).data.data;
}

export async function fetchTrend() {
  if (useMock) return trend;
  return (await http.get('/screen/trend')).data.data;
}

export async function fetchRanking() {
  if (useMock) return ranking;
  return (await http.get('/screen/ranking')).data.data;
}

export async function fetchQuality() {
  if (useMock) return quality;
  return (await http.get('/screen/quality')).data.data;
}
```

`web/src/views/ScreenView.vue` 的关键逻辑：

```vue
<template>
  <div class="screen">
    <h1>医院数据治理运营大屏</h1>
    <div class="kpis">
      <div v-for="item in overview.kpis" :key="item.key" class="kpi">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </div>
    </div>
    <div ref="trendRef" class="chart"></div>
    <div ref="rankingRef" class="chart"></div>
  </div>
</template>

<script setup>
import * as echarts from 'echarts';
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { fetchOverview, fetchRanking, fetchTrend } from '../api/client.js';
import { buildRankingOption, buildTrendOption } from '../utils/chartOptions.js';

const overview = ref({ kpis: [] });
const trendRef = ref(null);
const rankingRef = ref(null);
let trendChart;
let rankingChart;
let stream;

onMounted(async () => {
  overview.value = await fetchOverview();
  trendChart = echarts.init(trendRef.value);
  rankingChart = echarts.init(rankingRef.value);
  trendChart.setOption(buildTrendOption(await fetchTrend()));
  const ranking = await fetchRanking();
  rankingChart.setOption(buildRankingOption(ranking.hospitals));
  if (import.meta.env.VITE_USE_MOCK !== 'true') {
    stream = new EventSource('/api/screen/stream');
    stream.onmessage = (event) => {
      overview.value = JSON.parse(event.data);
    };
  }
  window.addEventListener('resize', resize);
});

function resize() {
  trendChart?.resize();
  rankingChart?.resize();
}

onBeforeUnmount(() => {
  stream?.close();
  window.removeEventListener('resize', resize);
  trendChart?.dispose();
  rankingChart?.dispose();
});
</script>

<style scoped>
.screen { min-height: 100vh; padding: 24px; color: #d9f7ff; background: #061423; }
.kpis { display: flex; gap: 16px; }
.kpi { flex: 1; padding: 16px; border: 1px solid #12405c; background: #0a2133; }
.chart { height: 320px; margin-top: 16px; }
</style>
```

治理后台四个页面使用 Element Plus 表格与表单，路由：

```javascript
// web/src/router/index.js
import { createRouter, createWebHistory } from 'vue-router';

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/screen' },
    { path: '/screen', component: () => import('../views/ScreenView.vue') },
    { path: '/datasources', component: () => import('../views/DatasourcesView.vue') },
    { path: '/tasks', component: () => import('../views/SyncTasksView.vue') },
    { path: '/quality', component: () => import('../views/QualityView.vue') },
    { path: '/alerts', component: () => import('../views/AlertsView.vue') },
  ],
});
```

- [ ] **Step 4: 运行测试**

Run: `node --test web/tests/chartOptions.test.mjs`
Expected: PASS（不依赖 npm install）。

- [ ] **Step 5: 静态检查**

Run: `python -m compileall -q app etl tests`
Expected: 无输出。前端 `vite build` 需要安装依赖，本轮不下载、不执行，标记为未验证。

- [ ] **Step 6: 提交**

```bash
git add data-platform/web
git commit -m "feat(data-platform): add vue dashboard and governance pages"
```

---

### Task 11: Flink 代码展示、README 与验证报告

**Files:**
- Create: `flink/jobs/realtime_registration_agg.py`, `flink/sql/realtime_registration_agg.sql`, `flink/Dockerfile`, `flink/README.md`, `README.md`, `docs/verification-2026-11-04.md`, `tests/integration/README.md`
- Test: `python -m py_compile flink/jobs/realtime_registration_agg.py`

**Interfaces:**
- Flink 作业消费 `ai.registration.event`，5 分钟滚动窗口，按医院与事件类型聚合，写入 `ads_realtime_registration_5m`。
- README 必须写明：代码已实现，未部署、未运行验证。

- [ ] **Step 1: 写代码与文档**

`flink/jobs/realtime_registration_agg.py`：

```python
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.table import EnvironmentSettings, StreamTableEnvironment


def main() -> None:
    env = StreamExecutionEnvironment.get_execution_environment()
    env.enable_checkpointing(10_000)
    table_env = StreamTableEnvironment.create(
        env, environment_settings=EnvironmentSettings.in_streaming_mode()
    )

    table_env.execute_sql(
        """
        CREATE TABLE registration_events (
          eventId STRING,
          eventType STRING,
          occurredAt BIGINT,
          payload STRING,
          event_time AS TO_TIMESTAMP_LTZ(occurredAt, 3),
          WATERMARK FOR event_time AS event_time - INTERVAL '30' SECOND
        ) WITH (
          'connector' = 'kafka',
          'topic' = 'ai.registration.event',
          'properties.bootstrap.servers' = '127.0.0.1:9092',
          'properties.group.id' = 'dp-flink-rt-agg',
          'scan.startup.mode' = 'latest-offset',
          'format' = 'json',
          'json.fail-on-missing-field' = 'false',
          'json.ignore-parse-errors' = 'true'
        )
        """
    )

    table_env.execute_sql(
        """
        CREATE TABLE ads_realtime_registration_5m (
          window_start TIMESTAMP(3),
          window_end TIMESTAMP(3),
          hospital_id BIGINT,
          event_type STRING,
          cnt BIGINT
        ) WITH (
          'connector' = 'jdbc',
          'url' = 'jdbc:clickhouse://127.0.0.1:8123/data_platform',
          'table-name' = 'ads_realtime_registration_5m',
          'username' = 'default',
          'password' = ''
        )
        """
    )

    table_env.execute_sql(
        """
        INSERT INTO ads_realtime_registration_5m
        SELECT window_start, window_end,
               CAST(JSON_VALUE(payload, '$.hospitalId') AS BIGINT) AS hospital_id,
               eventType AS event_type,
               COUNT(*) AS cnt
        FROM TABLE(TUMBLE(TABLE registration_events, DESCRIPTOR(event_time), INTERVAL '5' MINUTES))
        WHERE eventType IN ('APPOINTMENT_CREATED', 'APPOINTMENT_CANCELLED', 'REGISTRATION_REFUNDED')
        GROUP BY window_start, window_end, eventType,
                 CAST(JSON_VALUE(payload, '$.hospitalId') AS BIGINT)
        """
    )


if __name__ == "__main__":
    main()
```

`flink/sql/realtime_registration_agg.sql` 保存与上面等价的 Flink SQL；`flink/Dockerfile`：

```dockerfile
FROM flink:1.19-scala_2.12-java17
WORKDIR /opt/flink/usrlib
COPY jobs/ /opt/flink/usrlib/jobs/
COPY sql/ /opt/flink/usrlib/sql/
# 本轮不构建、不启动；仅保留可部署镜像定义。
```

`flink/README.md`：

```markdown
# Flink 实时聚合（代码展示）

状态：代码已实现，**未部署、未运行验证**。

- 输入：Kafka `ai.registration.event`
- 窗口：5 分钟滚动窗口
- 输出：ClickHouse `data_platform.ads_realtime_registration_5m`
- 依赖：Kafka connector、ClickHouse JDBC driver、PyFlink 1.19

本地验证步骤（本轮未执行）：

1. 启动 Kafka 与 ClickHouse。
2. 构建镜像并提交作业。
3. 查询 `ads_realtime_registration_5m` 确认窗口结果。
```

`README.md` 必须包含：

- 项目定位、架构图、目录说明。
- 状态矩阵：已实现 / 已静态验证 / 未验证。
- 明确写出 “Kafka、ClickHouse、Flink 未下载、未部署”。
- 环境变量说明与 `.env` 不入库提示。
- 后续人工验收清单（引用设计文档第 11.3 节）。

`docs/verification-2026-11-04.md` 记录本轮实际执行过的命令与结果；未执行的集成/E2E/Flink 一律写“未执行”，不得写“通过”。报告必须包含：

- 已执行命令与真实输出摘要（PASS / SKIP 数量、编译是否通过）。
- Kafka、ClickHouse、Flink、集成测试、E2E、前端构建各自的执行状态。
- 未执行项的原因与后续人工验收入口（引用设计文档第 11.3 节）。

`tests/integration/README.md`：

```markdown
# 集成测试（未执行）

本目录保留后续接入真实 Kafka / ClickHouse 的测试说明。本轮不下载、不启动基础设施，因此没有任何集成测试结果。
```

- [ ] **Step 2: 运行静态检查**

Run: `python -m py_compile flink/jobs/realtime_registration_agg.py`
Expected: PASS（不导入 PyFlink，仅编译）。

Run: `python -m compileall -q app etl tests`
Expected: 无输出。

Run: `python -m unittest discover -s tests/unit -v`
Expected: 可运行的纯逻辑测试 PASS；依赖缺失的测试 SKIP；不允许 FAIL。

Run: `node --test web/tests/chartOptions.test.mjs`
Expected: PASS。

- [ ] **Step 3: 提交**

```bash
git add data-platform/flink data-platform/README.md data-platform/docs/verification-2026-11-04.md data-platform/tests/integration/README.md
git commit -m "docs(data-platform): add flink showcase and verification report"
```

---

## Self-Review

**Spec coverage:**

- 数据源 / 元数据 / 加密：Task 2。
- 全量 + 增量抽取、水位、断点：Task 3。
- Kafka 事件信封、消费、幂等、隔离：Task 4、Task 5。
- ClickHouse 分层、DDL、转换 SQL：Task 6。
- 质量规则、评分：Task 7。
- 告警规则、去重、冷却、Webhook：Task 8。
- 大屏 API、SSE、Vue 页面：Task 9、Task 10。
- Flink 代码展示、状态标注：Task 11。
- 验证口径：Global Constraints + Task 11。
- 非目标：未实现 Oracle/SQLServer、血缘、多租户、DAG，符合设计文档。

**Placeholder scan:** 无 TBD/TODO；每个任务包含测试、实现、验证命令和提交命令。治理后台四个页面的具体表格字段由 Task 2/3/7/8 的 API 响应结构决定，页面实现按 Element Plus 表格 + 表单完成，不引入未定义接口。

**Type consistency:** `SyncTaskSpec` 字段在 Task 3 定义并在 Extractor 中使用；`QualityRule` / `QualityResult` 在 Task 7 内自洽；`ScreenRepository` 在 Task 9 定义并被 Fake 与 ClickHouse 实现共用；`EventPublisher` / `EventConsumer` / `OlapStore` 在 Task 4 定义，Task 3、5、6、9 复用。

**Known limitation:** `etl/sql/ddl/clickhouse.sql` 是代码级 DDL，未在 ClickHouse 中执行；SQL 语法以 ClickHouse 24.x 为目标，执行阶段如遇版本差异需单独修正。
