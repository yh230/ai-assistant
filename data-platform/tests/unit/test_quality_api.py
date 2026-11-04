# tests/unit/test_quality_api.py
import importlib.util
import json
import unittest
from datetime import datetime
from types import SimpleNamespace
from unittest import mock

HAS_API = all(
    importlib.util.find_spec(name) is not None
    for name in ("fastapi", "sqlalchemy", "pydantic", "pydantic_settings")
)


class FakeQuery:
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
    def __init__(self, rows=None, rule=None):
        self.query_obj = FakeQuery(rows or [])
        self.rule = rule
        self.models = []

    def query(self, model):
        self.models.append(model)
        return self.query_obj

    def get(self, model, key):
        return self.rule


def _rule_row(**overrides):
    values = dict(
        id=3,
        name="registration no not null",
        target_table="dwd_registration",
        rule_type="NOT_NULL",
        column_name="registration_no",
        params_json={"date_column": "created_at"},
        threshold=0.99,
        severity="P0",
        cron="0 * * * *",
        enabled=True,
        create_time=datetime(2026, 11, 4, 12, 0, 0),
    )
    values.update(overrides)
    return SimpleNamespace(**values)


@unittest.skipUnless(HAS_API, "backend dependencies not installed")
class QualityRoutesTest(unittest.TestCase):
    def test_routes_cover_the_design_surface(self):
        from app.api import quality

        routes = {}
        for route in quality.router.routes:
            routes.setdefault(route.path, set()).update(route.methods)
        self.assertIn("/quality/rules", routes)
        self.assertIn("/quality/rules/{rule_id}", routes)
        self.assertIn("/quality/rules/{rule_id}/run", routes)
        self.assertIn("/quality/results", routes)
        self.assertTrue({"GET", "POST"} <= routes["/quality/rules"])
        self.assertTrue({"PUT", "DELETE"} <= routes["/quality/rules/{rule_id}"])
        self.assertEqual(routes["/quality/rules/{rule_id}/run"], {"POST"})
        self.assertIn("GET", routes["/quality/results"])

    def test_rule_view_exposes_configuration(self):
        from app.api import quality

        view = quality._rule_view(_rule_row())
        self.assertEqual(
            set(view),
            {
                "id",
                "name",
                "target_table",
                "rule_type",
                "column_name",
                "params",
                "threshold",
                "severity",
                "cron",
                "enabled",
                "create_time",
            },
        )
        self.assertEqual(view["params"], {"date_column": "created_at"})
        self.assertEqual(view["create_time"], "2026-11-04T12:00:00")

    def test_result_view_exposes_counts(self):
        from app.api import quality

        row = SimpleNamespace(
            id=11,
            rule_id=3,
            total_rows=100,
            bad_rows=2,
            pass_rate=0.98,
            status="FAIL",
            checked_at=datetime(2026, 11, 4, 12, 0, 0),
        )
        view = quality._result_view(row)
        self.assertEqual(view["id"], 11)
        self.assertEqual(view["rule_id"], 3)
        self.assertEqual(view["total_rows"], 100)
        self.assertEqual(view["bad_rows"], 2)
        self.assertEqual(view["pass_rate"], 0.98)
        self.assertEqual(view["status"], "FAIL")
        self.assertEqual(view["checked_at"], "2026-11-04T12:00:00")

    def test_list_results_filters_and_limits(self):
        from app.api import quality

        db = FakeDb(
            [
                SimpleNamespace(
                    id=2,
                    rule_id=3,
                    total_rows=10,
                    bad_rows=0,
                    pass_rate=1.0,
                    status="PASS",
                    checked_at=datetime(2026, 11, 4, 12, 0, 0),
                )
            ]
        )

        result = quality.list_results(rule_id=3, limit=25, db=db)

        self.assertEqual(len(db.query_obj.filters), 1)
        self.assertTrue(db.query_obj.order_by_called)
        self.assertEqual(db.query_obj.limit_value, 25)
        self.assertEqual(result["data"][0]["rule_id"], 3)

    def test_create_rule_returns_the_new_id(self):
        from app.api import quality
        from app.schemas.quality import QualityRuleCreate

        payload = QualityRuleCreate(
            name="status enum",
            target_table="dwd_registration",
            rule_type="ENUM",
            column_name="status",
            params={"values": [1, 2]},
        )
        with mock.patch.object(quality, "QualityService") as service:
            service.return_value.create_rule.return_value = SimpleNamespace(id=9)
            result = quality.create_rule(payload, db=FakeDb())

        self.assertEqual(result, {"code": 0, "message": "ok", "data": {"id": 9}})
        service.return_value.create_rule.assert_called_once()

    def test_update_rule_maps_params_to_params_json(self):
        from app.api import quality
        from app.schemas.quality import QualityRuleUpdate

        db = FakeDb(rule=_rule_row())
        payload = QualityRuleUpdate(params={"values": [1, 2]}, rule_type="ENUM")
        with mock.patch.object(quality, "QualityService") as service:
            service.return_value.update_rule.return_value = _rule_row(
                rule_type="ENUM", params_json={"values": [1, 2]}
            )
            quality.update_rule(3, payload, db=db)

        _, kwargs = service.return_value.update_rule.call_args
        self.assertEqual(kwargs["params_json"], {"values": [1, 2]})
        self.assertEqual(kwargs["rule_type"], "ENUM")
        self.assertNotIn("params", kwargs)

    def test_missing_rule_is_404(self):
        from fastapi import HTTPException

        from app.api import quality

        with self.assertRaises(HTTPException) as context:
            quality._get_rule(FakeDb(rule=None), 3)
        self.assertEqual(context.exception.status_code, 404)

    def test_run_rule_success_returns_payload(self):
        from app.api import quality
        from etl.quality import QualityResult

        outcome = quality.QualityRunOutcome(
            result=QualityResult(3, 100, 1, 0.99, "PASS", "P0"),
            result_id=11,
            checked_at=datetime(2026, 11, 4, 12, 0, 0),
            olap_written=True,
        )
        settings = SimpleNamespace(demo_mode=False, olap_store="clickhouse")
        with mock.patch.object(quality, "_build_store", return_value=object()), mock.patch.object(
            quality, "get_settings", return_value=settings
        ), mock.patch.object(
            quality, "QualityService"
        ) as service:
            service.return_value.run_rule.return_value = outcome
            result = quality.run_rule(3, db=FakeDb(rule=_rule_row()))

        self.assertEqual(result["code"], 0)
        self.assertEqual(result["data"]["result_id"], 11)
        self.assertEqual(result["data"]["olap_written"], True)
        self.assertEqual(result["data"]["store_kind"], "clickhouse")
        self.assertIsNone(result["data"]["warning"])

    def test_run_rule_memory_store_returns_a_warning(self):
        from app.api import quality
        from etl.quality import QualityResult

        outcome = quality.QualityRunOutcome(
            result=QualityResult(3, 100, 1, 0.99, "PASS", "P0"),
            result_id=11,
            checked_at=datetime(2026, 11, 4, 12, 0, 0),
            olap_written=False,
            olap_error=None,
            store_kind="memory",
            warning="memory store: results were not persisted to ClickHouse",
        )
        settings = SimpleNamespace(
            demo_mode=False,
            olap_store="memory",
            clickhouse_url="http://127.0.0.1:8123",
            clickhouse_database="data_platform",
        )
        with mock.patch.object(
            quality, "_build_store", return_value=object()
        ), mock.patch.object(
            quality, "get_settings", return_value=settings
        ), mock.patch.object(quality, "QualityService") as service:
            service.return_value.run_rule.return_value = outcome
            result = quality.run_rule(3, db=FakeDb(rule=_rule_row()))

        # Not an HTTP error: the metadata result is durable, but the payload
        # must not claim a ClickHouse write.
        self.assertEqual(result["code"], 0)
        self.assertFalse(result["data"]["olap_written"])
        self.assertEqual(result["data"]["store_kind"], "memory")
        self.assertIn("not persisted to ClickHouse", result["data"]["warning"])
        self.assertEqual(service.call_args.kwargs["store_kind"], "memory")

    def test_run_rule_warehouse_failure_returns_502_envelope(self):
        from app.api import quality
        from etl.quality import QualityResult

        outcome = quality.QualityRunOutcome(
            result=QualityResult(3, 100, 1, 0.99, "PASS", "P0"),
            result_id=11,
            checked_at=datetime(2026, 11, 4, 12, 0, 0),
            olap_written=False,
            olap_error="quality result 11 saved but ads_quality_daily write failed: boom",
        )
        settings = SimpleNamespace(demo_mode=False, olap_store="clickhouse")
        with mock.patch.object(quality, "_build_store", return_value=object()), mock.patch.object(
            quality, "get_settings", return_value=settings
        ), mock.patch.object(
            quality, "QualityService"
        ) as service:
            service.return_value.run_rule.return_value = outcome
            response = quality.run_rule(3, db=FakeDb(rule=_rule_row()))

        self.assertEqual(response.status_code, 502)
        body = json.loads(response.body)
        self.assertEqual(body["code"], 502)
        self.assertIn("ads_quality_daily", body["message"])
        self.assertEqual(body["data"]["result_id"], 11)
        self.assertEqual(body["data"]["olap_written"], False)

    def test_unavailable_store_is_503(self):
        from fastapi import HTTPException

        from app.api import quality

        with mock.patch.object(
            quality, "build_olap_store", side_effect=RuntimeError("no driver")
        ):
            with self.assertRaises(HTTPException) as context:
                quality._build_store()

        self.assertEqual(context.exception.status_code, 503)
        self.assertIn("unavailable", context.exception.detail)


if __name__ == "__main__":
    unittest.main()
