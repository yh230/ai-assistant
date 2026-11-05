# tests/unit/test_cleaning.py
import unittest
from decimal import Decimal

from etl.cleaning import (
    clean_chat_message,
    clean_registration,
    clean_row,
    mask_phone,
    sanitize_sample,
)


class CleaningTest(unittest.TestCase):
    def test_masks_pii_and_normalizes(self):
        result = clean_registration({
            "id": 1,
            "registration_no": "R001",
            "hospital_id": 1,
            "department_id": 2,
            "doctor_user_id": 3,
            "schedule_date": "2026-11-04",
            "period": 1,
            "patient_name": "张三",
            "patient_phone": "13800138000",
            "patient_id_card": "110101199001011234",
            "source_type": 1,
            "pay_status": 1,
            "pay_amount": "10.50",
            "status": 3,
            "create_time": "2026-11-04 09:00:00",
            "update_time": "2026-11-04 09:30:00",
        })
        self.assertEqual(result.errors, ())
        self.assertEqual(result.row["patient_name_masked"], "张*")
        self.assertEqual(result.row["patient_phone_masked"], "138****8000")
        self.assertNotIn("patient_id_card", result.row)
        self.assertEqual(result.row["pay_amount"], Decimal("10.50"))

    def test_rejects_invalid_status(self):
        result = clean_registration(
            {"id": 1, "hospital_id": 1, "status": 99, "schedule_date": "2026-11-04"}
        )
        self.assertTrue(any("status" in error for error in result.errors))

    def test_phone_mask_handles_short_values(self):
        self.assertEqual(mask_phone("123"), "***")
        self.assertEqual(mask_phone(None), "")

    def test_phone_mask_masks_seven_digit_values(self):
        self.assertEqual(mask_phone("1234567"), "*******")
        self.assertEqual(mask_phone("123-4567"), "*******")

    def test_phone_mask_masks_eleven_digit_values(self):
        self.assertEqual(mask_phone("13800138000"), "138****8000")
        self.assertEqual(mask_phone("13912345678"), "139****5678")

    def test_null_registration_no_becomes_empty_string(self):
        result = clean_registration({
            "id": 1,
            "hospital_id": 1,
            "registration_no": None,
            "schedule_date": "2026-11-04",
            "period": 1,
            "status": 3,
            "pay_amount": 10,
        })
        self.assertEqual(result.errors, ())
        self.assertEqual(result.row["registration_no"], "")

    def test_null_session_id_is_rejected_not_stringified(self):
        result = clean_chat_message({"id": 7, "session_id": None, "role": "user"})
        self.assertIsNone(result.row)
        self.assertEqual(result.errors, ("session_id is required",))

    def test_chat_session_projection_requires_id_and_session_id(self):
        result = clean_row("chat_session", {"id": 1})
        self.assertIsNone(result.row)
        self.assertEqual(result.errors, ("session_id is required",))

        null_result = clean_row("chat_session", {"id": 1, "session_id": None})
        self.assertIsNone(null_result.row)
        self.assertEqual(null_result.errors, ("session_id is required",))

    def test_hospital_requires_identity_fields(self):
        result = clean_row("hospital", {"id": 1})
        self.assertIsNone(result.row)
        self.assertEqual(result.errors, ("name is required",))

        null_result = clean_row("hospital", {"id": 1, "name": None})
        self.assertIsNone(null_result.row)
        self.assertEqual(null_result.errors, ("name is required",))

        valid = clean_row("hospital", {"id": 1, "name": "H"})
        self.assertEqual(valid.errors, ())
        self.assertEqual(valid.row["name"], "H")

    def test_doctor_schedule_requires_schedule_fields(self):
        result = clean_row(
            "doctor_schedule",
            {"id": 1, "hospital_id": 2, "schedule_date": "2026-11-04", "period": 1},
        )
        self.assertIsNone(result.row)
        self.assertEqual(
            result.errors,
            ("department_id is required", "doctor_user_id is required"),
        )

    def test_number_source_requires_seq_no(self):
        result = clean_row(
            "number_source",
            {
                "id": 1,
                "hospital_id": 1,
                "schedule_id": 2,
                "schedule_date": "2026-11-04",
                "period": 1,
            },
        )
        self.assertIsNone(result.row)
        self.assertEqual(result.errors, ("seq_no is required",))

    def test_chat_message_keeps_metadata_not_content(self):
        result = clean_chat_message({
            "id": 7,
            "session_id": "s-1",
            "role": "user",
            "content": "你好",
            "reference_json": None,
        })
        self.assertEqual(result.errors, ())
        self.assertEqual(result.row["content_length"], 2)
        self.assertFalse(result.row["has_reference"])
        self.assertNotIn("content", result.row)

    def test_rejects_invalid_chat_role(self):
        result = clean_chat_message({"id": 7, "role": "system"})
        self.assertIsNone(result.row)
        self.assertTrue(any("role" in error for error in result.errors))

    def test_unknown_source_table_is_rejected(self):
        result = clean_row("sys_oper_log", {"id": 1})
        self.assertIsNone(result.row)
        self.assertTrue(any("unknown source table" in error for error in result.errors))

    def test_row_without_primary_key_is_rejected(self):
        result = clean_row("chat_message", {"role": "user", "content": "hi"})
        self.assertIsNone(result.row)
        self.assertTrue(result.errors)

    def test_non_object_row_is_rejected(self):
        result = clean_row("registration", ["not", "a", "row"])
        self.assertIsNone(result.row)
        self.assertEqual(result.errors, ("row must be a JSON object",))

    def test_unknown_source_columns_are_dropped(self):
        result = clean_row(
            "department",
            {"id": 1, "hospital_id": 2, "name": "D", "unknown_column": "x"},
        )
        self.assertEqual(result.errors, ())
        self.assertNotIn("unknown_column", result.row)

    def test_sanitize_sample_strips_pii_keys(self):
        sample = sanitize_sample({
            "id": 1,
            "patient_name": "张三",
            "patient_phone": "13800138000",
            "patient_id_card": "110101199001011234",
            "content": "你好",
            "reference_json": {"doc": "x"},
            "status": 99,
            "nested": {"doctor_name": "李四", "keep": 1},
            "items": [{"contact_phone": "123"}],
        })
        self.assertEqual(
            sample,
            {"id": 1, "status": 99, "nested": {"keep": 1}, "items": [{}]},
        )
        self.assertNotIn("张三", repr(sample))
        self.assertNotIn("13800138000", repr(sample))

    def test_sanitize_sample_passes_scalars_through(self):
        # A non-object row is never copied; only its type is recorded.
        self.assertEqual(sanitize_sample(["a", 1, None]), {"type": "list"})
        self.assertEqual(sanitize_sample("raw-pii"), {"type": "str"})
        self.assertEqual(sanitize_sample(b"raw"), {"type": "bytes"})

    def test_sanitize_sample_keeps_nested_scalars(self):
        self.assertEqual(
            sanitize_sample({"items": ["a", 1, None]}),
            {"items": ["a", 1, None]},
        )

    def test_precomputed_masks_survive_recleaning(self):
        result = clean_registration({
            "id": 1,
            "hospital_id": 1,
            "schedule_date": "2026-11-04",
            "period": 1,
            "status": 3,
            "pay_amount": "10.50",
            "patient_name_masked": "张*",
            "patient_phone_masked": "138****8000",
        })
        self.assertEqual(result.errors, ())
        self.assertEqual(result.row["patient_name_masked"], "张*")
        self.assertEqual(result.row["patient_phone_masked"], "138****8000")

    def test_precomputed_chat_length_survives_recleaning(self):
        result = clean_chat_message({
            "id": 7,
            "session_id": "s-1",
            "role": "user",
            "content_length": 42,
            "has_reference": True,
        })
        self.assertEqual(result.errors, ())
        self.assertEqual(result.row["content_length"], 42)
        self.assertTrue(result.row["has_reference"])

    def test_error_strings_do_not_echo_source_values(self):
        for table, row in (
            ("registration", {"id": 1, "hospital_id": 1, "status": 999,
                              "schedule_date": "2026-11-04", "period": 1}),
            ("chat_message", {"id": 1, "session_id": "s-1", "role": "system-secret"}),
            ("chat_message", {"id": "not-a-number", "session_id": "s-1", "role": "user"}),
        ):
            with self.subTest(table=table, row=row):
                result = clean_row(table, row)
                self.assertIsNone(result.row)
                self.assertTrue(result.errors)
                self.assertNotIn("999", " ".join(result.errors))
                self.assertNotIn("system-secret", " ".join(result.errors))
                self.assertNotIn("not-a-number", " ".join(result.errors))


if __name__ == "__main__":
    unittest.main()
