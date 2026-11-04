# tests/unit/test_sync_service.py
import os
import tempfile
import unittest
from datetime import datetime
from decimal import Decimal

try:
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    from app.core.db import Base
    from app.core.exceptions import DataPlatformError
    from app.models.metadata import SyncRun, SyncTask
    from app.services.sync_service import SyncService
except ImportError:  # pragma: no cover - exercised only without the backend stack
    Base = None


class FakeSource:
    def __init__(self, batches=None, error=None):
        self.batches = list(batches or [])
        self.error = error
        self.incremental_calls = []

    def fetch_full_batch(self, schema, table, columns, primary_key, after_pk, limit):
        if self.error is not None:
            raise self.error
        return self.batches.pop(0) if self.batches else []

    def fetch_incremental_batch(
        self,
        schema,
        table,
        columns,
        primary_key,
        watermark_column,
        after_watermark,
        after_pk,
        limit,
        upper_watermark=None,
    ):
        self.incremental_calls.append(
            (watermark_column, after_watermark, after_pk, limit)
        )
        if self.error is not None:
            raise self.error
        return self.batches.pop(0) if self.batches else []

    def max_watermark(self, schema, table, watermark_column):
        return None


class FakePublisher:
    def __init__(self):
        self.messages = []

    def publish(self, topic, key, value):
        self.messages.append((topic, key, value))

    def flush(self):
        pass


