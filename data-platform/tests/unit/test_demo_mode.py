# tests/unit/test_demo_mode.py
import importlib.util
import os
import unittest
from types import SimpleNamespace
from unittest import mock

HAS_STACK = all(
    importlib.util.find_spec(name) is not None
    for name in ("fastapi", "sqlalchemy", "pydantic", "pydantic_settings")
)


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class DemoSeedTest(unittest.TestCase):
    def setUp(self):
        from sqlalchemy import create_engine
        from sqlalchemy.orm import sessionmaker

        import app.models.metadata  # noqa: F401
        from app.core.db import Base

        self.engine = create_engine("sqlite://")
        Base.metadata.create_all(bind=self.engine)
        self.Session = sessionmaker(bind=self.engine, expire_on_commit=False)
        self.db = self.Session()

    def tearDown(self):
        self.db.close()
        self.engine.dispose()

    def _counts(self):
        from app.models.metadata import (
            DataSource,
            DpAlertRecord,
            DpAlertRule,
            DpQualityResult,
            DpQualityRule,
            SyncRun,
            SyncTask,
        )

        return {
            "datasources": self.db.query(DataSource).count(),
            "tasks": self.db.query(SyncTask).count(),
            "runs": self.db.query(SyncRun).count(),
            "quality_rules": self.db.query(DpQualityRule).count(),
            "quality_results": self.db.query(DpQualityResult).count(),
            "alert_rules": self.db.query(DpAlertRule).count(),
            "alert_records": self.db.query(DpAlertRecord).count(),
        }

    def test_seed_populates_every_governance_table(self):
        from app.demo import seed_demo_metadata

        seeded = seed_demo_metadata(self.db)

        self.assertTrue(seeded)
        counts = self._counts()
        for table, count in counts.items():
            with self.subTest(table=table):
                self.assertGreater(count, 0)

    def test_seed_is_idempotent(self):
        from app.demo import seed_demo_metadata

        seed_demo_metadata(self.db)
        first = self._counts()
        seed_demo_metadata(self.db)
        self.assertEqual(self._counts(), first)

    def test_seed_skips_a_non_empty_metadata_database(self):
        from app.demo import seed_demo_metadata
        from app.models.metadata import DataSource

        self.db.add(
            DataSource(
                name="existing",
                host="127.0.0.1",
                database_name="real_db",
                username="readonly",
                password_cipher="cipher",
            )
        )
        self.db.commit()

        seeded = seed_demo_metadata(self.db)

        self.assertFalse(seeded)
        self.assertEqual(self.db.query(DataSource).count(), 1)


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class DemoSimulationTest(unittest.TestCase):
    def setUp(self):
        from sqlalchemy import create_engine
        from sqlalchemy.orm import sessionmaker

        import app.models.metadata  # noqa: F401
        from app.core.db import Base

        self.engine = create_engine("sqlite://")
        Base.metadata.create_all(bind=self.engine)
        self.Session = sessionmaker(bind=self.engine, expire_on_commit=False)
        self.db = self.Session()

    def tearDown(self):
        self.db.close()
        self.engine.dispose()

    def _task(self):
        from app.models.metadata import DataSource, SyncTask

        datasource = DataSource(
            name="demo",
            host="127.0.0.1",
            database_name="registration_db",
            username="readonly",
            password_cipher="",
        )
        self.db.add(datasource)
        self.db.commit()
        task = SyncTask(
            datasource_id=datasource.id,
            source_schema="registration_db",
            source_table="registration",
            target_table="ods_registration",
            primary_key="id",
            watermark_column="update_time",
            watermark_type="datetime",
        )
        self.db.add(task)
        self.db.commit()
        self.db.refresh(task)
        return task

    def _rule(self):
        from app.models.metadata import DpQualityRule

        rule = DpQualityRule(
            name="挂号单号非空",
            target_table="ods_registration",
            rule_type="NOT_NULL",
            column_name="registration_no",
            params_json={},
            threshold=0.99,
            severity="P1",
        )
        self.db.add(rule)
        self.db.commit()
        self.db.refresh(rule)
        return rule

    def test_demo_sync_run_reports_no_publication_and_no_write(self):
        from app.demo import DEMO_SYNC_WARNING, simulate_sync_run
        from app.models.metadata import SyncRun

        task = self._task()
        payload = simulate_sync_run(self.db, task, "incremental")

        self.assertGreater(payload["rows_read"], 0)
        self.assertEqual(payload["rows_written"], 0)
        self.assertFalse(payload["published"])
        self.assertTrue(payload["demo"])
        self.assertEqual(payload["warning"], DEMO_SYNC_WARNING)
        self.assertEqual(self.db.query(SyncRun).count(), 1)
        self.assertIsNotNone(task.last_run_at)

    def test_demo_sync_run_rejects_an_unknown_mode(self):
        from app.demo import simulate_sync_run

        with self.assertRaises(ValueError):
            simulate_sync_run(self.db, self._task(), "SIDEWAYS")

    def test_demo_quality_run_persists_a_simulated_result(self):
        from app.demo import DEMO_QUALITY_WARNING, simulate_quality_run
        from app.models.metadata import DpQualityResult

        rule = self._rule()
        payload = simulate_quality_run(self.db, rule)

        self.assertEqual(payload["rule_id"], rule.id)
        self.assertGreater(payload["total_rows"], 0)
        self.assertFalse(payload["olap_written"])
        self.assertEqual(payload["store_kind"], "demo")
        self.assertEqual(payload["warning"], DEMO_QUALITY_WARNING)
        self.assertEqual(self.db.query(DpQualityResult).count(), 1)


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class DemoAuthTest(unittest.TestCase):
    def test_demo_mode_accepts_an_anonymous_staff_request(self):
        from app.api import deps

        with mock.patch.object(
            deps, "get_settings", return_value=SimpleNamespace(demo_mode=True)
        ):
            principal = deps.require_staff(None)
        self.assertEqual(principal["identity"], "staff")
        self.assertTrue(principal["demo"])

    def test_non_demo_mode_still_requires_a_token(self):
        from fastapi import HTTPException

        from app.api import deps

        with mock.patch.object(
            deps, "get_settings", return_value=SimpleNamespace(demo_mode=False)
        ):
            with self.assertRaises(HTTPException) as context:
                deps.require_staff(None)
        self.assertEqual(context.exception.status_code, 401)


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class DemoScreenSelectionTest(unittest.TestCase):
    def test_screen_service_uses_the_demo_repository(self):
        from app.api import screen
        from app.demo_screen import DemoScreenRepository

        settings = SimpleNamespace(
            demo_mode=True,
            olap_store="memory",
            clickhouse_url="http://127.0.0.1:8123",
            clickhouse_database="data_platform",
            screen_max_trend_days=365,
            screen_max_ranking_limit=100,
            screen_recent_alert_limit=10,
        )
        with mock.patch.object(screen, "get_settings", return_value=settings):
            service = screen._service()
        self.assertIsInstance(service.repository, DemoScreenRepository)


class DemoConfigTest(unittest.TestCase):
    @unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
    def test_demo_mode_defaults_to_false(self):
        from app.core.config import Settings

        self.assertFalse(Settings(_env_file=None).demo_mode)

    @unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
    def test_demo_mode_reads_the_environment(self):
        from app.core.config import Settings

        with mock.patch.dict(os.environ, {"DEMO_MODE": "true"}, clear=True):
            self.assertTrue(Settings(_env_file=None).demo_mode)


if __name__ == "__main__":
    unittest.main()
