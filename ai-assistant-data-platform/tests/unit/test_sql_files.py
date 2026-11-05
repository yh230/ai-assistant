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

# Every DWD/OPS source read by a transform must use FINAL so unmerged
# ReplacingMergeTree versions are collapsed before aggregation.
FINAL_SOURCES = {
    "ads/ads_hospital_daily.sql": (
        "dwd_registration r FINAL",
        "dwd_ai_session s FINAL",
        "dwd_ai_message m FINAL",
    ),
    "ads/ads_department_daily.sql": ("dwd_registration FINAL",),
    "ads/ads_ai_daily.sql": (
        "dwd_ai_session s FINAL",
        "dwd_ai_message m FINAL",
    ),
    "ads/ads_pipeline_health.sql": ("ops_ingest_audit FINAL",),
}

NULL_SAFE_RAG = (
    "ifNull(countIf(m.role = 'assistant' AND m.has_reference = 1) "
    "/ nullIf(countIf(m.role = 'assistant'), 0), 0) AS rag_hit_rate"
)

NULL_SAFE_RATIOS = {
    "ads/ads_hospital_daily.sql": (
        "ifNull(countIf(r.status IN (4, 5, 6)) / nullIf(count(), 0), 0) AS cancel_rate",
        "ifNull(countIf(r.status = 3) / nullIf(countIf(r.status IN (2, 3)), 0), 0) AS visit_rate",
        NULL_SAFE_RAG,
    ),
    "ads/ads_ai_daily.sql": (NULL_SAFE_RAG,),
}


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
                ("occurred_at", "DateTime('Asia/Shanghai')"),
                ("_ingested_at", "DateTime('Asia/Shanghai')"),
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

    def test_every_datetime_column_is_pinned_to_shanghai(self):
        ddl = read_sql("ddl/clickhouse.sql")
        code = "\n".join(
            line for line in ddl.splitlines() if not line.strip().startswith("--")
        )
        # No bare DateTime type survives (DateTime'(' is required), and the
        # toDateTime() TTL helper is not a column type.
        self.assertNotRegex(code, r"(?<!to)DateTime(?!\()")
        for match in re.finditer(r"(?<!to)DateTime\(([^)]*)\)", code):
            self.assertEqual(match.group(1), "'Asia/Shanghai'")

    def test_transform_sql_uses_the_shanghai_clock(self):
        for relative in TRANSFORM_TARGETS:
            sql = read_sql(relative)
            self.assertNotRegex(sql, r"now\(\)", relative)
            self.assertNotIn("today()", sql, relative)
            # A bare toDate(identifier) has no time-zone argument.
            self.assertNotRegex(sql, r"toDate\([\w.]+\)", relative)

    def test_ops_tables_expire(self):
        ddl = read_sql("ddl/clickhouse.sql")
        for table in (
            "ops_quarantine",
            "ops_ingest_audit",
            "ops_registration_event",
        ):
            statement = normalized(table_statement(ddl, table))
            self.assertIn("TTL _ingested_at + INTERVAL 90 DAY", statement, table)

    def test_optional_non_nullable_columns_have_defaults(self):
        ddl = read_sql("ddl/clickhouse.sql")
        expectations = {
            "ods_registration": ("registration_no String DEFAULT ''",),
            "ods_hospital": ("address String DEFAULT ''", "phone String DEFAULT ''"),
            "ods_doctor_schedule": ("total_limit UInt32 DEFAULT 0",),
            "ods_department": ("status UInt8 DEFAULT 0",),
        }
        for table, fragments in expectations.items():
            statement = normalized(table_statement(ddl, table))
            for fragment in fragments:
                self.assertIn(fragment, statement, f"{table}: {fragment}")

    def test_transform_cli_exists(self):
        self.assertTrue((SQL_ROOT.parent / "transform_main.py").exists())

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

    def test_ads_transforms_read_every_source_with_final(self):
        for relative, sources in FINAL_SOURCES.items():
            sql = normalized(read_sql(relative))
            for source in sources:
                self.assertIn(source, sql, f"{relative}: {source}")

    def test_ads_ratios_are_null_safe(self):
        for relative, formulas in NULL_SAFE_RATIOS.items():
            sql = normalized(read_sql(relative))
            for formula in formulas:
                self.assertIn(formula, sql, f"{relative}: {formula}")


if __name__ == "__main__":
    unittest.main()
