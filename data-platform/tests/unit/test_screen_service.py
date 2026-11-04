# tests/unit/test_screen_service.py
import asyncio
import importlib.util
import unittest
from datetime import date
from types import SimpleNamespace
from unittest import mock

from app.services.screen_service import (
    ClickHouseScreenRepository,
    ScreenService,
    format_sse,
)

HAS_API = all(
    importlib.util.find_spec(name) is not None
    for name in ("fastapi", "sqlalchemy", "pydantic", "pydantic_settings")
)

KPI_KEYS = [
    "reg_count",
    "ai_sessions",
    "quality_score",
    "lag_seconds",
    "open_alerts",
    "cancel_rate",
]


class FakeScreenRepository:
    """Configurable ScreenRepository that records every call."""

    def __init__(
        self,
        overview=None,
        trend=None,
        hospitals=None,
        departments=None,
        health=None,
        alerts=None,
    ):
        self.overview_data = (
            {"reg_count": 10, "ai_sessions": 3} if overview is None else overview
        )
        self.trend_data = (
            [{"date": "2026-11-04", "value": 10}] if trend is None else trend
        )
        self.hospital_data = (
            [{"hospital_id": 1, "name": "第一医院", "value": 10}]
            if hospitals is None
            else hospitals
        )
        self.department_data = (
            [{"department_id": 2, "name": "内科", "value": 8}]
            if departments is None
            else departments
        )
        self.health_data = (
            {"quality_score": 0.98, "lag_seconds": 12, "open_alerts": 1}
            if health is None
            else health
        )
        self.alert_data = (
            [{"severity": "P0", "title": "链路延迟"}] if alerts is None else alerts
        )
        self.calls = []

    def overview(self):
        self.calls.append(("overview",))
        return self.overview_data

    def trend(self, days):
        self.calls.append(("trend", days))
        return self.trend_data

    def hospital_ranking(self, limit):
        self.calls.append(("hospital_ranking", limit))
        return self.hospital_data

    def department_ranking(self, limit):
        self.calls.append(("department_ranking", limit))
        return self.department_data

    def quality_health(self):
        self.calls.append(("quality_health",))
        return self.health_data

    def recent_alerts(self, limit):
        self.calls.append(("recent_alerts", limit))
        return self.alert_data


class RecordingOlap:
    def __init__(self, rows=None):
        self.rows = [] if rows is None else rows
        self.queries = []

    def query(self, sql):
        self.queries.append(sql)
        return self.rows


def _kpi_values(data):
    return {item["key"]: item["value"] for item in data["kpis"]}


