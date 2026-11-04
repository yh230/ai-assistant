# tests/unit/test_metadata_models.py
import unittest

try:
    from app.core.db import Base
    import app.models.metadata  # noqa: F401
except ImportError:
    Base = None

TABLE_NAMES = (
    "dp_datasource",
    "dp_sync_task",
    "dp_sync_run",
    "dp_table_meta",
    "dp_column_meta",
    "dp_quality_rule",
    "dp_quality_result",
    "dp_alert_rule",
    "dp_alert_record",
)


@unittest.skipIf(Base is None, "sqlalchemy not installed")
class MetadataModelTest(unittest.TestCase):
    def test_tables_registered(self):
        names = set(Base.metadata.tables)
        for name in TABLE_NAMES:
            self.assertIn(name, names)

    def test_sync_task_numeric_watermark_column(self):
        from sqlalchemy import Numeric

        column = Base.metadata.tables["dp_sync_task"].c["last_watermark_num"]
        self.assertIsInstance(column.type, Numeric)
        self.assertTrue(column.nullable)
        self.assertEqual(column.type.precision, 20)
        self.assertEqual(column.type.scale, 0)

    def test_timestamp_defaults_use_the_platform_clock(self):
        from app.core.time import now_shanghai

        pairs = (
            ("dp_datasource", "create_time"),
            ("dp_sync_run", "started_at"),
            ("dp_quality_result", "checked_at"),
        )
        for table, column_name in pairs:
            with self.subTest(table=table, column=column_name):
                column = Base.metadata.tables[table].c[column_name]
                # SQLAlchemy 2.1 wraps zero-argument defaults; the original
                # callable is exposed through __wrapped__.
                default = column.default.arg
                self.assertIs(getattr(default, "__wrapped__", default), now_shanghai)
