# tests/unit/test_sql_files.py
import re
import unittest
from pathlib import Path

from etl.transform import render_sql

SQL_ROOT = Path(__file__).resolve().parents[2] / "etl" / "sql"

CORE_SQL_FILES = (
    "ddl/clickhouse.sql",
    "dwd/dwd_registration.sql",
    "dwd/dwd_ai_session.sql",
    "dwd/dwd_ai_message.sql",
    "ads/ads_hospital_daily.sql",
    "ads/ads_department_daily.sql",
    "ads/ads_ai_daily.sql",
    "ads/ads_pipeline_health.sql",
)

TRANSFORM_TARGETS = {
    "dwd/dwd_registration.sql": "data_platform.dwd_registration",
    "dwd/dwd_ai_session.sql": "data_platform.dwd_ai_session",
    "dwd/dwd_ai_message.sql": "data_platform.dwd_ai_message",
    "ads/ads_hospital_daily.sql": "data_platform.ads_hospital_daily",
    "ads/ads_department_daily.sql": "data_platform.ads_department_daily",
    "ads/ads_ai_daily.sql": "data_platform.ads_ai_daily",
    "ads/ads_pipeline_health.sql": "data_platform.ads_pipeline_health",
}

MESSAGE_LEVEL_RAG = (
    "countIf(m.role = 'assistant' AND m.has_reference = 1) "
    "/ nullIf(countIf(m.role = 'assistant'), 0)"
)


def read_sql(relative: str) -> str:
    return (SQL_ROOT / relative).read_text(encoding="utf-8")


def table_statement(ddl: str, table: str) -> str:
    match = re.search(
        rf"CREATE TABLE IF NOT EXISTS data_platform\.{table} \(.*?\)\s*ENGINE = [^;]*;",
        ddl,
        flags=re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"no CREATE TABLE statement found for {table}")
    return match.group(0)


def table_columns(statement: str) -> list[tuple[str, str]]:
    body = re.search(r"\((.*)\)\s*ENGINE =", statement, flags=re.DOTALL)
    if body is None:
        raise AssertionError("column list not found")
    columns = []
    for entry in re.split(r",\s*\n", body.group(1).strip()):
        name, type_ = entry.strip().split(None, 1)
        columns.append((name, type_))
    return columns


def normalized(sql: str) -> str:
    return " ".join(sql.split())


class SqlFilesTest(unittest.TestCase):
    def test_core_sql_files_exist(self):
        for relative in CORE_SQL_FILES:
            self.assertTrue((SQL_ROOT / relative).exists(), relative)

    def test_clickhouse_ddl_is_marked_unverified(self):
        ddl = read_sql("ddl/clickhouse.sql")

        self.assertIn("UNVERIFIED", ddl)

    def test_every_create_statement_is_idempotent(self):
        ddl = read_sql("ddl/clickhouse.sql")

        creates = re.findall(r"CREATE\s+(?:DATABASE|TABLE|VIEW)\s+", ddl)
        guarded = re.findall(r"CREATE\s+(?:DATABASE|TABLE|VIEW)\s+IF NOT EXISTS\s+", ddl)

        self.assertTrue(creates)
        self.assertEqual(len(creates), len(guarded))

    def test_quarantine_ddl_is_replay_safe(self):
        statement = table_statement(read_sql("ddl/clickhouse.sql"), "ops_quarantine")
        columns = dict(table_columns(statement))

        self.assertEqual(columns["batch_id"], "String")
        self.assertEqual(columns["sample"], "String")
        self.assertIn("ENGINE = ReplacingMergeTree(_ingested_at)", statement)
        self.assertRegex(
            normalized(statement),
            r"ORDER BY \(source_table, _event_id, batch_id\)",
        )

    def test_ingest_audit_ddl_is_replay_safe(self):
        statement = table_statement(read_sql("ddl/clickhouse.sql"), "ops_ingest_audit")
        columns = dict(table_columns(statement))

        self.assertEqual(columns["batch_id"], "String")
        self.assertIn("ENGINE = ReplacingMergeTree(_ingested_at)", statement)
        self.assertRegex(normalized(statement), r"ORDER BY \(batch_id, source_table\)")

    def test_registration_event_ddl_matches_the_realtime_handler(self):
        statement = table_statement(read_sql("ddl/clickhouse.sql"), "ops_registration_event")

        self.assertEqual(
            table_columns(statement),
            [
                ("_event_id", "String"),
                ("event_type", "String"),
                ("registration_no", "String"),
                ("hospital_id", "Nullable(UInt64)"),
                ("visit_date", "Nullable(Date)"),
                ("period", "Nullable(UInt8)"),
                ("occurred_at", "DateTime"),
                ("_ingested_at", "DateTime"),
            ],
        )
        self.assertIn("ENGINE = ReplacingMergeTree(_ingested_at)", statement)
        self.assertRegex(normalized(statement), r"ORDER BY \(_event_id\)")

    def test_quality_daily_keeps_status_as_string(self):
        statement = table_statement(read_sql("ddl/clickhouse.sql"), "ads_quality_daily")

        self.assertIn(("status", "String"), table_columns(statement))

    def test_nullable_sorting_keys_declare_the_clickhouse_setting(self):
        ddl = read_sql("ddl/clickhouse.sql")

        for table in ("ods_chat_session", "dwd_ai_session", "ads_ai_daily"):
            statement = table_statement(ddl, table)
            self.assertIn("Nullable(", statement, table)
            self.assertIn("SETTINGS allow_nullable_key = 1", normalized(statement), table)

    def test_transform_sql_targets_are_explicit_and_templated(self):
        for relative, target in TRANSFORM_TARGETS.items():
            sql = read_sql(relative)
            self.assertIn(f"INSERT INTO {target}", normalized(sql), relative)
            self.assertIn("{{ds}}", sql, relative)

    def test_transform_sql_renders_for_a_business_date(self):
        for relative in TRANSFORM_TARGETS:
            rendered = render_sql(read_sql(relative), {"ds": "2026-11-04"})

            self.assertNotIn("{{", rendered, relative)
            self.assertNotIn("}}", rendered, relative)

    def test_rag_hit_rate_is_message_level(self):
        for relative in ("ads/ads_hospital_daily.sql", "ads/ads_ai_daily.sql"):
            self.assertIn(MESSAGE_LEVEL_RAG, normalized(read_sql(relative)), relative)


if __name__ == "__main__":
    unittest.main()
