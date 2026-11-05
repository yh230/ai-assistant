# tests/unit/test_watermark.py
import unittest
from datetime import datetime
from decimal import Decimal

from etl.watermark import advance_numeric, advance_watermark, lower_bound


class WatermarkTest(unittest.TestCase):
    def test_lower_bound_applies_overlap(self):
        value = lower_bound(datetime(2026, 11, 4, 12, 0, 0), 60)
        self.assertEqual(value, datetime(2026, 11, 4, 11, 59, 0))

    def test_lower_bound_without_watermark_is_none(self):
        self.assertIsNone(lower_bound(None, 60))

    def test_advance_keeps_maximum(self):
        current = datetime(2026, 11, 4, 12, 0, 0)
        self.assertEqual(advance_watermark(current, datetime(2026, 11, 4, 11, 0, 0)), current)
        self.assertEqual(
            advance_watermark(current, datetime(2026, 11, 4, 13, 0, 0)),
            datetime(2026, 11, 4, 13, 0, 0),
        )

    def test_advance_numeric_is_monotonic(self):
        self.assertEqual(advance_numeric(Decimal("10"), Decimal("8")), Decimal("10"))
        self.assertEqual(advance_numeric(Decimal("10"), 11), Decimal("11"))
        self.assertIsNone(advance_numeric(None, None))


if __name__ == "__main__":
    unittest.main()
