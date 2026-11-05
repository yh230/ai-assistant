# tests/unit/test_envelope.py
import unittest
from datetime import date, datetime, time, timedelta
from decimal import Decimal

from etl.envelope import Envelope


class EnvelopeTest(unittest.TestCase):
    def test_roundtrip(self):
        original = Envelope(
            event_id="e-1",
            event_type="ODS_ROW",
            occurred_at=1730000000000,
            payload={
                "source_db": "registration_db",
                "source_table": "registration",
                "row": {"id": 1},
            },
        )
        restored = Envelope.from_bytes(original.to_bytes())
        self.assertEqual(restored.event_id, "e-1")
        self.assertEqual(restored.payload["source_table"], "registration")

    def test_rejects_missing_fields(self):
        with self.assertRaises(ValueError):
            Envelope.from_bytes(b'{"eventId":"e-1"}')

    def test_rejects_non_object_payload(self):
        raw = b'{"eventId":"e-1","eventType":"ODS_ROW","occurredAt":1,"payload":[]}'
        with self.assertRaises(ValueError):
            Envelope.from_bytes(raw)

    def test_serialises_datetime_and_decimal_row_values(self):
        original = Envelope(
            event_id="e-2",
            event_type="ODS_ROW",
            occurred_at=1730000000000,
            payload={
                "row": {
                    "updated_at": datetime(2026, 11, 4, 10, 1, 0),
                    "schedule_date": date(2026, 11, 4),
                    "period_start": time(8, 30, 0),
                    "duration": timedelta(hours=1, minutes=15),
                    "pay_amount": Decimal("10.50"),
                }
            },
        )
        restored = Envelope.from_bytes(original.to_bytes())
        self.assertEqual(restored.payload["row"]["updated_at"], "2026-11-04T10:01:00")
        self.assertEqual(restored.payload["row"]["schedule_date"], "2026-11-04")
        self.assertEqual(restored.payload["row"]["period_start"], "08:30:00")
        self.assertEqual(restored.payload["row"]["duration"], "01:15:00")
        self.assertEqual(restored.payload["row"]["pay_amount"], "10.50")


if __name__ == "__main__":
    unittest.main()
