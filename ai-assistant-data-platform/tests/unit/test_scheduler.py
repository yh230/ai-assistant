# tests/unit/test_scheduler.py
import importlib.util
import unittest
from types import SimpleNamespace
from unittest import mock

REQUIRED = ("apscheduler", "sqlalchemy", "pydantic_settings")
MISSING = [name for name in REQUIRED if importlib.util.find_spec(name) is None]


class FakeDb:
    """Session double returning the scheduled task and its datasource."""

    def __init__(self, task, datasource):
        self.task = task
        self.datasource = datasource
        self.closed = False

    def get(self, model, key):
        if key == self.task.id:
            return self.task
        return self.datasource

    def close(self):
        self.closed = True


@unittest.skipIf(MISSING, f"missing optional dependencies: {', '.join(MISSING)}")
class ScheduledRunTopicPrefixTest(unittest.TestCase):
    def test_run_task_publishes_with_the_configured_prefix(self):
        from app import scheduler

        task = SimpleNamespace(id=42, datasource_id=7)
        datasource = SimpleNamespace(id=7)
        db = FakeDb(task, datasource)
        settings = SimpleNamespace(
            dp_secret_key="secret",
            event_bus="memory",
            kafka_bootstrap="127.0.0.1:9092",
            kafka_consumer_group="g",
            kafka_topic_prefix="dp.stage",
        )
        publisher = mock.MagicMock()
        with (
            mock.patch.object(scheduler, "SessionLocal", return_value=db),
            mock.patch.object(scheduler, "get_settings", return_value=settings),
            mock.patch.object(scheduler, "DatasourceService") as datasource_service,
            mock.patch.object(scheduler, "MySQLSourceRepository"),
            mock.patch.object(scheduler, "build_event_bus", return_value=publisher),
            mock.patch.object(scheduler, "SyncService") as sync_service,
        ):
            datasource_service.return_value.url_for.return_value = "mysql+pymysql://x"
            scheduler.run_task(42)

        # Manual runs and consumer_main both use get_settings().kafka_topic_prefix;
        # a scheduled run must publish to the same topics.
        sync_service.assert_called_once_with(db, topic_prefix="dp.stage")
        sync_service.return_value.run.assert_called_once_with(
            42, "INCREMENTAL", mock.ANY, publisher
        )
        publisher.flush.assert_called_once()
        publisher.close.assert_called_once()
        self.assertTrue(db.closed)


if __name__ == "__main__":
    unittest.main()
