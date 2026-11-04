"""Dashboard (screen) aggregation service and ClickHouse repository.

The screen is a read-only view over the ADS tables.  Every aggregate is
computed at query time, so no extra write path is introduced.  The alert
center is a separate MySQL-backed API (``/api/alerts``); the screen "open
alerts" KPI and its alert card are derived from today's failed quality rules in
``ads_quality_daily`` as required by the task.

ClickHouse reads always use ``FINAL`` because the ADS tables are
``ReplacingMergeTree`` and may still contain unmerged duplicates.  Windows and
row limits are clamped by the repository, so a caller-supplied value cannot
turn a dashboard refresh into a full-table scan.
"""

import json
import math
import re
from typing import Any, Protocol

from app.schemas.screen import (
    KpiItem,
    ScreenOverview,
    ScreenQuality,
    ScreenRanking,
    TrendPoint,
)
from etl.ports.olap_store import OlapStore

# KPI order is part of the screen layout contract: six cards, left to right.
KPI_LABELS: tuple[tuple[str, str], ...] = (
    ("reg_count", "今日挂号量"),
    ("ai_sessions", "今日 AI 会话"),
    ("quality_score", "数据质量分"),
    ("lag_seconds", "链路延迟(秒)"),
    ("open_alerts", "质量异常告警"),
    ("cancel_rate", "今日取消率"),
)

DEFAULT_TREND_DAYS = 30
MAX_TREND_DAYS = 365
DEFAULT_RANKING_LIMIT = 10
MAX_RANKING_LIMIT = 100
DEFAULT_RECENT_ALERT_LIMIT = 10
MAX_RECENT_ALERT_LIMIT = 50
DEFAULT_ALERT_LOOKBACK_DAYS = 7
MAX_ALERT_LOOKBACK_DAYS = 90
DEFAULT_HEALTH_WINDOW_HOURS = 24
MAX_HEALTH_WINDOW_HOURS = 168

_DATABASE_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


class ScreenRepository(Protocol):
    def overview(self) -> dict[str, Any]: ...

    def trend(self, days: int) -> list[dict[str, Any]]: ...

    def hospital_ranking(self, limit: int) -> list[dict[str, Any]]: ...

    def department_ranking(self, limit: int) -> list[dict[str, Any]]: ...

    def quality_health(self) -> dict[str, Any]: ...

    def recent_alerts(self, limit: int) -> list[dict[str, Any]]: ...


def _number(value: Any) -> float | int:
    """Return a JSON-safe number, using 0 for missing or non-finite values."""
    if value is None:
        return 0
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else 0
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0
    return number if math.isfinite(number) else 0


