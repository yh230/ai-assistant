# tests/unit/test_api_health.py
import importlib.util
import unittest

HAS_STACK = all(importlib.util.find_spec(name) for name in ("fastapi", "sqlalchemy", "pydantic_settings"))


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class HealthApiTest(unittest.TestCase):
    def test_health(self):
        from fastapi.testclient import TestClient
        from app.main import create_app

        response = TestClient(create_app()).get("/api/health")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["code"], 0)
        self.assertEqual(body["data"]["status"], "ok")
