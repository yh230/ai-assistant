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
        self.assertEqual(settings.event_bus, "memory")

    def test_env_reads_app_env_variable(self):
        with mock.patch.dict(os.environ, {"APP_ENV": "prod"}, clear=True):
            settings = Settings(_env_file=None)
        self.assertEqual(settings.env, "prod")
