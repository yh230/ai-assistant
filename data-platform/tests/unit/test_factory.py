# tests/unit/test_factory.py
import importlib.util
import unittest

from etl.adapters.factory import build_event_bus, build_olap_store
from etl.adapters.inmemory_event_bus import InMemoryEventBus
from etl.adapters.inmemory_olap_store import InMemoryOlapStore


class FactoryTest(unittest.TestCase):
    def test_memory_event_bus(self):
        self.assertIsInstance(
            build_event_bus("memory", "127.0.0.1:9092", "g"), InMemoryEventBus
        )

    def test_memory_olap_store(self):
        self.assertIsInstance(
            build_olap_store("memory", "http://127.0.0.1:8123", "db"), InMemoryOlapStore
        )

    @unittest.skipIf(
        importlib.util.find_spec("confluent_kafka") is not None, "confluent-kafka installed"
    )
    def test_kafka_adapter_reports_missing_dependency(self):
        with self.assertRaises(RuntimeError) as context:
            build_event_bus("kafka", "127.0.0.1:9092", "g")
        self.assertIn("confluent-kafka", str(context.exception))

    @unittest.skipIf(
        importlib.util.find_spec("clickhouse_connect") is not None, "clickhouse-connect installed"
    )
    def test_clickhouse_adapter_reports_missing_dependency(self):
        with self.assertRaises(RuntimeError) as context:
            build_olap_store("clickhouse", "http://127.0.0.1:8123", "db")
        self.assertIn("clickhouse-connect", str(context.exception))

    def test_kafka_adapter_module_imports_without_client_library(self):
        from etl.adapters import kafka_event_bus

        self.assertTrue(hasattr(kafka_event_bus, "KafkaEventBus"))

    def test_clickhouse_adapter_module_imports_without_client_library(self):
        from etl.adapters import clickhouse_olap_store

        self.assertTrue(hasattr(clickhouse_olap_store, "ClickHouseOlapStore"))

    def test_clickhouse_session_settings_pin_the_platform_clock(self):
        from etl.adapters.clickhouse_olap_store import SESSION_SETTINGS

        self.assertEqual(SESSION_SETTINGS["session_timezone"], "Asia/Shanghai")
        self.assertEqual(SESSION_SETTINGS["input_format_null_as_default"], 1)

    def test_unknown_event_bus_kind_is_rejected(self):
        with self.assertRaises(ValueError):
            build_event_bus("redis", "127.0.0.1:6379", "g")

    def test_unknown_olap_store_kind_is_rejected(self):
        with self.assertRaises(ValueError):
            build_olap_store("druid", "http://127.0.0.1:8082", "db")


if __name__ == "__main__":
    unittest.main()
