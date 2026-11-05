# tests/unit/test_time.py
import unittest
from datetime import datetime, timedelta, timezone

from app.core.time import now_shanghai


class PlatformClockTest(unittest.TestCase):
    def test_now_shanghai_is_naive_utc_plus_eight(self):
        value = now_shanghai()
        expected = datetime.now(timezone(timedelta(hours=8))).replace(tzinfo=None)

        self.assertIsNone(value.tzinfo)
        self.assertLess(abs((value - expected).total_seconds()), 5)


if __name__ == "__main__":
    unittest.main()
