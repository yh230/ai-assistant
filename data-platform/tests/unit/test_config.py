# tests/unit/test_config.py
import unittest

try:
    from app.core.config import Settings
except ImportError:
    Settings = None


@unittest.skipIf(Settings is None, "pydantic-settings not installed")
class SettingsTest(unittest.TestCase):
    def test_defaults(self):
        settings = Settings(_env_file=None)
        self.assertEqual(settings.kafka_consumer_group, "dp-ods-loader")
        self.assertEqual(settings.kafka_topic_prefix, "dp.ods")
        self.assertEqual(settings.timezone, "Asia/Shanghai")
        self.assertEqual(settings.event_bus, "memory")
