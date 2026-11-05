# tests/unit/test_privacy.py
import unittest

from etl.privacy import redact_source_row


class PrivacyTest(unittest.TestCase):
    def test_registration_removes_direct_pii_and_adds_masks(self):
        row = {
            "id": 1,
            "hospital_id": 2,
            "patient_name": "张三",
            "patient_phone": "13800138000",
            "patient_id_card": "110101199001011234",
            "status": 3,
        }

        redacted = redact_source_row("registration", row)

        for key in ("patient_name", "patient_phone", "patient_id_card"):
            self.assertNotIn(key, redacted)
        self.assertEqual(redacted["patient_name_masked"], "张*")
        self.assertEqual(redacted["patient_phone_masked"], "138****8000")
        self.assertNotIn("张三", repr(redacted))
        self.assertNotIn("13800138000", repr(redacted))
        self.assertNotIn("110101199001011234", repr(redacted))
        # The source row is not mutated: the extractor may still need its keys.
        self.assertIn("patient_phone", row)

    def test_chat_message_removes_content_and_reference(self):
        row = {
            "id": 7,
            "session_id": "s-1",
            "role": "user",
            "content": "你好，我是张三",
            "reference_json": {"doc": "secret"},
        }

        redacted = redact_source_row("chat_message", row)

        self.assertNotIn("content", redacted)
        self.assertNotIn("reference_json", redacted)
        self.assertNotIn("张三", repr(redacted))
        self.assertEqual(redacted["content_length"], len("你好，我是张三"))
        self.assertTrue(redacted["has_reference"])

    def test_chat_message_keeps_precomputed_fields(self):
        redacted = redact_source_row(
            "chat_message",
            {
                "id": 7,
                "session_id": "s-1",
                "role": "user",
                "content_length": 12,
                "has_reference": True,
            },
        )

        self.assertEqual(redacted["content_length"], 12)
        self.assertTrue(redacted["has_reference"])

    def test_other_tables_are_unchanged_copies(self):
        row = {"id": 1, "name": "H", "phone": "010-1234"}
        redacted = redact_source_row("hospital", row)

        self.assertEqual(redacted, row)
        self.assertIsNot(redacted, row)

    def test_non_dict_rows_are_returned_unchanged(self):
        self.assertEqual(redact_source_row("registration", ["x"]), ["x"])
        self.assertEqual(redact_source_row("registration", b"raw"), b"raw")


if __name__ == "__main__":
    unittest.main()
