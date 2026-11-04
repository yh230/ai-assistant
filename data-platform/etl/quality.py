"""Data-quality rule SQL builders, evaluation and scoring.

The six standard rule types plus custom SQL follow design section 6.5.
ClickHouse cannot bind table or column names as parameters, so the builders
interpolate identifiers; every identifier, numeric parameter and enum literal
therefore passes a strict whitelist before it reaches the SQL.  Custom SQL is
the only free-form statement and must be a read-only ``SELECT``/``WITH`` query
returning ``total_rows`` and ``bad_rows`` columns.

The module depends on the standard library only (plus the ``OlapStore``
protocol), so rule generation and scoring can be unit tested without the
optional ClickHouse client or a live warehouse.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from etl.ports.olap_store import OlapStore

QUALITY_DAILY_TABLE = "ads_quality_daily"

RULE_TYPES = frozenset(
    {"NOT_NULL", "UNIQUE", "RANGE", "ENUM", "FRESHNESS", "VOLUME", "CUSTOM_SQL"}
)
SEVERITIES = frozenset({"P0", "P1", "P2"})
SEVERITY_WEIGHTS = {"P0": 3, "P1": 2, "P2": 1}
DEFAULT_WEIGHT = 1

# Metrics are bucketed by the platform time zone (design section 9:
# Asia/Shanghai).  Same convention as ``etl.consumer``: prefer the IANA
# database so the Python side matches ClickHouse's ``Asia/Shanghai`` rules,
# and fall back to a fixed UTC+8 offset on hosts without tzdata
# (Asia/Shanghai has no DST, so the fallback is exact).
PLATFORM_TIMEZONE = "Asia/Shanghai"
try:
    PLATFORM_TZ: timezone | ZoneInfo = ZoneInfo(PLATFORM_TIMEZONE)
except ZoneInfoNotFoundError:  # Windows without the tzdata package
    PLATFORM_TZ = timezone(timedelta(hours=8), PLATFORM_TIMEZONE)

# ClickHouse evaluates ``today()`` in the server time zone, which may differ
# from the platform time zone.  The VOLUME window therefore renders "today"
# explicitly in Asia/Shanghai so the SQL window and the evaluator's reference
# date cannot drift apart by a day.
VOLUME_TODAY_SQL = f"toDate(now(), '{PLATFORM_TIMEZONE}')"


def platform_today() -> date:
    """Today's date in the platform time zone (Asia/Shanghai)."""
    return datetime.now(PLATFORM_TZ).date()


# Rule types whose threshold is a pass-rate in [0, 1]; FRESHNESS thresholds are
# seconds and VOLUME thresholds are relative deviations, both non-negative.
_RATE_RULE_TYPES = frozenset({"NOT_NULL", "UNIQUE", "RANGE", "ENUM", "CUSTOM_SQL"})
_COLUMN_RULE_TYPES = frozenset({"NOT_NULL", "UNIQUE", "RANGE", "ENUM", "FRESHNESS"})

_IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_NUMBER = re.compile(r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)")
_CUSTOM_SQL_START = re.compile(r"\s*(?:select|with)\b", re.IGNORECASE | re.DOTALL)


@dataclass(frozen=True)
class QualityRule:
    id: int
    name: str
    table: str
    rule_type: str
    column: str | None
    params: dict[str, Any] = field(default_factory=dict)
    threshold: float = 1.0
    severity: str = "P1"


@dataclass(frozen=True)
class QualityResult:
    rule_id: int
    total_rows: int
    bad_rows: int
    pass_rate: float
    status: str
    severity: str


def _normalized_type(rule: QualityRule) -> str:
    rule_type = (rule.rule_type or "").strip().upper()
    if rule_type not in RULE_TYPES:
        raise ValueError(f"unknown rule type: {rule.rule_type}")
    return rule_type


