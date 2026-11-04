# tests/unit/test_demo_screen.py
import unittest
from datetime import date, datetime

from app.demo_screen import DemoScreenRepository


class DemoScreenRepositoryTest(unittest.TestCase):
    def setUp(self):
        self.today = date(2026, 11, 4)
        self.repository = DemoScreenRepository(today=self.today)

    def test_overview_has_non_zero_traffic_values(self):
        data = self.repository.overview()
        self.assertGreater(data["reg_count"], 0)
        self.assertGreater(data["ai_sessions"], 0)
        self.assertGreater(data["cancel_rate"], 0)
        self.assertLess(data["cancel_rate"], 1)

    def test_overview_is_deterministic_for_the_same_day(self):
        self.assertEqual(
            self.repository.overview(),
            DemoScreenRepository(today=self.today).overview(),
        )

    def test_trend_ends_today_and_has_the_requested_window(self):
        rows = self.repository.trend(7)
        self.assertEqual(len(rows), 7)
        self.assertEqual(rows[-1]["date"], "2026-11-04")
        self.assertEqual(rows[0]["date"], "2026-10-29")
        self.assertTrue(all(row["value"] > 0 for row in rows))

    def test_trend_clamps_invalid_windows(self):
        self.assertEqual(len(self.repository.trend(0)), 1)
        self.assertEqual(len(self.repository.trend(-5)), 1)
        self.assertEqual(len(self.repository.trend(10_000)), 365)

    def test_hospital_ranking_is_sorted_and_limited(self):
        rows = self.repository.hospital_ranking(3)
        self.assertEqual(len(rows), 3)
        values = [row["value"] for row in rows]
        self.assertEqual(values, sorted(values, reverse=True))
        self.assertTrue(all(row["name"] and row["hospital_id"] for row in rows))

    def test_department_ranking_is_sorted_and_limited(self):
        rows = self.repository.department_ranking(5)
        self.assertEqual(len(rows), 5)
        values = [row["value"] for row in rows]
        self.assertEqual(values, sorted(values, reverse=True))
        self.assertTrue(all(row["department_id"] for row in rows))

    def test_rankings_clamp_the_limit(self):
        self.assertEqual(len(self.repository.hospital_ranking(0)), 1)
        self.assertEqual(len(self.repository.hospital_ranking(10_000)), 5)
        self.assertEqual(len(self.repository.department_ranking(10_000)), 10)

    def test_quality_health_is_a_ratio_with_a_lag(self):
        health = self.repository.quality_health()
        self.assertGreaterEqual(health["quality_score"], 0.9)
        self.assertLessEqual(health["quality_score"], 1)
        self.assertGreater(health["lag_seconds"], 0)
        self.assertGreaterEqual(health["open_alerts"], 0)

    def test_open_alerts_match_the_visible_alert_card(self):
        health = self.repository.quality_health()
        alerts = self.repository.recent_alerts(10)
        self.assertEqual(health["open_alerts"], len(alerts))

    def test_recent_alerts_have_the_screen_shape(self):
        rows = self.repository.recent_alerts(2)
        self.assertEqual(len(rows), 2)
        for row in rows:
            self.assertIn(row["severity"], {"P0", "P1", "P2"})
            self.assertTrue(row["title"])
            self.assertIsInstance(row["checked_at"], datetime)

    def test_recent_alerts_are_ordered_newest_first(self):
        rows = self.repository.recent_alerts(10)
        timestamps = [row["checked_at"] for row in rows]
        self.assertEqual(timestamps, sorted(timestamps, reverse=True))

    def test_recent_alerts_limit_is_bounded(self):
        self.assertEqual(len(self.repository.recent_alerts(0)), 1)
        self.assertEqual(len(self.repository.recent_alerts(10_000)), 2)


if __name__ == "__main__":
    unittest.main()
