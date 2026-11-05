# tests/unit/test_api_health.py
import importlib.util
import os
import sys
import tempfile
import unittest
from unittest import mock

HAS_STACK = all(
    importlib.util.find_spec(name) is not None
    for name in ("fastapi", "sqlalchemy", "pydantic_settings", "httpx", "cryptography")
)


def _sqlite_url(path):
    return "sqlite:///" + path.replace("\\", "/")


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class HealthApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # app.core.db builds its engine at import time, so point METADATA_DB_URL
        # at a throwaway SQLite file before importing app.main; otherwise
        # create_app() would create data-platform.db in the repository. This only
        # takes effect if app.core.db has not been imported by an earlier test
        # module yet (it is the first module in discovery order).
        cls._tmpdir = tempfile.TemporaryDirectory()
        cls._previous_db_url = os.environ.get("METADATA_DB_URL")
        cls._previous_demo_mode = os.environ.get("DEMO_MODE")
        os.environ["METADATA_DB_URL"] = _sqlite_url(
            os.path.join(cls._tmpdir.name, "health-test.db")
        )
        # The developer .env may enable the demo seed; these tests assert the
        # normal (empty metadata, JWT-protected) behaviour.
        os.environ["DEMO_MODE"] = "false"

        from app.main import create_app

        cls._create_app = staticmethod(create_app)

    @classmethod
    def tearDownClass(cls):
        db_module = sys.modules.get("app.core.db")
        if db_module is not None:
            db_module.engine.dispose()
        if cls._previous_db_url is None:
            os.environ.pop("METADATA_DB_URL", None)
        else:
            os.environ["METADATA_DB_URL"] = cls._previous_db_url
        if cls._previous_demo_mode is None:
            os.environ.pop("DEMO_MODE", None)
        else:
            os.environ["DEMO_MODE"] = cls._previous_demo_mode
        cls._tmpdir.cleanup()

    def test_health(self):
        from fastapi.testclient import TestClient

        response = TestClient(self._create_app()).get("/api/health")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["code"], 0)
        self.assertEqual(body["data"]["status"], "ok")

    def test_unknown_route_returns_error_envelope(self):
        from fastapi.testclient import TestClient

        response = TestClient(self._create_app()).get("/api/missing-route")
        self.assertEqual(response.status_code, 404)
        body = response.json()
        self.assertEqual(body["code"], 404)
        self.assertIn("message", body)
        self.assertIsNone(body["data"])

    def test_route_http_error_returns_error_envelope(self):
        from fastapi.testclient import TestClient

        response = TestClient(self._create_app()).get("/api/datasources")
        self.assertEqual(response.status_code, 401)
        body = response.json()
        self.assertEqual(body["code"], 401)
        self.assertEqual(body["message"], "missing token")
        self.assertIsNone(body["data"])

    def test_unhandled_error_returns_a_generic_500_envelope(self):
        from fastapi.testclient import TestClient

        app = self._create_app()

        @app.get("/api/_boom")
        def boom():
            raise RuntimeError("secret internal detail")

        response = TestClient(app, raise_server_exceptions=False).get("/api/_boom")

        self.assertEqual(response.status_code, 500)
        body = response.json()
        self.assertEqual(body["code"], 500)
        self.assertEqual(body["message"], "internal error")
        self.assertIsNone(body["data"])
        self.assertNotIn("secret internal detail", response.text)

    def test_screen_routes_are_registered(self):
        # FastAPI 0.162 includes routers lazily; the OpenAPI schema is the
        # stable surface that resolves every included route.
        paths = set(self._create_app().openapi()["paths"])
        for path in (
            "/api/screen/overview",
            "/api/screen/trend",
            "/api/screen/ranking",
            "/api/screen/quality",
            "/api/screen/stream",
        ):
            self.assertIn(path, paths)

    def test_screen_overview_returns_six_zero_kpis_for_an_empty_store(self):
        from fastapi.testclient import TestClient

        # The default in-memory OLAP store has no ADS rows, so every KPI must
        # fall back to 0 instead of failing or returning null.  Pin the store
        # kind so a developer .env cannot reach a real ClickHouse here.
        with mock.patch.dict(os.environ, {"OLAP_STORE": "memory"}):
            response = TestClient(self._create_app()).get("/api/screen/overview")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["code"], 0)
        self.assertEqual(
            [item["key"] for item in body["data"]["kpis"]],
            [
                "reg_count",
                "ai_sessions",
                "quality_score",
                "lag_seconds",
                "open_alerts",
                "cancel_rate",
            ],
        )
        self.assertEqual({item["value"] for item in body["data"]["kpis"]}, {0})