class ScreenServiceTest(unittest.TestCase):
    # Brief cases, kept verbatim.
    def test_overview_contains_kpis(self):
        service = ScreenService(FakeScreenRepository())
        data = service.overview()
        self.assertEqual(data["kpis"][0]["key"], "reg_count")

    def test_sse_format(self):
        self.assertEqual(format_sse({"a": 1}), 'data: {"a": 1}\n\n')

    def test_overview_returns_six_kpis_in_design_order(self):
        data = ScreenService(FakeScreenRepository()).overview()
        self.assertEqual([item["key"] for item in data["kpis"]], KPI_KEYS)

    def test_overview_maps_raw_and_health_values(self):
        repository = FakeScreenRepository(
            overview={"reg_count": 10, "ai_sessions": 3, "cancel_rate": 0.12}
        )
        values = _kpi_values(ScreenService(repository).overview())
        self.assertEqual(values["reg_count"], 10)
        self.assertEqual(values["ai_sessions"], 3)
        self.assertEqual(values["quality_score"], 0.98)
        self.assertEqual(values["lag_seconds"], 12)
        self.assertEqual(values["open_alerts"], 1)
        self.assertEqual(values["cancel_rate"], 0.12)

    def test_overview_defaults_missing_values_to_zero(self):
        data = ScreenService(
            FakeScreenRepository(overview={}, health={})
        ).overview()
        self.assertEqual(_kpi_values(data), {key: 0 for key in KPI_KEYS})

    def test_overview_defaults_none_values_to_zero(self):
        repository = FakeScreenRepository(
            overview={"reg_count": None, "ai_sessions": None, "cancel_rate": None},
            health={"quality_score": None, "lag_seconds": None, "open_alerts": None},
        )
        self.assertEqual(
            _kpi_values(ScreenService(repository).overview()),
            {key: 0 for key in KPI_KEYS},
        )

    def test_overview_keeps_the_raw_repository_dict(self):
        repository = FakeScreenRepository()
        data = ScreenService(repository).overview()
        self.assertIs(data["raw"], repository.overview_data)

    def test_overview_keeps_raw_values_over_health_fallbacks(self):
        repository = FakeScreenRepository(
            overview={"reg_count": 4, "quality_score": 0.5},
            health={"quality_score": 0.9, "lag_seconds": 12, "open_alerts": 1},
        )
        values = _kpi_values(ScreenService(repository).overview())
        self.assertEqual(values["quality_score"], 0.5)

    def test_overview_fills_none_raw_values_from_health(self):
        repository = FakeScreenRepository(
            overview={"reg_count": None},
            health={"reg_count": 7, "quality_score": 0.9},
        )
        values = _kpi_values(ScreenService(repository).overview())
        self.assertEqual(values["reg_count"], 7)
        self.assertEqual(values["quality_score"], 0.9)

    def test_overview_does_not_fail_on_an_empty_repository(self):
        repository = FakeScreenRepository(overview=None, health=None)
        repository.overview_data = None
        repository.health_data = None
        data = ScreenService(repository).overview()
        self.assertEqual(len(data["kpis"]), 6)
        self.assertEqual(data["raw"], {})

    def test_trend_passes_the_window_through(self):
        repository = FakeScreenRepository()
        rows = ScreenService(repository).trend(7)
        self.assertEqual(rows, [{"date": "2026-11-04", "value": 10}])
        self.assertIn(("trend", 7), repository.calls)

    def test_trend_defaults_to_thirty_days(self):
        repository = FakeScreenRepository()
        ScreenService(repository).trend()
        self.assertIn(("trend", 30), repository.calls)

    def test_ranking_combines_hospital_and_department_rows(self):
        repository = FakeScreenRepository()
        data = ScreenService(repository).ranking(5)
        self.assertEqual(data["hospitals"][0]["name"], "第一医院")
        self.assertEqual(data["departments"][0]["name"], "内科")
        self.assertIn(("hospital_ranking", 5), repository.calls)
        self.assertIn(("department_ranking", 5), repository.calls)

    def test_ranking_defaults_to_ten_rows(self):
        repository = FakeScreenRepository()
        ScreenService(repository).ranking()
        self.assertIn(("hospital_ranking", 10), repository.calls)
        self.assertIn(("department_ranking", 10), repository.calls)

    def test_quality_combines_health_and_recent_failures(self):
        repository = FakeScreenRepository()
        data = ScreenService(repository).quality()
        self.assertEqual(
            data["health"],
            {"quality_score": 0.98, "lag_seconds": 12, "open_alerts": 1},
        )
        self.assertEqual(data["alerts"], [{"severity": "P0", "title": "链路延迟"}])
        self.assertIn(("recent_alerts", 10), repository.calls)

    def test_quality_defaults_missing_health_to_zero(self):
        repository = FakeScreenRepository(health={})
        data = ScreenService(repository).quality()
        self.assertEqual(
            data["health"],
            {"quality_score": 0, "lag_seconds": 0, "open_alerts": 0},
        )

    def test_quality_uses_the_configured_alert_limit(self):
        repository = FakeScreenRepository()
        ScreenService(repository, alert_limit=3).quality()
        self.assertIn(("recent_alerts", 3), repository.calls)

    def test_sse_format_keeps_raw_unicode(self):
        self.assertEqual(
            format_sse({"标题": "链路延迟"}),
            'data: {"标题": "链路延迟"}\n\n',
        )

    def test_sse_format_terminates_frames_with_a_blank_line(self):
        frame = format_sse({"a": 1})
        self.assertTrue(frame.startswith("data: "))
        self.assertTrue(frame.endswith("\n\n"))

    def test_sse_format_serializes_dates(self):
        self.assertIn("2026-11-04", format_sse({"date": date(2026, 11, 4)}))


