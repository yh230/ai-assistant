"""Deterministic screen fixtures for DEMO_MODE.

The demo mode shows the dashboard and governance pages with only SQLite and
Python: no Kafka, ClickHouse or MySQL is contacted.  Values are derived from
the platform date so a repeated query on the same day is stable; they are
presentation fixtures, not evidence of pipeline correctness.
"""

import math
from datetime import date, datetime, timedelta

from app.core.time import now_shanghai

DEFAULT_TREND_DAYS = 30
MAX_TREND_DAYS = 365

# (id, name, share of today's registrations)
_HOSPITALS: tuple[tuple[int, str, float], ...] = (
    (1, "市第一人民医院", 0.29),
    (2, "仁和医院", 0.23),
    (3, "儿童医学中心", 0.19),
    (4, "中医医院", 0.16),
    (5, "康复医院", 0.13),
)

# (id, name, share of today's registrations)
_DEPARTMENTS: tuple[tuple[int, str, float], ...] = (
    (101, "心血管内科", 0.16),
    (102, "呼吸内科", 0.14),
    (103, "普通外科", 0.12),
    (104, "儿科", 0.10),
    (105, "妇产科", 0.09),
    (106, "骨科", 0.08),
    (107, "眼科", 0.07),
    (108, "耳鼻喉科", 0.06),
    (109, "皮肤科", 0.05),
    (110, "神经内科", 0.04),
)


def _bound(value: int, minimum: int, maximum: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return minimum
    return max(minimum, min(number, maximum))


class DemoScreenRepository:
    """ScreenRepository fixture used only when ``DEMO_MODE=true``."""

    def __init__(self, today: date | None = None):
        self._today = today or now_shanghai().date()

    def _now(self) -> datetime:
        # Keep the injected day (for reproducible tests) but use the current
        # wall-clock time so "x minutes ago" renders naturally.
        return datetime.combine(self._today, now_shanghai().time())

    def _daily_value(self, day: date) -> int:
        ordinal = day.toordinal()
        wave = math.sin(ordinal / 11.0) * 90 + math.cos(ordinal / 5.0) * 40
        weekday_factor = 0.72 if day.weekday() >= 5 else 1.0
        return int((1180 + wave) * weekday_factor)

    def _split(self, total: int, shares: tuple[float, ...]) -> list[int]:
        values = [int(total * share) for share in shares]
        values[0] += total - sum(values)
        return values

    def overview(self) -> dict:
        total = self._daily_value(self._today)
        return {
            "reg_count": total,
            "ai_sessions": int(total * 0.46) + 18,
            "cancel_rate": round(0.038 + (self._today.toordinal() % 7) * 0.002, 4),
        }

    def trend(self, days: int) -> list[dict]:
        window = _bound(days, 1, MAX_TREND_DAYS)
        start = self._today - timedelta(days=window - 1)
        return [
            {
                "date": (start + timedelta(days=offset)).isoformat(),
                "value": self._daily_value(start + timedelta(days=offset)),
            }
            for offset in range(window)
        ]

    def hospital_ranking(self, limit: int) -> list[dict]:
        top = _bound(limit, 1, len(_HOSPITALS))
        values = self._split(
            self._daily_value(self._today), tuple(share for _, _, share in _HOSPITALS)
        )
        return [
            {"hospital_id": hospital_id, "name": name, "value": value}
            for (hospital_id, name, _), value in zip(_HOSPITALS, values)
        ][:top]

    def department_ranking(self, limit: int) -> list[dict]:
        top = _bound(limit, 1, len(_DEPARTMENTS))
        values = self._split(
            self._daily_value(self._today),
            tuple(share for _, _, share in _DEPARTMENTS),
        )
        return [
            {
                "hospital_id": 1,
                "department_id": department_id,
                "name": name,
                "value": value,
            }
            for (department_id, name, _), value in zip(_DEPARTMENTS, values)
        ][:top]

    def quality_health(self) -> dict:
        # Weighted quality score, as a ratio (the screen renders a percentage).
        score = 0.972 + (self._today.toordinal() % 5) * 0.004
        return {
            "quality_score": round(score, 4),
            "lag_seconds": 12,
            "open_alerts": 2,
        }

    def recent_alerts(self, limit: int) -> list[dict]:
        top = _bound(limit, 1, 2)
        now = self._now()
        rows = [
            {
                "rule_id": 2,
                "severity": "P0",
                "title": "挂号单号唯一性校验失败",
                "pass_rate": 0.9872,
                "checked_at": now - timedelta(minutes=18),
            },
            {
                "rule_id": 4,
                "severity": "P1",
                "title": "AI 会话消息内容非空校验失败",
                "pass_rate": 0.9931,
                "checked_at": now - timedelta(hours=2),
            },
        ]
        return rows[:top]