@unittest.skipIf(Base is None, "sqlalchemy not installed")
class SyncServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmpdir = tempfile.TemporaryDirectory()
        db_path = os.path.join(cls._tmpdir.name, "sync-service.db").replace("\\", "/")
        cls.engine = create_engine("sqlite:///" + db_path)
        cls.Session = sessionmaker(bind=cls.engine, expire_on_commit=False)
        Base.metadata.create_all(cls.engine)

    @classmethod
    def tearDownClass(cls):
        cls.engine.dispose()
        cls._tmpdir.cleanup()

    def setUp(self):
        self.db = self.Session()
        self.service = SyncService(self.db)

    def tearDown(self):
        self.db.close()

    def _create_task(self, **overrides):
        options = {
            "datasource_id": 1,
            "source_schema": "chat_db",
            "source_table": "chat_session",
            "target_table": "ods_chat_session",
            "primary_key": "id",
            "watermark_column": "updated_at",
        }
        options.update(overrides)
        return self.service.create_task(**options)

    def _fresh(self):
        return self.Session()

    def test_acquire_lock_blocks_a_second_runner(self):
        task = self._create_task()

        self.service.acquire_lock(task.id)

        other = self._fresh()
        try:
            with self.assertRaises(DataPlatformError) as ctx:
                SyncService(other).acquire_lock(task.id)
            self.assertEqual(ctx.exception.code, 409)
            self.assertIn("already running", ctx.exception.message)
        finally:
            other.close()

        stored = self._fresh()
        try:
            row = stored.get(SyncTask, task.id)
            self.assertTrue(row.running)
            self.assertIsNotNone(row.locked_at)
        finally:
            stored.close()

        self.service.release_lock(task.id)

        other = self._fresh()
        try:
            SyncService(other).acquire_lock(task.id)
            SyncService(other).release_lock(task.id)
        finally:
            other.close()

    def test_acquire_lock_unknown_task_returns_404(self):
        with self.assertRaises(DataPlatformError) as ctx:
            self.service.acquire_lock(9999)
        self.assertEqual(ctx.exception.code, 404)

    def test_run_rejects_concurrent_execution(self):
        task = self._create_task()
        self.service.acquire_lock(task.id)

        with self.assertRaises(DataPlatformError) as ctx:
            self.service.run(task.id, "INCREMENTAL", FakeSource(), FakePublisher())

        self.assertEqual(ctx.exception.code, 409)
        stored = self._fresh()
        try:
            run_count = stored.query(SyncRun).filter(SyncRun.task_id == task.id).count()
            self.assertEqual(run_count, 0)
        finally:
            stored.close()

    def test_run_releases_lock_when_extraction_fails(self):
        task = self._create_task()

        with self.assertRaises(RuntimeError):
            self.service.run(
                task.id, "FULL", FakeSource(error=RuntimeError("boom")), FakePublisher()
            )

        stored = self._fresh()
        try:
            row = stored.get(SyncTask, task.id)
            self.assertFalse(row.running)
            self.assertIsNone(row.locked_at)
            self.assertFalse(row.full_load_done)
            self.assertIsNone(row.last_run_at)
            run = stored.query(SyncRun).filter(SyncRun.task_id == task.id).one()
            self.assertEqual(run.status, "FAILED")
            self.assertIn("boom", run.error_msg)
        finally:
            stored.close()

    def test_run_persists_numeric_watermark_to_numeric_column(self):
        task = self._create_task(watermark_column="seq", watermark_type="numeric")
        task.full_load_done = True
        task.last_watermark_num = Decimal("100")
        self.db.commit()
        source = FakeSource([[{"id": 1, "seq": 130}]])

        result = self.service.run(task.id, "INCREMENTAL", source, FakePublisher())

        self.assertEqual(result.last_watermark_num, Decimal("130"))
        self.assertIsNone(result.last_watermark)
        self.assertEqual(source.incremental_calls[0][1], Decimal("100"))

        stored = self._fresh()
        try:
            row = stored.get(SyncTask, task.id)
            self.assertEqual(row.last_watermark_num, Decimal("130"))
            self.assertIsNone(row.last_watermark)
            self.assertTrue(row.full_load_done)
            self.assertIsNotNone(row.last_run_at)
            self.assertFalse(row.running)
        finally:
            stored.close()

    def test_run_persists_datetime_watermark_only_to_datetime_column(self):
        task = self._create_task(watermark_column="updated_at")
        task.full_load_done = True
        task.last_watermark = datetime(2026, 11, 4, 10, 0, 0)
        self.db.commit()
        source = FakeSource([[{"id": 1, "updated_at": datetime(2026, 11, 4, 10, 5, 0)}]])

        result = self.service.run(task.id, "INCREMENTAL", source, FakePublisher())

        self.assertEqual(result.last_watermark, datetime(2026, 11, 4, 10, 5, 0))
        self.assertIsNone(result.last_watermark_num)

        stored = self._fresh()
        try:
            row = stored.get(SyncTask, task.id)
            self.assertEqual(row.last_watermark, datetime(2026, 11, 4, 10, 5, 0))
            self.assertIsNone(row.last_watermark_num)
        finally:
            stored.close()

    def test_update_task_resets_watermark_when_configuration_changes(self):
        task = self._create_task(watermark_column="updated_at")
        task.full_load_done = True
        task.last_watermark = datetime(2026, 11, 4, 10, 0, 0)
        self.db.commit()

        updated = self.service.update_task(
            task, watermark_column="seq", watermark_type="numeric", batch_size=200
        )

        self.assertEqual(updated.watermark_column, "seq")
        self.assertEqual(updated.watermark_type, "numeric")
        self.assertEqual(updated.batch_size, 200)
        self.assertFalse(updated.full_load_done)
        self.assertIsNone(updated.last_watermark)
        self.assertIsNone(updated.last_watermark_num)

    def test_update_task_resets_watermark_when_source_is_repointed(self):
        task = self._create_task(watermark_column="seq", watermark_type="numeric")
        repoints = (
            {"datasource_id": 2},
            {
                "source_schema": "registration_db",
                "source_table": "registration",
                "target_table": "ods_registration",
            },
            {"source_table": "chat_message", "target_table": "ods_chat_message"},
        )
        for change in repoints:
            task.full_load_done = True
            task.last_watermark_num = Decimal("100")
            self.db.commit()

            updated = self.service.update_task(task, **change)

            for field, value in change.items():
                self.assertEqual(getattr(updated, field), value)
            self.assertFalse(updated.full_load_done)
            self.assertIsNone(updated.last_watermark)
            self.assertIsNone(updated.last_watermark_num)

    def test_create_task_rejects_an_unsupported_source(self):
        with self.assertRaises(ValueError) as context:
            self._create_task(
                source_schema="system_db",
                source_table="sys_oper_log",
                target_table="ods_sys_oper_log",
            )
        self.assertIn("unsupported source table", str(context.exception))

    def test_create_task_rejects_a_target_mismatch(self):
        with self.assertRaises(ValueError) as context:
            self._create_task(target_table="ods_hospital")
        self.assertIn("target_table must be ods_chat_session", str(context.exception))

    def test_update_task_rejects_an_unsupported_source(self):
        task = self._create_task()

        with self.assertRaises(ValueError):
            self.service.update_task(
                task,
                source_schema="system_db",
                source_table="sys_oper_log",
                target_table="ods_sys_oper_log",
            )

    def test_update_task_rejects_a_target_mismatch(self):
        task = self._create_task()

        with self.assertRaises(ValueError):
            self.service.update_task(task, target_table="ods_hospital")

    def test_update_task_requires_a_matching_target_for_a_new_source(self):
        task = self._create_task()

        with self.assertRaises(ValueError):
            self.service.update_task(task, source_table="chat_message")

    def test_update_task_rejects_changes_while_running(self):
        task = self._create_task()
        self.service.acquire_lock(task.id)

        try:
            with self.assertRaises(DataPlatformError) as ctx:
                self.service.update_task(task, cron="0 * * * *")
        finally:
            self.service.release_lock(task.id)

        self.assertEqual(ctx.exception.code, 409)
        stored = self._fresh()
        try:
            row = stored.get(SyncTask, task.id)
            self.assertEqual(row.cron, "*/10 * * * *")
        finally:
            stored.close()

    def test_update_task_keeps_watermark_when_configuration_is_unchanged(self):
        task = self._create_task(watermark_column="updated_at")
        task.full_load_done = True
        task.last_watermark = datetime(2026, 11, 4, 10, 0, 0)
        self.db.commit()
        watermark = task.last_watermark

        updated = self.service.update_task(task, cron="0 * * * *", enabled=False)

        self.assertTrue(updated.full_load_done)
        self.assertEqual(updated.last_watermark, watermark)
        self.assertFalse(updated.enabled)
        self.assertEqual(updated.cron, "0 * * * *")

    def test_update_task_rejects_unknown_fields_and_null_required_fields(self):
        task = self._create_task()

        with self.assertRaises(ValueError):
            self.service.update_task(task, unknown_field="x")
        with self.assertRaises(ValueError):
            self.service.update_task(task, source_table=None)
        with self.assertRaises(ValueError):
            self.service.update_task(task, watermark_type="string")

    def test_create_task_rejects_unknown_watermark_type(self):
        with self.assertRaises(ValueError):
            self._create_task(watermark_type="timestamp")

    def test_create_task_requires_watermark_column(self):
        with self.assertRaises(ValueError):
            self._create_task(watermark_column=None)
        with self.assertRaises(ValueError):
            self._create_task(watermark_column="   ")

    def test_update_task_requires_watermark_column(self):
        task = self._create_task()

        with self.assertRaises(ValueError):
            self.service.update_task(task, watermark_column=None)
        with self.assertRaises(ValueError):
            self.service.update_task(task, watermark_column="")

    def test_delete_task_removes_its_runs(self):
        task = self._create_task()
        self.db.add(SyncRun(task_id=task.id, run_type="FULL", status="SUCCESS"))
        self.db.commit()

        self.service.delete_task(task)

        stored = self._fresh()
        try:
            self.assertIsNone(stored.get(SyncTask, task.id))
            run_count = stored.query(SyncRun).filter(SyncRun.task_id == task.id).count()
            self.assertEqual(run_count, 0)
        finally:
            stored.close()

    def test_get_spec_maps_only_the_active_watermark(self):
        task = self._create_task(watermark_column="seq", watermark_type="numeric")
        task.last_watermark = datetime(2026, 11, 4, 10, 0, 0)
        task.last_watermark_num = Decimal("42")
        self.db.commit()

        spec = self.service.get_spec(task.id)

        self.assertEqual(spec.watermark_type, "numeric")
        self.assertEqual(spec.last_watermark_num, Decimal("42"))
        self.assertIsNone(spec.last_watermark)


if __name__ == "__main__":
    unittest.main()