class ClickHouseScreenRepositoryTest(unittest.TestCase):
    def _repo(self, rows=None, **kwargs):
        olap = RecordingOlap(rows)
        return ClickHouseScreenRepository(olap, **kwargs), olap

    def test_overview_query_uses_final_and_today(self):
        row = {"reg_count": 10, "ai_sessions": 3, "cancel_rate": 0.1}
        repository, olap = self._repo([row])
        self.assertEqual(repository.overview(), row)
        sql = olap.queries[0]
        self.assertIn("data_platform.ads_hospital_daily FINAL", sql)
        self.assertIn("metric_date = today()", sql)
        self.assertIn("sum(reg_count)", sql)
        self.assertIn("sum(ai_sessions)", sql)
        self.assertIn("cancel_rate", sql)

    def test_overview_empty_result_is_an_empty_dict(self):
        repository, _ = self._repo([])
        self.assertEqual(repository.overview(), {})

    def test_trend_query_uses_final_and_bounds_the_window(self):
        repository, olap = self._repo([{"date": "2026-11-04", "value": 10}])
        repository.trend(7)
        sql = olap.queries[0]
        self.assertIn("data_platform.ads_hospital_daily FINAL", sql)
        self.assertIn("INTERVAL 6 DAY", sql)
        self.assertIn("GROUP BY metric_date", sql)
        self.assertIn("ORDER BY metric_date", sql)

    def test_trend_clamps_the_window_to_the_configured_maximum(self):
        repository, olap = self._repo([], max_trend_days=90)
        repository.trend(365)
        sql = olap.queries[0]
        self.assertIn("INTERVAL 89 DAY", sql)
        self.assertNotIn("365", sql)

    def test_trend_clamps_non_positive_windows_to_one_day(self):
        repository, olap = self._repo([])
        repository.trend(0)
        self.assertIn("INTERVAL 0 DAY", olap.queries[0])

    def test_hospital_ranking_uses_final_names_and_limit(self):
        repository, olap = self._repo(
            [{"hospital_id": 1, "name": "第一医院", "value": 10}]
        )
        rows = repository.hospital_ranking(5)
        self.assertEqual(rows[0]["name"], "第一医院")
        sql = olap.queries[0]
        self.assertIn("ads_hospital_daily FINAL", sql)
        self.assertIn("dwd_hospital AS h", sql)
        self.assertIn("LEFT JOIN", sql)
        self.assertIn("ORDER BY r.value DESC", sql)
        self.assertIn("LIMIT 5", sql)

    def test_hospital_ranking_clamps_the_limit(self):
        repository, olap = self._repo([], max_ranking_limit=20)
        repository.hospital_ranking(10_000)
        self.assertIn("LIMIT 20", olap.queries[0])
        repository.hospital_ranking(0)
        self.assertIn("LIMIT 1", olap.queries[1])

    def test_department_ranking_uses_final_names_and_limit(self):
        repository, olap = self._repo(
            [{"hospital_id": 1, "department_id": 2, "name": "内科", "value": 8}]
        )
        rows = repository.department_ranking(10)
        self.assertEqual(rows[0]["department_id"], 2)
        sql = olap.queries[0]
        self.assertIn("ads_department_daily FINAL", sql)
        self.assertIn("dwd_department AS d", sql)
        self.assertIn("LIMIT 10", sql)

    def test_quality_health_query_uses_final_today_and_failures(self):
        row = {"quality_score": 0.97, "lag_seconds": 12, "open_alerts": 2}
        repository, olap = self._repo([row])
        self.assertEqual(repository.quality_health(), row)
        sql = olap.queries[0]
        self.assertEqual(sql.count("ads_quality_daily FINAL"), 2)
        self.assertIn("ads_pipeline_health FINAL", sql)
        self.assertIn("metric_date = today()", sql)
        self.assertIn("status = 'FAIL'", sql)
        self.assertIn("INTERVAL 24 HOUR", sql)
        self.assertIn("multiIf(severity = 'P0', 3, severity = 'P1', 2, 1)", sql)

    def test_quality_health_honours_the_health_window(self):
        repository, olap = self._repo([], health_window_hours=48)
        repository.quality_health()
        self.assertIn("INTERVAL 48 HOUR", olap.queries[0])

    def test_quality_health_defaults_missing_values(self):
        repository, _ = self._repo([])
        self.assertEqual(
            repository.quality_health(),
            {"quality_score": 0, "lag_seconds": 0, "open_alerts": 0},
        )

    def test_quality_health_normalizes_null_values(self):
        repository, _ = self._repo(
            [{"quality_score": None, "lag_seconds": None, "open_alerts": None}]
        )
        self.assertEqual(
            repository.quality_health(),
            {"quality_score": 0, "lag_seconds": 0, "open_alerts": 0},
        )

    def test_recent_alerts_queries_failed_quality_rows(self):
        rows = [{"severity": "P0", "title": "链路延迟"}]
        repository, olap = self._repo(rows)
        self.assertEqual(repository.recent_alerts(10), rows)
        sql = olap.queries[0]
        self.assertIn("ads_quality_daily FINAL", sql)
        self.assertIn("status = 'FAIL'", sql)
        self.assertIn("metric_date >= today() - INTERVAL 6 DAY", sql)
        self.assertIn("rule_name AS title", sql)
        self.assertIn("ORDER BY checked_at DESC", sql)
        self.assertIn("LIMIT 10", sql)

    def test_recent_alerts_clamps_the_limit(self):
        repository, olap = self._repo([], max_alert_limit=20)
        repository.recent_alerts(10_000)
        self.assertIn("LIMIT 20", olap.queries[0])

    def test_custom_database_is_used_in_every_query(self):
        repository, olap = self._repo([], database="dp_test")
        repository.overview()
        repository.trend(30)
        repository.hospital_ranking(10)
        repository.department_ranking(10)
        repository.quality_health()
        repository.recent_alerts(10)
        self.assertTrue(olap.queries)
        for sql in olap.queries:
            self.assertIn("dp_test.", sql)
            self.assertNotIn("data_platform.", sql)

    def test_invalid_database_identifier_is_rejected(self):
        for database in ("", "data_platform; DROP TABLE x", "bad-name", "1abc"):
            with self.subTest(database=database):
                with self.assertRaises(ValueError):
                    ClickHouseScreenRepository(RecordingOlap(), database=database)

    def test_every_query_reads_with_final(self):
        repository, olap = self._repo([])
        repository.overview()
        repository.trend(30)
        repository.hospital_ranking(10)
        repository.department_ranking(10)
        repository.quality_health()
        repository.recent_alerts(10)
        self.assertEqual(len(olap.queries), 6)
        for sql in olap.queries:
            self.assertIn("FINAL", sql)


