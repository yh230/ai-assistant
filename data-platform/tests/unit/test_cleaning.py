# tests/unit/test_cleaning.py
import unittest

from etl.cleaning import clean_chat_message, clean_registration, clean_row, mask_phone


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
        self.assertEqual(result.row["pay_amount"], 10.5)

    def test_rejects_invalid_status(self):
        result = clean_registration(
            {"id": 1, "hospital_id": 1, "status": 99, "schedule_date": "2026-11-04"}
        )
        self.assertTrue(any("status" in error for error in result.errors))

    def test_phone_mask_handles_short_values(self):
        self.assertEqual(mask_phone("123"), "***")
        self.assertEqual(mask_phone(None), "")

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
        result = clean_row("department", {"id": 1, "hospital_id": 2, "unknown_column": "x"})
        self.assertEqual(result.errors, ())
        self.assertNotIn("unknown_column", result.row)


if __name__ == "__main__":
    unittest.main()
