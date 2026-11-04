# tests/unit/test_config.py
import importlib.util
import os
import unittest
from unittest import mock

if importlib.util.find_spec("pydantic_settings") is not None:
    from app.core.config import Settings
else:
    Settings = None


@unittest.skipIf(Settings is None, "pydantic-settings not installed")
class SettingsTest(unittest.TestCase):
    def test_defaults(self):
        settings = Settings(_env_file=None)
        self.assertEqual(settings.kafka_consumer_group, "dp-ods-loader")
        self.assertEqual(settings.kafka_topic_prefix, "dp.ods")
        self.assertEqual(settings.timezone, "Asia/Shanghai")
        self.assertEqual(settings.alert_webhook_url, "")
        self.assertEqual(settings.event_bus, "memory")
        self.assertFalse(settings.dp_enable_scheduler)
        self.assertEqual(settings.screen_trend_days, 30)
        self.assertEqual(settings.screen_max_trend_days, 365)
        self.assertEqual(settings.screen_ranking_limit, 10)
        self.assertEqual(settings.screen_max_ranking_limit, 100)
        self.assertEqual(settings.screen_recent_alert_limit, 10)
        self.assertEqual(settings.screen_stream_interval_seconds, 5.0)

    def test_env_reads_app_env_variable(self):
        with mock.patch.dict(os.environ, {"APP_ENV": "prod"}, clear=True):
            settings = Settings(_env_file=None)
        self.assertEqual(settings.env, "prod")
