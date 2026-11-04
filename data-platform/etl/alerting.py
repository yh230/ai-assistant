"""Alert decision logic, notification payloads and the optional webhook adapter.

Design section 6.6 describes the alert loop: a rule compares a metric value
with a threshold, requires a number of consecutive breaching periods and
suppresses duplicate notifications for the same rule/dimension fingerprint
inside a cooldown window.  The alert lifecycle is ``OPEN -> ACKED -> CLOSED``.

Everything that decides whether an alert fires is pure: no database, no
clock, no network.  Callers pass the observed value, the consecutive breach
count and the current time, which keeps the engine deterministic and unit
testable.  ``default_webhook_sender`` is the single I/O helper: it imports
``httpx`` lazily so the dependency stays optional, and ``AlertService``
accepts an injected sender so tests never touch the network.

Time convention: metadata tables store timestamps without a time zone, and the
platform convention (design section 9) is Asia/Shanghai wall-clock time.  The
helpers here normalise aware datetimes to that zone and drop the tzinfo so the
cooldown comparison cannot mix UTC and local values.
"""

from __future__ import annotations

import hashlib
import operator
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Callable
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

# Same convention as ``etl.quality`` / ``etl.consumer``: prefer the IANA
# database and fall back to a fixed UTC+8 offset on hosts without tzdata
# (Asia/Shanghai has no DST, so the fallback is exact).
PLATFORM_TIMEZONE = "Asia/Shanghai"
try:
    PLATFORM_TZ: timezone | ZoneInfo = ZoneInfo(PLATFORM_TIMEZONE)
except ZoneInfoNotFoundError:  # Windows without the tzdata package
    PLATFORM_TZ = timezone(timedelta(hours=8), PLATFORM_TIMEZONE)

WEBHOOK_TIMEOUT_SECONDS = 5

# Alert metrics from design section 6.6.
METRIC_TYPES = frozenset(
    {
        "PIPELINE_LAG",
        "DATA_FRESHNESS",
        "QUALITY_SCORE",
        "VOLUME_ANOMALY",
        "KAFKA_LAG",
    }
)
SEVERITIES = frozenset({"P0", "P1", "P2"})
# ``inapp`` keeps the record in the alert center; ``webhook`` additionally
# pushes it to the configured generic webhook.
CHANNELS = frozenset({"webhook", "inapp"})
ALERT_STATUSES = frozenset({"OPEN", "ACKED", "CLOSED"})

_OPERATORS: dict[str, Callable[[float, float], bool]] = {
    ">": operator.gt,
    ">=": operator.ge,
    "<": operator.lt,
    "<=": operator.le,
    "==": operator.eq,
}
SUPPORTED_OPERATORS = frozenset(_OPERATORS)


@dataclass(frozen=True)
class AlertRule:
    """Immutable rule value object used by the pure decision functions.

    ``window_minutes`` is the aggregation window the caller collected the
    metric over; it does not influence ``should_fire`` and is carried so the
    notification text and future schedulers can describe the rule.
    """

    id: int
    name: str
    metric_type: str
    operator: str
    threshold: float
    window_minutes: int
    consecutive_periods: int
    severity: str
    cooldown_minutes: int


@dataclass(frozen=True)
class AlertEvent:
    """Notification payload content for one fired alert."""

    rule_id: int
    fingerprint: str
    severity: str
    title: str
    content: str


def to_local_naive(value: datetime) -> datetime:
    """Return the platform-local (Asia/Shanghai) naive wall-clock time.

    Naive input is assumed to already be platform-local -- that is how the
    metadata tables store these columns.  Aware input is converted first so a
    UTC timestamp never gets compared against a local one.
    """
    if value.tzinfo is None:
        return value
    return value.astimezone(PLATFORM_TZ).replace(tzinfo=None)


def local_now() -> datetime:
    """Current Asia/Shanghai wall-clock time without tzinfo."""
    return datetime.now(PLATFORM_TZ).replace(tzinfo=None)


def fingerprint(rule_id: int, dimension: str) -> str:
    """Stable rule+dimension identity used for cooldown de-duplication."""
    return hashlib.sha256(f"{rule_id}:{dimension}".encode("utf-8")).hexdigest()[:16]


def should_fire(
    rule: AlertRule,
    value: float,
    breach_count: int,
    last_fired_at: datetime | None,
    now: datetime,
) -> bool:
    """Decide whether an alert should fire for one observation.

    The value must breach the threshold, the caller must have observed at
    least ``consecutive_periods`` consecutive breaches, and either no alert
    was fired before or the cooldown has fully elapsed.  Aware and naive
    timestamps are normalised to Asia/Shanghai before subtraction.
    """
    compare = _OPERATORS.get(rule.operator)
    if compare is None:
        raise ValueError(f"unknown operator: {rule.operator}")
    if not compare(value, rule.threshold):
        return False
    if breach_count < rule.consecutive_periods:
        return False
    if last_fired_at is not None:
        elapsed = to_local_naive(now) - to_local_naive(last_fired_at)
        if elapsed < timedelta(minutes=rule.cooldown_minutes):
            return False
    return True


def build_webhook_payload(event: AlertEvent) -> dict:
    """DingTalk/WeCom-compatible text payload (design section 6.6)."""
    return {
        "msgtype": "text",
        "text": {
            "content": (
                f"[{event.severity}] {event.title}\n{event.content}"
                f"\nfingerprint={event.fingerprint}"
            )
        },
    }


def _format_number(value: float) -> str:
    # ``:g`` renders whole numbers as "600" instead of "600.0" and keeps
    # float artefacts out of the notification text.
    return f"{float(value):g}"


def build_alert_event(
    rule: AlertRule,
    value: float,
    dimension: str,
    now: datetime | None = None,
) -> AlertEvent:
    """Build the notification event for a fired rule."""
    dimension = (dimension or "").strip() or "global"
    parts = [
        f"metric={rule.metric_type}",
        f"value={_format_number(value)}",
        f"condition={rule.operator} {_format_number(rule.threshold)}",
        f"dimension={dimension}",
        f"severity={rule.severity}",
    ]
    if now is not None:
        parts.append(f"at={to_local_naive(now):%Y-%m-%d %H:%M:%S}")
    return AlertEvent(
        rule_id=rule.id,
        fingerprint=fingerprint(rule.id, dimension),
        severity=rule.severity,
        title=f"{rule.name} ({rule.metric_type})",
        content=" ".join(parts),
    )


def default_webhook_sender(url: str, payload: dict) -> None:
    """POST ``payload`` as JSON to ``url`` using ``httpx``.

    ``httpx`` is an optional runtime dependency, so the import is lazy: the
    alert engine keeps working (record persisted, failure logged) when the
    client is not installed.  ``raise_for_status`` turns a rejected payload
    into an exception so a 4xx/5xx is not mistaken for a delivered alert.
    No retry in V1.
    """
    import httpx

    response = httpx.post(url, json=payload, timeout=WEBHOOK_TIMEOUT_SECONDS)
    response.raise_for_status()