def _bound(value: Any, minimum: int, maximum: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return minimum
    return max(minimum, min(number, maximum))


class ScreenService:
    def __init__(
        self, repository: ScreenRepository, alert_limit: int = DEFAULT_RECENT_ALERT_LIMIT
    ):
        self.repository = repository
        self.alert_limit = alert_limit

    def overview(self) -> ScreenOverview:
        raw = self.repository.overview() or {}
        health = self.repository.quality_health() or {}
        # ``overview`` stays the unmodified repository payload.  Quality and
        # pipeline KPIs come from the dedicated health query; they only fill
        # keys the overview query did not provide.
        merged = dict(raw)
        for key, value in health.items():
            if merged.get(key) is None:
                merged[key] = value
        kpis: list[KpiItem] = [
            {"key": key, "label": label, "value": _number(merged.get(key))}
            for key, label in KPI_LABELS
        ]
        return {"kpis": kpis, "raw": raw}

    def trend(self, days: int = DEFAULT_TREND_DAYS) -> list[TrendPoint]:
        return self.repository.trend(days)

    def ranking(self, limit: int = DEFAULT_RANKING_LIMIT) -> ScreenRanking:
        return {
            "hospitals": self.repository.hospital_ranking(limit),
            "departments": self.repository.department_ranking(limit),
        }

    def quality(self) -> ScreenQuality:
        health = self.repository.quality_health() or {}
        return {
            "health": {
                "quality_score": _number(health.get("quality_score")),
                "lag_seconds": _number(health.get("lag_seconds")),
                "open_alerts": _number(health.get("open_alerts")),
            },
            "alerts": self.repository.recent_alerts(self.alert_limit),
        }


def format_sse(data: dict[str, Any]) -> str:
    """Serialize one SSE frame; the payload is sent raw, without the envelope."""
    return f"data: {json.dumps(data, ensure_ascii=False, default=str)}\n\n"


class ClickHouseScreenRepository:
    """ScreenRepository backed by the ADS tables through ``OlapStore.query``."""

    def __init__(
        self,
        olap: OlapStore,
        database: str = "data_platform",
        *,
        max_trend_days: int = MAX_TREND_DAYS,
        max_ranking_limit: int = MAX_RANKING_LIMIT,
        max_alert_limit: int = MAX_RECENT_ALERT_LIMIT,
        health_window_hours: int = DEFAULT_HEALTH_WINDOW_HOURS,
        alert_lookback_days: int = DEFAULT_ALERT_LOOKBACK_DAYS,
    ):
        if not isinstance(database, str) or not _DATABASE_PATTERN.fullmatch(database):
            # The database name is interpolated into SQL, so it must be a plain
            # ClickHouse identifier rather than a caller-controlled fragment.
            raise ValueError(f"invalid ClickHouse database identifier: {database!r}")
        self.olap = olap
        self.database = database
        self.max_trend_days = _bound(max_trend_days, 1, MAX_TREND_DAYS)
        self.max_ranking_limit = _bound(max_ranking_limit, 1, MAX_RANKING_LIMIT)
        self.max_alert_limit = _bound(max_alert_limit, 1, MAX_RECENT_ALERT_LIMIT)
        self.health_window_hours = _bound(
            health_window_hours, 1, MAX_HEALTH_WINDOW_HOURS
        )
        self.alert_lookback_days = _bound(
            alert_lookback_days, 1, MAX_ALERT_LOOKBACK_DAYS
        )

    def overview(self) -> dict[str, Any]:
        rows = self.olap.query(
            "SELECT sum(reg_count) AS reg_count, "
            "sum(ai_sessions) AS ai_sessions, "
            "ifNull(sum(reg_count * cancel_rate) / nullIf(sum(reg_count), 0), 0) "
            "AS cancel_rate "
            f"FROM {self.database}.ads_hospital_daily FINAL "
            "WHERE metric_date = today()"
        )
        return rows[0] if rows else {}

    def trend(self, days: int) -> list[dict[str, Any]]:
        # The window includes today: days=30 covers today and the 29 days
        # before it.
        window = _bound(days, 1, self.max_trend_days)
        return self.olap.query(
            "SELECT metric_date AS date, sum(reg_count) AS value "
            f"FROM {self.database}.ads_hospital_daily FINAL "
            f"WHERE metric_date >= today() - INTERVAL {window - 1} DAY "
            "GROUP BY metric_date ORDER BY metric_date"
        )

    def hospital_ranking(self, limit: int) -> list[dict[str, Any]]:
        top = _bound(limit, 1, self.max_ranking_limit)
        # The dwd_hospital view already applies FINAL to ods_hospital.
        return self.olap.query(
            "SELECT r.hospital_id AS hospital_id, h.name AS name, r.value AS value "
            "FROM ("
            "SELECT hospital_id, sum(reg_count) AS value "
            f"FROM {self.database}.ads_hospital_daily FINAL "
            "WHERE metric_date = today() GROUP BY hospital_id"
            ") AS r "
            f"LEFT JOIN {self.database}.dwd_hospital AS h ON h.id = r.hospital_id "
            f"ORDER BY r.value DESC LIMIT {top}"
        )

    def department_ranking(self, limit: int) -> list[dict[str, Any]]:
        top = _bound(limit, 1, self.max_ranking_limit)
        # The dwd_department view already applies FINAL to ods_department.
        return self.olap.query(
            "SELECT r.hospital_id AS hospital_id, "
            "r.department_id AS department_id, d.name AS name, r.value AS value "
            "FROM ("
            "SELECT hospital_id, department_id, sum(reg_count) AS value "
            f"FROM {self.database}.ads_department_daily FINAL "
            "WHERE metric_date = today() GROUP BY hospital_id, department_id"
            ") AS r "
            f"LEFT JOIN {self.database}.dwd_department AS d ON d.id = r.department_id "
            f"ORDER BY r.value DESC LIMIT {top}"
        )

    def quality_health(self) -> dict[str, Any]:
        # Quality score follows the design weighting: P0=3, P1=2, P2=1.
        # lag_seconds is bounded to the most recent window so it stays a cheap
        # lookup on ads_pipeline_health.  open_alerts counts today's failed
        # quality rules; the alert lifecycle itself lives in the MySQL alert
        # center, not in ClickHouse.
        rows = self.olap.query(
            "SELECT "
            "ifNull(sum(pass_rate * multiIf(severity = 'P0', 3, "
            "severity = 'P1', 2, 1)) "
            "/ nullIf(sum(multiIf(severity = 'P0', 3, severity = 'P1', 2, 1)), 0), "
            "0) AS quality_score, "
            "(SELECT max(lag_seconds) "
            f"FROM {self.database}.ads_pipeline_health FINAL "
            f"WHERE metric_time >= now() - INTERVAL {self.health_window_hours} HOUR) "
            "AS lag_seconds, "
            "(SELECT count() "
            f"FROM {self.database}.ads_quality_daily FINAL "
            "WHERE metric_date = today() AND status = 'FAIL') AS open_alerts "
            f"FROM {self.database}.ads_quality_daily FINAL "
            "WHERE metric_date = today()"
        )
        if not rows:
            return {"quality_score": 0, "lag_seconds": 0, "open_alerts": 0}
        row = rows[0]
        return {
            "quality_score": _number(row.get("quality_score")),
            "lag_seconds": _number(row.get("lag_seconds")),
            "open_alerts": _number(row.get("open_alerts")),
        }

    def recent_alerts(self, limit: int) -> list[dict[str, Any]]:
        top = _bound(limit, 1, self.max_alert_limit)
        # Recent quality failures over a bounded lookback window; the alert
        # center API carries the full alert lifecycle.
        return self.olap.query(
            "SELECT rule_id, severity, rule_name AS title, pass_rate, checked_at "
            f"FROM {self.database}.ads_quality_daily FINAL "
            "WHERE status = 'FAIL' "
            f"AND metric_date >= today() - INTERVAL {self.alert_lookback_days - 1} DAY "
            f"ORDER BY checked_at DESC LIMIT {top}"
        )
