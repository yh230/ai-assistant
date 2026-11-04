"""Demo-mode seed data and simulated governance actions.

``DEMO_MODE=true`` exists so the dashboard and governance pages can run with
only SQLite: no Kafka, ClickHouse or MySQL is contacted.  The seed is
idempotent and refuses to mix with an existing metadata database; the manual
run endpoints return explicitly simulated results instead of pretending that
rows were published or a warehouse was written.
"""

from datetime import timedelta
from typing import Any

from sqlalchemy.orm import Session

from app.core.time import now_shanghai
from app.models.metadata import (
    ColumnMeta,
    DataSource,
    DpAlertRecord,
    DpAlertRule,
    DpQualityResult,
    DpQualityRule,
    SyncRun,
    SyncTask,
    TableMeta,
)

DEMO_DATASOURCE_NAME = "AI 医院业务库（演示）"
DEMO_SYNC_WARNING = "演示模式：未连接业务库、Kafka 与 ClickHouse，未写入任何数据"
DEMO_QUALITY_WARNING = "演示模式：质量结果为模拟值，未写入 ClickHouse"
DEMO_CONNECTION_MESSAGE = "演示模式不连接真实数据源"

_TASKS: tuple[tuple[str, str, str, str, str, int], ...] = (
    ("registration_db", "registration", "ods_registration", "update_time", "datetime", 500),
    (
        "registration_db",
        "doctor_schedule",
        "ods_doctor_schedule",
        "update_time",
        "datetime",
        300,
    ),
    ("registration_db", "number_source", "ods_number_source", "id", "numeric", 500),
    ("chat_db", "chat_session", "ods_chat_session", "update_time", "datetime", 200),
    ("chat_db", "chat_message", "ods_chat_message", "id", "numeric", 1000),
    ("hospital_db", "hospital", "ods_hospital", "update_time", "datetime", 100),
    ("hospital_db", "department", "ods_department", "update_time", "datetime", 100),
)

_QUALITY_RULES: tuple[
    tuple[str, str, str, str | None, dict[str, Any], float, str], ...
] = (
    ("挂号单号非空", "ods_registration", "NOT_NULL", "registration_no", {}, 0.99, "P1"),
    ("挂号单号唯一", "ods_registration", "UNIQUE", "registration_no", {}, 1.0, "P0"),
    (
        "挂号状态枚举",
        "ods_registration",
        "ENUM",
        "status",
        {"values": [0, 1, 2, 3]},
        0.98,
        "P1",
    ),
    ("AI 会话消息非空", "ods_chat_message", "NOT_NULL", "content_length", {}, 0.99, "P2"),
    (
        "挂号金额范围",
        "ods_registration",
        "RANGE",
        "pay_amount",
        {"min": 0, "max": 100000},
        0.95,
        "P2",
    ),
)

_ALERT_RULES: tuple[tuple[str, str, str, float, int, int, str, str, int], ...] = (
    ("链路延迟超阈值", "PIPELINE_LAG", ">", 300, 5, 2, "P0", "webhook", 30),
    ("数据质量分过低", "QUALITY_SCORE", "<", 0.9, 60, 1, "P1", "inapp", 60),
    ("数据新鲜度不足", "DATA_FRESHNESS", ">", 3600, 30, 2, "P1", "inapp", 60),
    ("Kafka 消费积压", "KAFKA_LAG", ">", 10000, 10, 3, "P2", "inapp", 120),
)

_METADATA_SPECS: tuple[tuple[str, str, str, tuple[tuple[str, str, bool, str], ...]], ...] = (
    (
        "registration_db",
        "registration",
        "挂号记录",
        (
            ("id", "bigint", False, "主键"),
            ("registration_no", "varchar(64)", False, "挂号单号"),
            ("hospital_id", "bigint", False, "医院 ID"),
            ("schedule_date", "date", False, "就诊日期"),
            ("status", "tinyint", False, "挂号状态"),
            ("update_time", "datetime", False, "更新时间"),
        ),
    ),
    (
        "chat_db",
        "chat_message",
        "AI 会话消息",
        (
            ("id", "bigint", False, "主键"),
            ("session_id", "varchar(64)", False, "会话 ID"),
            ("role", "varchar(16)", False, "角色"),
            ("content_length", "int", False, "内容长度"),
            ("created_at", "datetime", True, "创建时间"),
        ),
    ),
    (
        "hospital_db",
        "hospital",
        "医院主数据",
        (
            ("id", "bigint", False, "主键"),
            ("name", "varchar(100)", False, "医院名称"),
            ("status", "tinyint", False, "状态"),
            ("update_time", "datetime", False, "更新时间"),
        ),
    ),
)


def _is_empty(db: Session) -> bool:
    models = (
        DataSource,
        SyncTask,
        SyncRun,
        DpQualityRule,
        DpQualityResult,
        DpAlertRule,
        DpAlertRecord,
        TableMeta,
        ColumnMeta,
    )
    return not any(db.query(model).count() for model in models)