def _identifier(value: Any, label: str) -> str:
    """Return a whitelisted SQL identifier or raise.

    Dot-qualified names (``database.table``) are allowed so a rule can target
    an explicitly qualified table; every segment must match ``_IDENT``.
    """
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label} must be a non-empty identifier")
    if not all(_IDENT.fullmatch(part) for part in value.split(".")):
        raise ValueError(f"invalid {label}: {value}")
    return value


def _number_text(value: Any, label: str) -> str:
    """Render a numeric parameter without allowing SQL fragments."""
    if isinstance(value, bool):
        raise ValueError(f"{label} must be numeric")
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError(f"{label} must be finite")
        return repr(value)
    if isinstance(value, str):
        text = value.strip()
        if _NUMBER.fullmatch(text):
            return text
    raise ValueError(f"{label} must be numeric")


def _quote_text(value: str) -> str:
    """Quote a string literal, escaping ClickHouse backslash and quote rules."""
    escaped = value.replace("\\", "\\\\").replace("'", "\\'")
    return f"'{escaped}'"


def _enum_literal(value: Any) -> str:
    if isinstance(value, bool):
        raise ValueError("ENUM params.values must contain numbers or strings")
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("ENUM params.values must be finite")
        return repr(value)
    if isinstance(value, str):
        return _quote_text(value)
    raise ValueError("ENUM params.values must contain numbers or strings")


def _validated_threshold(rule_type: str, threshold: Any) -> float:
    try:
        value = float(threshold)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"threshold must be numeric: {threshold}") from exc
    if not math.isfinite(value) or value < 0:
        raise ValueError("threshold must be a finite non-negative number")
    if rule_type in _RATE_RULE_TYPES and value > 1:
        raise ValueError(f"{rule_type} threshold must be within [0, 1]")
    return value


def validate_rule(rule: QualityRule) -> None:
    """Validate a rule before SQL generation or persistence.

    Raises ``ValueError`` with a caller-facing message; the API maps it to
    HTTP 400.  ``CUSTOM_SQL`` rules ignore ``table``/``column`` because the
    statement itself defines the scope.
    """
    rule_type = _normalized_type(rule)
    if rule.severity not in SEVERITIES:
        raise ValueError(f"unsupported severity: {rule.severity}")
    _validated_threshold(rule_type, rule.threshold)
    if not isinstance(rule.params, dict):
        raise ValueError("params must be an object")

    if rule_type == "CUSTOM_SQL":
        sql = rule.params.get("sql")
        if not isinstance(sql, str) or not sql.strip():
            raise ValueError("CUSTOM_SQL rule requires params.sql")
        if not _CUSTOM_SQL_START.match(sql):
            raise ValueError("CUSTOM_SQL rule must be a read-only SELECT/WITH statement")
        # The HTTP interface may execute multiple statements depending on
        # client settings; reject them here so custom SQL stays read-only.
        body = sql.strip()
        if body.endswith(";"):
            body = body[:-1].rstrip()
        if ";" in body:
            raise ValueError("CUSTOM_SQL rule must be a single statement")
        return

    _identifier(rule.table, "table")
    if rule_type in _COLUMN_RULE_TYPES:
        if not rule.column:
            raise ValueError(f"{rule_type} rule requires a column")
        _identifier(rule.column, "column")

    if rule_type == "RANGE":
        minimum = _number_text(rule.params.get("min"), "params.min")
        maximum = _number_text(rule.params.get("max"), "params.max")
        if float(minimum) > float(maximum):
            raise ValueError("params.min must not exceed params.max")
    elif rule_type == "ENUM":
        values = rule.params.get("values")
        if not isinstance(values, (list, tuple)) or not values:
            raise ValueError("ENUM rule requires a non-empty params.values list")
        for value in values:
            _enum_literal(value)
    elif rule_type == "VOLUME":
        _identifier(rule.params.get("date_column", "created_at"), "params.date_column")


