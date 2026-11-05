from datetime import datetime
from decimal import Decimal

from sqlalchemy import Boolean, DateTime, Float, Integer, JSON, Numeric, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.core.db import Base
from app.core.time import now_shanghai


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
    create_time: Mapped[datetime] = mapped_column(DateTime, default=now_shanghai)
    update_time: Mapped[datetime] = mapped_column(DateTime, default=now_shanghai, onupdate=now_shanghai)


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
    last_watermark_num: Mapped[Decimal | None] = mapped_column(Numeric(20, 0), nullable=True)
    last_run_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    # Row-level mutex: a run flips ``running`` with a conditional UPDATE so two
    # extractors can never process the same task concurrently. ``locked_at``
    # records when the lock was taken for operators diagnosing a stuck run.
    running: Mapped[bool] = mapped_column(Boolean, default=False)
    locked_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=now_shanghai)


class SyncRun(Base):
    __tablename__ = "dp_sync_run"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    task_id: Mapped[int] = mapped_column(Integer, index=True)
    run_type: Mapped[str] = mapped_column(String(20))
    status: Mapped[str] = mapped_column(String(20))
    started_at: Mapped[datetime] = mapped_column(DateTime, default=now_shanghai)
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
    last_introspected_at: Mapped[datetime] = mapped_column(DateTime, default=now_shanghai)


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
    create_time: Mapped[datetime] = mapped_column(DateTime, default=now_shanghai)


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
    checked_at: Mapped[datetime] = mapped_column(DateTime, default=now_shanghai)


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
    # Alert timestamps follow the Asia/Shanghai wall-clock convention used by
    # the cooldown comparison.  ``AlertService`` always passes ``created_at``
    # explicitly; the default covers direct inserts.
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_shanghai)
    ack_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
