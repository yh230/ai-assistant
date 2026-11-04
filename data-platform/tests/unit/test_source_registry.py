# tests/unit/test_source_registry.py
import unittest

from etl.source_registry import (
    SOURCES,
    SOURCE_SCHEMAS,
    SOURCE_TABLES,
    source_by_table,
    source_for,
    target_table_for,
    topic_for,
    validate_source_target,
)


class SourceRegistryTest(unittest.TestCase):
    def test_seven_sources_are_registered(self):
        self.assertEqual(len(SOURCES), 7)
        self.assertEqual(len(SOURCE_TABLES), 7)
        self.assertEqual(len(SOURCE_SCHEMAS), 7)
        self.assertEqual(
            {spec.target_table for spec in SOURCES},
            {
                "ods_registration",
                "ods_doctor_schedule",
                "ods_number_source",
                "ods_chat_session",
                "ods_chat_message",
                "ods_hospital",
                "ods_department",
            },
        )

    def test_system_db_is_not_a_supported_source(self):
        self.assertIsNone(source_for("system_db", "sys_oper_log"))
        self.assertIsNone(source_by_table("sys_oper_log"))

    def test_validate_accepts_the_registered_target(self):
        spec = validate_source_target("chat_db", "chat_message", "ods_chat_message")
        self.assertEqual(spec.target_table, "ods_chat_message")

    def test_validate_rejects_an_unsupported_source(self):
        with self.assertRaises(ValueError) as context:
            validate_source_target("system_db", "sys_oper_log", "ods_sys_oper_log")
        self.assertIn("unsupported source table", str(context.exception))

    def test_validate_rejects_a_target_mismatch(self):
        with self.assertRaises(ValueError) as context:
            validate_source_target("chat_db", "chat_message", "ods_hospital")
        self.assertIn("target_table must be ods_chat_message", str(context.exception))

    def test_target_table_for_falls_back_to_the_registry(self):
        self.assertEqual(target_table_for("hospital"), "ods_hospital")
        self.assertEqual(target_table_for("hospital", "ods_hospital"), "ods_hospital")

    def test_target_table_for_rejects_a_declared_mismatch(self):
        with self.assertRaises(ValueError):
            target_table_for("hospital", "ods_registration")

    def test_topic_follows_the_schema_and_prefix(self):
        spec = source_for("registration_db", "registration")
        self.assertEqual(topic_for("dp.stage", spec), "dp.stage.registration_db.registration")


if __name__ == "__main__":
    unittest.main()