@unittest.skipUnless(HAS_API, "backend dependencies not installed")
class ScreenApiTest(unittest.TestCase):
    def test_routes_cover_the_design_surface(self):
        from app.api import screen

        routes = {}
        for route in screen.router.routes:
            routes.setdefault(route.path, set()).update(route.methods)
        self.assertEqual(routes["/screen/overview"], {"GET"})
        self.assertEqual(routes["/screen/trend"], {"GET"})
        self.assertEqual(routes["/screen/ranking"], {"GET"})
        self.assertEqual(routes["/screen/quality"], {"GET"})
        self.assertEqual(routes["/screen/stream"], {"GET"})

    def test_overview_returns_the_envelope(self):
        from app.api import screen

        service = mock.Mock()
        service.overview.return_value = {"kpis": []}
        with mock.patch.object(screen, "_service", return_value=service):
            body = screen.overview()
        self.assertEqual(body, {"code": 0, "message": "ok", "data": {"kpis": []}})
        service.overview.assert_called_once_with()

    def test_trend_uses_the_configured_default_and_bound(self):
        from app.api import screen

        settings = SimpleNamespace(
            screen_trend_days=30, screen_max_trend_days=90
        )
        service = mock.Mock()
        service.trend.return_value = []
        with mock.patch.object(
            screen, "get_settings", return_value=settings
        ), mock.patch.object(screen, "_service", return_value=service):
            screen.trend(days=None)
            screen.trend(days=365)
            screen.trend(days=7)
        self.assertEqual(service.trend.call_args_list[0].args, (30,))
        self.assertEqual(service.trend.call_args_list[1].args, (90,))
        self.assertEqual(service.trend.call_args_list[2].args, (7,))

    def test_ranking_uses_the_configured_default_and_bound(self):
        from app.api import screen

        settings = SimpleNamespace(
            screen_ranking_limit=10, screen_max_ranking_limit=100
        )
        service = mock.Mock()
        service.ranking.return_value = {"hospitals": [], "departments": []}
        with mock.patch.object(
            screen, "get_settings", return_value=settings
        ), mock.patch.object(screen, "_service", return_value=service):
            screen.ranking(limit=None)
            screen.ranking(limit=500)
            screen.ranking(limit=5)
        self.assertEqual(service.ranking.call_args_list[0].args, (10,))
        self.assertEqual(service.ranking.call_args_list[1].args, (100,))
        self.assertEqual(service.ranking.call_args_list[2].args, (5,))

    def test_quality_returns_the_envelope(self):
        from app.api import screen

        service = mock.Mock()
        service.quality.return_value = {"health": {}, "alerts": []}
        with mock.patch.object(screen, "_service", return_value=service):
            body = screen.quality()
        self.assertEqual(body["code"], 0)
        self.assertEqual(body["data"], {"health": {}, "alerts": []})

    def test_service_injects_the_alert_limit(self):
        from app.api import screen

        settings = SimpleNamespace(
            olap_store="memory",
            clickhouse_url="http://127.0.0.1:8123",
            clickhouse_database="data_platform",
            screen_max_trend_days=365,
            screen_max_ranking_limit=100,
            screen_recent_alert_limit=7,
        )
        with mock.patch.object(
            screen, "get_settings", return_value=settings
        ), mock.patch.object(
            screen, "ClickHouseScreenRepository"
        ) as repository, mock.patch.object(
            screen, "ScreenService"
        ) as service:
            screen._service()
        self.assertEqual(service.call_args.kwargs["alert_limit"], 7)
        self.assertEqual(repository.call_args.kwargs["database"], "data_platform")
        self.assertEqual(repository.call_args.kwargs["max_trend_days"], 365)
        self.assertEqual(repository.call_args.kwargs["max_ranking_limit"], 100)

    def test_query_failure_is_503(self):
        from fastapi import HTTPException

        from app.api import screen

        with mock.patch.object(
            screen, "_service", side_effect=RuntimeError("no driver")
        ):
            with self.assertRaises(HTTPException) as context:
                screen.overview()
        self.assertEqual(context.exception.status_code, 503)