def seed_demo_metadata(db: Session) -> dict[str, int]:
    """Insert demo metadata once; return the inserted row counts.

    An existing metadata database is never modified: the demo seed is only for
    an empty SQLite file, so it cannot be mistaken for real governance data.
    """
    if not _is_empty(db):
        return {}

    now = now_shanghai()
    counts: dict[str, int] = {}

    datasource = DataSource(
        name=DEMO_DATASOURCE_NAME,
        db_type="mysql",
        host="10.20.30.40",
        port=3306,
        database_name="hospital_db",
        username="readonly",
        # Demo data: no reachable database and no password to encrypt.
        password_cipher="",
        status=1,
        last_test_at=now - timedelta(minutes=10),
        create_time=now - timedelta(days=30),
        update_time=now - timedelta(minutes=10),
    )
    db.add(datasource)
    db.commit()
    db.refresh(datasource)
    counts["datasources"] = 1

    tasks: list[SyncTask] = []
    for index, (schema, table, target, watermark, watermark_type, batch) in enumerate(
        _TASKS
    ):
        task = SyncTask(
            datasource_id=datasource.id,
            source_schema=schema,
            source_table=table,
            target_table=target,
            primary_key="id",
            watermark_column=watermark,
            watermark_type=watermark_type,
            batch_size=batch,
            overlap_seconds=60,
            cron="*/10 * * * *",
            enabled=True,
            full_load_done=True,
            last_watermark=None if watermark_type == "numeric" else now - timedelta(minutes=8),
            last_watermark_num=102400 + index if watermark_type == "numeric" else None,
            last_run_at=now - timedelta(minutes=6),
            create_time=now - timedelta(days=7),
        )
        db.add(task)
        tasks.append(task)
    db.commit()
    for task in tasks:
        db.refresh(task)
    counts["sync_tasks"] = len(tasks)

    runs: list[SyncRun] = []
    for index, task in enumerate(tasks):
        rows = 140 + (index * 137) % 320
        runs.append(
            SyncRun(
                task_id=task.id,
                run_type="INCREMENTAL",
                status="SUCCESS",
                started_at=now - timedelta(minutes=7),
                finished_at=now - timedelta(minutes=6),
                rows_read=rows,
                rows_written=rows,
            )
        )
        runs.append(
            SyncRun(
                task_id=task.id,
                run_type="FULL",
                status="SUCCESS",
                started_at=now - timedelta(days=1, minutes=12),
                finished_at=now - timedelta(days=1, minutes=10),
                rows_read=rows * 4,
                rows_written=rows * 4,
            )
        )
    runs.append(
        SyncRun(
            task_id=tasks[2].id,
            run_type="INCREMENTAL",
            status="FAILED",
            started_at=now - timedelta(hours=3),
            finished_at=now - timedelta(hours=3) + timedelta(seconds=4),
            rows_read=0,
            rows_written=0,
            error_msg="source connection timed out (demo fixture)",
        )
    )
    db.add_all(runs)
    db.commit()
    counts["sync_runs"] = len(runs)

    rules: list[DpQualityRule] = []
    for name, target, rule_type, column, params, threshold, severity in _QUALITY_RULES:
        rule = DpQualityRule(
            name=name,
            target_table=target,
            rule_type=rule_type,
            column_name=column,
            params_json=params,
            threshold=threshold,
            severity=severity,
            cron="0 * * * *",
            enabled=True,
            create_time=now - timedelta(days=20),
        )
        db.add(rule)
        rules.append(rule)
    db.commit()
    for rule in rules:
        db.refresh(rule)
    counts["quality_rules"] = len(rules)

    # Two results per rule; the UNIQUE rule has one failing run so the screen
    # alert card and the quality results page show a realistic failure.
    results: list[DpQualityResult] = []
    for index, rule in enumerate(rules):
        for bad_rows, minutes_ago in ((index % 3, 12), (0, 180)):
            total_rows = 1180 + index * 37
            pass_rate = round(1 - bad_rows / total_rows, 6)
            results.append(
                DpQualityResult(
                    rule_id=rule.id,
                    total_rows=total_rows,
                    bad_rows=bad_rows,
                    pass_rate=pass_rate,
                    status="PASS" if pass_rate >= rule.threshold else "FAIL",
                    checked_at=now - timedelta(minutes=minutes_ago),
                )
            )
    db.add_all(results)
    db.commit()
    counts["quality_results"] = len(results)

    alert_rules: list[DpAlertRule] = []
    for (
        name,
        metric,
        operator,
        threshold,
        window_minutes,
        consecutive,
        severity,
        channel,
        cooldown,
    ) in _ALERT_RULES:
        rule = DpAlertRule(
            name=name,
            metric_type=metric,
            operator=operator,
            threshold=threshold,
            window_minutes=window_minutes,
            consecutive_periods=consecutive,
            severity=severity,
            channel=channel,
            cooldown_minutes=cooldown,
            enabled=True,
        )
        db.add(rule)
        alert_rules.append(rule)
    db.commit()
    for rule in alert_rules:
        db.refresh(rule)
    counts["alert_rules"] = len(alert_rules)

    alerts = [
        DpAlertRecord(
            rule_id=alert_rules[0].id,
            fingerprint="pipeline_lag:all",
            severity="P0",
            title="链路延迟超过阈值",
            content="lag_seconds=412 超过阈值 300 秒",
            status="OPEN",
            created_at=now - timedelta(minutes=12),
        ),
        DpAlertRecord(
            rule_id=alert_rules[1].id,
            fingerprint="quality_score:registration",
            severity="P1",
            title="数据质量分低于阈值",
            content="quality_score=0.87 低于阈值 0.90",
            status="OPEN",
            created_at=now - timedelta(hours=2),
        ),
        DpAlertRecord(
            rule_id=alert_rules[2].id,
            fingerprint="freshness:ods_registration",
            severity="P1",
            title="数据新鲜度不足",
            content="ods_registration 最新数据已滞后 4200 秒",
            status="ACKED",
            created_at=now - timedelta(hours=6),
            ack_at=now - timedelta(hours=5),
        ),
        DpAlertRecord(
            rule_id=alert_rules[3].id,
            fingerprint="kafka_lag:dp-ods-loader",
            severity="P2",
            title="Kafka 消费积压",
            content="消费组 dp-ods-loader 积压 12480 条",
            status="CLOSED",
            created_at=now - timedelta(days=1),
            ack_at=now - timedelta(hours=23),
            closed_at=now - timedelta(hours=22),
        ),
    ]
    db.add_all(alerts)
    db.commit()
    counts["alert_records"] = len(alerts)

    tables = 0
    columns = 0
    for schema, table, comment, column_specs in _METADATA_SPECS:
        meta = TableMeta(
            datasource_id=datasource.id,
            table_schema=schema,
            table_name=table,
            table_comment=comment,
            row_estimate=1000 + columns * 100,
            last_introspected_at=now - timedelta(minutes=10),
        )
        db.add(meta)
        db.commit()
        db.refresh(meta)
        tables += 1
        for ordinal, (name, data_type, nullable, column_comment) in enumerate(
            column_specs, start=1
        ):
            db.add(
                ColumnMeta(
                    table_meta_id=meta.id,
                    column_name=name,
                    data_type=data_type,
                    nullable=nullable,
                    column_comment=column_comment,
                    ordinal=ordinal,
                )
            )
            columns += 1
        db.commit()
    counts["metadata_tables"] = tables
    counts["metadata_columns"] = columns
    return counts


