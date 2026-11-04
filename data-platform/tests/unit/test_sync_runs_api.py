# tests/unit/test_sync_runs_api.py
import importlib.util
import unittest
from datetime import datetime
from types import SimpleNamespace

HAS_API = all(
    importlib.util.find_spec(name) is not None
    for name in ("fastapi", "sqlalchemy", "pydantic", "pydantic_settings", "cryptography")
)


class FakeQuery:
    """Records the query builder calls; returns pre-ordered rows."""

    def __init__(self, rows):
        self.rows = rows
        self.filters = []
        self.order_by_called = False
        self.limit_value = None

    def filter(self, *criteria):
        self.filters.append(criteria)
        return self

    def order_by(self, *criteria):
        self.order_by_called = True
        return self

    def limit(self, value):
        self.limit_value = value
        return self

    def all(self):
        return self.rows


class FakeDb:
    def __init__(self, rows):
        self.query_obj = FakeQuery(rows)
        self.models = []

    def query(self, model):
        self.models.append(model)
        return self.query_obj


@unittest.skipUnless(HAS_API, "backend dependencies not installed")
class SyncRunsApiTest(unittest.TestCase):
    def _rows(self):
        return [
            SimpleNamespace(
                id=2,
                task_id=1,
                run_type="INCREMENTAL",
                status="SUCCESS",
                started_at=datetime(2026, 11, 4, 12, 0, 0),
                finished_at=datetime(2026, 11, 4, 12, 0, 5),
                rows_read=3,
                rows_written=3,
                error_msg=None,
            ),
            SimpleNamespace(
                id=1,
                task_id=1,
                run_type="FULL",
                status="FAILED",
                started_at=datetime(2026, 11, 4, 11, 0, 0),
                finished_at=datetime(2026, 11, 4, 11, 0, 2),
                rows_read=10,
                rows_written=9,
                error_msg="boom",
            ),
        ]

    def test_route_is_registered_at_sync_runs(self):
        from app.api import sync_tasks

        routes = sync_tasks.runs_router.routes
        self.assertIn("/sync-runs", [route.path for route in routes])
        methods = {method for route in routes for method in route.methods}
        self.assertIn("GET", methods)

    def test_list_runs_returns_view_with_filter_and_limit(self):
        from app.api import sync_tasks

        db = FakeDb(self._rows())

        result = sync_tasks.list_runs(task_id=1, limit=25, db=db)

        self.assertEqual(db.models, [sync_tasks.SyncRun])
        self.assertEqual(len(db.query_obj.filters), 1)
        self.assertTrue(db.query_obj.order_by_called)
        self.assertEqual(db.query_obj.limit_value, 25)
        views = result["data"]
        self.assertEqual([view["id"] for view in views], [2, 1])
        self.assertEqual(
            set(views[0]),
            {
                "id",
                "task_id",
                "run_type",
                "status",
                "started_at",
                "finished_at",
                "rows_read",
                "rows_written",
                "error_msg",
            },
        )
        self.assertEqual(views[0]["started_at"], "2026-11-04T12:00:00")
        self.assertEqual(views[0]["finished_at"], "2026-11-04T12:00:05")
        self.assertIsNone(views[0]["error_msg"])
        self.assertEqual(views[1]["error_msg"], "boom")

    def test_list_runs_without_task_filter(self):
        from app.api import sync_tasks

        db = FakeDb(self._rows())

        sync_tasks.list_runs(task_id=None, limit=5, db=db)

        self.assertEqual(db.query_obj.filters, [])
        self.assertEqual(db.query_obj.limit_value, 5)


if __name__ == "__main__":
    unittest.main()