def build_rule_sql(rule: QualityRule) -> str:
    validate_rule(rule)
    rule_type = _normalized_type(rule)
    table = rule.table
    column = rule.column or ""
    # ReplacingMergeTree tables can hold several versions of a key until parts
    # merge; every standard rule reads FINAL so a duplicate version cannot
    # inflate total_rows or hide a bad value.
    source = f"{table} FINAL"

    if rule_type == "NOT_NULL":
        return (
            f"SELECT count() AS total_rows, countIf({column} IS NULL) AS bad_rows "
            f"FROM {source}"
        )
    if rule_type == "UNIQUE":
        return (
            f"SELECT count() AS total_rows, count() - uniqExact({column}) AS bad_rows "
            f"FROM {source}"
        )
    if rule_type == "RANGE":
        minimum = _number_text(rule.params["min"], "params.min")
        maximum = _number_text(rule.params["max"], "params.max")
        return (
            f"SELECT count() AS total_rows, "
            f"countIf({column} < {minimum} OR {column} > {maximum}) AS bad_rows "
            f"FROM {source}"
        )
    if rule_type == "ENUM":
        values = ", ".join(_enum_literal(value) for value in rule.params["values"])
        return (
            f"SELECT count() AS total_rows, countIf({column} NOT IN ({values})) AS bad_rows "
            f"FROM {source}"
        )
    if rule_type == "FRESHNESS":
        return (
            f"SELECT count() AS total_rows, "
            f"dateDiff('second', max({column}), now('{PLATFORM_TIMEZONE}')) AS lag_seconds "
            f"FROM {source}"
        )
    if rule_type == "VOLUME":
        date_column = rule.params.get("date_column", "created_at")
        return (
            f"SELECT toDate({date_column}, '{PLATFORM_TIMEZONE}') AS metric_date, "
            f"count() AS total_rows "
            f"FROM {source} WHERE {date_column} >= {VOLUME_TODAY_SQL} - 7 "
            f"GROUP BY metric_date ORDER BY metric_date"
        )
    # CUSTOM_SQL is validated as a read-only SELECT/WITH above.
    return str(rule.params["sql"])


def _as_date(value: Any) -> date | None:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    if isinstance(value, str):
        try:
            return date.fromisoformat(value[:10])
        except ValueError:
            return None
    return None


def _volume_window(rows: list[dict[str, Any]]) -> tuple[int, list[int], bool]:
    """Return (today's count, preceding seven counts, today is present).

    The query filters an 8-day window, but days without rows are absent from
    the result.  Rebuilding the calendar from the returned dates keeps missing
    days at zero, so a pipeline that produced nothing today cannot borrow
    yesterday's row as "today".  If the newest row is in the future (clock
    skew), the newest date becomes the reference day.  The presence flag is
    False when the result has no rows at all or the reference day is missing:
    VOLUME cannot pass without a row for the day it is measuring.
    """
    counts: dict[date, int] = {}
    for row in rows:
        key = _as_date(row.get("metric_date"))
        if key is None:
            continue
        try:
            counts[key] = int(row.get("total_rows") or 0)
        except (TypeError, ValueError) as exc:
            raise ValueError("VOLUME rows must expose an integer total_rows") from exc
    if not counts:
        return 0, [], False
    latest = max(counts)
    today = platform_today()
    # ``reference_date`` is the platform ("Asia/Shanghai") today, or the
    # newest data day when the warehouse clock runs ahead.
    reference_date = latest if latest > today else today
    window = [
        counts.get(reference_date - timedelta(days=offset), 0)
        for offset in range(7, -1, -1)
    ]
    return window[-1], window[:7], reference_date in counts