def simulate_sync_run(db: Session, task: SyncTask, mode: str) -> dict[str, Any]:
    """Record a simulated manual run without contacting any external system."""
    normalized = (mode or "").upper()
    if normalized not in ("FULL", "INCREMENTAL"):
        raise ValueError(f"unsupported sync mode: {mode}")

    now = now_shanghai()
    rows_read = 120 + (task.id * 137 + now.toordinal()) % 380
    run = SyncRun(
        task_id=task.id,
        run_type="FULL" if normalized == "FULL" or not task.full_load_done else "INCREMENTAL",
        status="SUCCESS",
        started_at=now - timedelta(seconds=2),
        finished_at=now,
        rows_read=rows_read,
        # Nothing is published to Kafka and nothing is written to ClickHouse.
        rows_written=0,
    )
    db.add(run)
    task.full_load_done = True
    task.last_run_at = now
    db.commit()
    db.refresh(run)
    return {
        "rows_read": rows_read,
        "rows_written": 0,
        "published": False,
        "demo": True,
        "warning": DEMO_SYNC_WARNING,
        "last_watermark": (
            task.last_watermark.isoformat() if task.last_watermark is not None else None
        ),
        "last_watermark_num": (
            str(task.last_watermark_num) if task.last_watermark_num is not None else None
        ),
    }


def simulate_quality_run(db: Session, rule: DpQualityRule) -> dict[str, Any]:
    """Persist a deterministic simulated quality result."""
    now = now_shanghai()
    total_rows = 1100 + (rule.id * 97) % 900
    bad_rows = (rule.id * 13) % 5
    pass_rate = round(1 - bad_rows / total_rows, 6)
    status = "PASS" if pass_rate >= float(rule.threshold) else "FAIL"
    record = DpQualityResult(
        rule_id=rule.id,
        total_rows=total_rows,
        bad_rows=bad_rows,
        pass_rate=pass_rate,
        status=status,
        checked_at=now,
    )
    db.add(record)
    db.commit()
    db.refresh(record)
    return {
        "result_id": record.id,
        "rule_id": rule.id,
        "total_rows": total_rows,
        "bad_rows": bad_rows,
        "pass_rate": pass_rate,
        "status": status,
        "severity": rule.severity,
        "checked_at": now.isoformat(),
        "olap_written": False,
        "store_kind": "demo",
        "warning": DEMO_QUALITY_WARNING,
    }