@unittest.skipUnless(HAS_API, "backend dependencies not installed")
class ScreenStreamTest(unittest.IsolatedAsyncioTestCase):
    async def test_stream_emits_raw_overview_and_stops_on_disconnect(self):
        from app.api import screen

        settings = SimpleNamespace(screen_stream_interval_seconds=5.0)
        service = mock.Mock()
        service.overview.return_value = {
            "kpis": [{"key": "reg_count", "label": "今日挂号量", "value": 1}]
        }
        with mock.patch.object(
            screen, "get_settings", return_value=settings
        ), mock.patch.object(screen, "_service", return_value=service):
            response = await screen.stream()
            self.assertEqual(response.media_type, "text/event-stream")
            iterator = response.body_iterator
            first = await iterator.__anext__()
            self.assertEqual(first, format_sse(service.overview.return_value))
            self.assertTrue(first.startswith("data: "))
            self.assertNotIn('"code"', first)
            with self.assertRaises(asyncio.CancelledError):
                await iterator.athrow(asyncio.CancelledError())

    async def test_stream_emits_an_error_frame_instead_of_dying(self):
        from app.api import screen

        settings = SimpleNamespace(screen_stream_interval_seconds=5.0)
        service = mock.Mock()
        service.overview.side_effect = RuntimeError("warehouse down")
        with mock.patch.object(
            screen, "get_settings", return_value=settings
        ), mock.patch.object(screen, "_service", return_value=service):
            response = await screen.stream()
            iterator = response.body_iterator
            frame = await iterator.__anext__()
            self.assertEqual(frame, format_sse({"error": "screen query failed"}))
            with self.assertRaises(asyncio.CancelledError):
                await iterator.athrow(asyncio.CancelledError())


if __name__ == "__main__":
    unittest.main()