def evaluate_rule(rule: QualityRule, olap: OlapStore) -> QualityResult:
    rule_type = _normalized_type(rule)
    rows = olap.query(build_rule_sql(rule))

    if rule_type == "FRESHNESS":
        raw_lag = rows[0].get("lag_seconds") if rows else None
        if raw_lag is None:
            # max(column) is NULL for an empty table: freshness is unknown, so
            # fail instead of reporting a false pass.
            return QualityResult(rule.id, 1, 1, 0.0, "FAIL", rule.severity)
        lag_seconds = max(int(raw_lag), 0)
        passed = lag_seconds <= rule.threshold
        return QualityResult(
            rule.id,
            1,
            0 if passed else 1,
            1.0 if passed else 0.0,
            "PASS" if passed else "FAIL",
            rule.severity,
        )

    if rule_type == "VOLUME":
        today_count, baseline_counts, today_present = _volume_window(rows)
        if not today_present:
            # No rows at all, or no row for the reference day: the pipeline
            # produced nothing today, which is a volume failure regardless of
            # how permissive the threshold is.
            return QualityResult(rule.id, 0, 1, 0.0, "FAIL", rule.severity)
        baseline = (
            sum(baseline_counts) / len(baseline_counts)
            if baseline_counts
            else float(today_count)
        )
        if baseline == 0:
            # 0 -> 0 is no change; 0 -> anything else is an unbounded deviation.
            deviation = 0.0 if today_count == 0 else math.inf
        else:
            deviation = abs(today_count - baseline) / baseline
        passed = deviation <= rule.threshold
        return QualityResult(
            rule.id,
            today_count,
            0 if passed else 1,
            1.0 if passed else 0.0,
            "PASS" if passed else "FAIL",
            rule.severity,
        )

    if not rows:
        if rule_type == "CUSTOM_SQL":
            raise ValueError(
                "CUSTOM_SQL must return a row with total_rows and bad_rows columns"
            )
        total_rows = bad_rows = 0
    else:
        row = rows[0]
        if "total_rows" not in row or "bad_rows" not in row:
            raise ValueError(
                f"{rule_type} quality SQL must return total_rows and bad_rows columns"
            )
        total_rows = int(row["total_rows"] or 0)
        bad_rows = int(row["bad_rows"] or 0)

    pass_rate = (
        1.0
        if total_rows == 0
        else max(0.0, min(1.0, (total_rows - bad_rows) / total_rows))
    )
    status = "PASS" if pass_rate >= rule.threshold else "FAIL"
    return QualityResult(
        rule.id, total_rows, bad_rows, pass_rate, status, rule.severity
    )


def weighted_score(results: list[QualityResult]) -> float:
    """Severity-weighted pass rate: P0=3, P1=2, P2=1; no results score 1.0."""
    if not results:
        return 1.0
    total_weight = 0
    weighted = 0.0
    for result in results:
        weight = SEVERITY_WEIGHTS.get(result.severity, DEFAULT_WEIGHT)
        total_weight += weight
        weighted += result.pass_rate * weight
    if total_weight == 0:
        return 1.0
    return weighted / total_weight


def build_quality_row(
    rule: QualityRule, result: QualityResult, checked_at: datetime
) -> dict[str, Any]:
    """Build the ``ads_quality_daily`` row for one rule run.

    Metadata timestamps follow the Asia/Shanghai wall-clock convention, so a
    naive ``checked_at`` is treated as already platform-local (``app.core.time``
    supplies such values); an aware value is converted to the platform time
    zone and its tzinfo dropped.  Both ``metric_date`` and ``checked_at`` are
    therefore written in the same calendar as the ClickHouse column types.
    """
    if checked_at.tzinfo is None:
        local = checked_at
    else:
        local = checked_at.astimezone(PLATFORM_TZ).replace(tzinfo=None)
    return {
        "metric_date": local.date(),
        "rule_id": rule.id,
        "rule_name": rule.name,
        "total_rows": result.total_rows,
        "bad_rows": result.bad_rows,
        "pass_rate": result.pass_rate,
        "severity": result.severity or rule.severity,
        "status": result.status,
        "checked_at": local,
    }
