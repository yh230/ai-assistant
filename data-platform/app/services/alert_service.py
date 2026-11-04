"""Alert rule CRUD, rule evaluation and alert-record persistence.

The service owns the conversion from ``dp_alert_rule`` rows to the pure
``etl.alerting.AlertRule`` value object, validates rule configuration before
it is persisted, and evaluates a supplied metric value:

1. compute the rule/dimension fingerprint;
2. ask ``etl.alerting.should_fire`` whether the value breaches the threshold,
   enough consecutive periods have been observed and the cooldown elapsed;
3. on fire, persist an ``OPEN`` ``dp_alert_record`` (Asia/Shanghai wall-clock
   ``created_at``, the metadata table stores naive timestamps);
4. optionally POST the notification to the configured webhook.

Webhook delivery happens after the record is committed.  A delivery failure is
logged and reported on the outcome but never rolls back the record and never
raises, matching design section 6.6 ("Webhook 失败只记录日志，不回滚告警记录").

V1 does not collect metric values from ClickHouse and does not persist the
per-fingerprint breach counter: the caller supplies ``value`` and
``breach_count``.  Wiring collectors and the scheduler is a deferred item.
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Callable

from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.core.exceptions import DataPlatformError
from app.models.metadata import DpAlertRecord, DpAlertRule
from etl.alerting import (
    CHANNELS,
    METRIC_TYPES,
    SEVERITIES,
    SUPPORTED_OPERATORS,
    AlertEvent,
    AlertRule,
    build_alert_event,
    build_webhook_payload,
    default_webhook_sender,
    fingerprint,
    local_now,
    should_fire,
    to_local_naive,
)

logger = logging.getLogger(__name__)

#: Injected webhook transport; receives ``(url, payload)``.
WebhookSender = Callable[[str, dict], None]

UPDATABLE_FIELDS = frozenset(
    {
        "name",
        "metric_type",
        "operator",
        "threshold",
        "window_minutes",
        "consecutive_periods",
        "severity",
        "channel",
        "cooldown_minutes",
        "enabled",
    }
)
# Every rule field is required: an explicit null must not blank a column.
REQUIRED_FIELDS = UPDATABLE_FIELDS

_NAME_MAX = 100
_METRIC_TYPE_MAX = 30
_SEVERITY_MAX = 10
_CHANNEL_MAX = 20

FIRED_REASON = "fired"
DISABLED_REASON = "rule is disabled"
CONDITION_REASON = "threshold or consecutive periods not satisfied"
COOLDOWN_REASON = "cooldown active for this fingerprint"


@dataclass(frozen=True)
class AlertEvaluation:
    """Outcome of one ``AlertService.evaluate_rule`` call."""

    rule_id: int
    fingerprint: str
    fired: bool
    reason: str
    record_id: int | None = None
    webhook_sent: bool = False
    webhook_error: str | None = None


def to_rule(row: DpAlertRule) -> AlertRule:
    """Convert the ORM row into the pure rule value object."""
    return AlertRule(
        id=row.id,
        name=row.name,
        metric_type=row.metric_type,
        operator=row.operator,
        threshold=float(row.threshold),
        window_minutes=int(row.window_minutes),
        consecutive_periods=int(row.consecutive_periods),
        severity=row.severity,
        cooldown_minutes=int(row.cooldown_minutes),
    )


def _required_text(label: str, value: Any, maximum: int) -> str:
    text = (value or "").strip() if isinstance(value, str) else ""
    if not text:
        raise ValueError(f"{label} is required")
    if len(text) > maximum:
        raise ValueError(f"{label} must be at most {maximum} characters")
    return text


def _integer(label: str, value: Any, *, minimum: int) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{label} must be an integer")
    try:
        number = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{label} must be an integer: {value}") from exc
    if number != value:
        raise ValueError(f"{label} must be an integer: {value}")
    if number < minimum:
        raise ValueError(f"{label} must be >= {minimum}")
    return number


def _validated_config(
    *,
    name: Any,
    metric_type: Any,
    operator: Any,
    threshold: Any,
    window_minutes: Any,
    consecutive_periods: Any,
    severity: Any,
    channel: Any,
    cooldown_minutes: Any,
) -> dict[str, Any]:
    """Validate and normalise one rule configuration.

    The service is the single validator (mirrors ``QualityService``), so the
    API reports a bad value as HTTP 400 instead of a Pydantic 422.
    """
    name = _required_text("name", name, _NAME_MAX)
    metric_type = _required_text("metric_type", metric_type, _METRIC_TYPE_MAX).upper()
    if metric_type not in METRIC_TYPES:
        expected = ", ".join(sorted(METRIC_TYPES))
        raise ValueError(f"unsupported metric_type: {metric_type}; expected one of {expected}")
    operator = _required_text("operator", operator, 5)
    if operator not in SUPPORTED_OPERATORS:
        expected = ", ".join(sorted(SUPPORTED_OPERATORS))
        raise ValueError(f"unsupported operator: {operator}; expected one of {expected}")
    try:
        threshold_value = float(threshold)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"threshold must be numeric: {threshold}") from exc
    if not math.isfinite(threshold_value):
        raise ValueError("threshold must be a finite number")
    severity = _required_text("severity", severity, _SEVERITY_MAX).upper()
    if severity not in SEVERITIES:
        expected = ", ".join(sorted(SEVERITIES))
        raise ValueError(f"unsupported severity: {severity}; expected one of {expected}")
    channel = _required_text("channel", channel, _CHANNEL_MAX).lower()
    if channel not in CHANNELS:
        expected = ", ".join(sorted(CHANNELS))
        raise ValueError(f"unsupported channel: {channel}; expected one of {expected}")
    return {
        "name": name,
        "metric_type": metric_type,
        "operator": operator,
        "threshold": threshold_value,
        "window_minutes": _integer("window_minutes", window_minutes, minimum=1),
        "consecutive_periods": _integer(
            "consecutive_periods", consecutive_periods, minimum=1
        ),
        "severity": severity,
        "channel": channel,
        "cooldown_minutes": _integer("cooldown_minutes", cooldown_minutes, minimum=0),
    }


class AlertService:
    def __init__(
        self,
        db: Session,
        sender: WebhookSender | None = None,
        webhook_url: str | None = None,
        now: Callable[[], datetime] | None = None,
    ):
        self.db = db
        self.sender = sender
        # ``None`` means "read ALERT_WEBHOOK_URL from settings"; an explicit
        # value (including "") lets callers and tests bypass the settings read.
        # Resolve once here: evaluations may run every polling period and must
        # not rebuild ``Settings`` (and re-read the .env file) each time.
        if webhook_url is None:
            webhook_url = get_settings().alert_webhook_url or ""
        self.webhook_url = webhook_url.strip()
        self._now = now or local_now

    # ------------------------------------------------------------------ rules

    def create_rule(
        self,
        *,
        name: str,
        metric_type: str,
        threshold: float,
        operator: str = ">",
        window_minutes: int = 5,
        consecutive_periods: int = 1,
        severity: str = "P1",
        channel: str = "webhook",
        cooldown_minutes: int = 30,
        enabled: bool = True,
    ) -> DpAlertRule:
        config = _validated_config(
            name=name,
            metric_type=metric_type,
            operator=operator,
            threshold=threshold,
            window_minutes=window_minutes,
            consecutive_periods=consecutive_periods,
            severity=severity,
            channel=channel,
            cooldown_minutes=cooldown_minutes,
        )
        entity = DpAlertRule(enabled=bool(enabled), **config)
        self.db.add(entity)
        self.db.commit()
        self.db.refresh(entity)
        return entity

    def update_rule(self, entity: DpAlertRule, **changes: Any) -> DpAlertRule:
        unknown = set(changes) - UPDATABLE_FIELDS
        if unknown:
            raise ValueError("unsupported alert rule fields: " + ", ".join(sorted(unknown)))
        for field in REQUIRED_FIELDS:
            if field in changes and changes[field] is None:
                raise ValueError(f"{field} cannot be null")
        config = _validated_config(
            name=changes.get("name", entity.name),
            metric_type=changes.get("metric_type", entity.metric_type),
            operator=changes.get("operator", entity.operator),
            threshold=changes.get("threshold", entity.threshold),
            window_minutes=changes.get("window_minutes", entity.window_minutes),
            consecutive_periods=changes.get(
                "consecutive_periods", entity.consecutive_periods
            ),
            severity=changes.get("severity", entity.severity),
            channel=changes.get("channel", entity.channel),
            cooldown_minutes=changes.get("cooldown_minutes", entity.cooldown_minutes),
        )
        for field, value in config.items():
            setattr(entity, field, value)
        if "enabled" in changes:
            entity.enabled = bool(changes["enabled"])
        self.db.commit()
        self.db.refresh(entity)
        return entity

    def delete_rule(self, entity: DpAlertRule) -> None:
        # Alert records are operational history and stay readable after the
        # rule is removed (no FK cascade exists).  Unlike quality results they
        # are still meaningful without their rule, so they are not deleted.
        self.db.delete(entity)
        self.db.commit()

    # ------------------------------------------------------------ evaluation

    def latest_record(self, record_fingerprint: str) -> DpAlertRecord | None:
        """Newest alert record for one fingerprint, regardless of status."""
        return (
            self.db.query(DpAlertRecord)
            .filter(DpAlertRecord.fingerprint == record_fingerprint)
            .order_by(DpAlertRecord.created_at.desc(), DpAlertRecord.id.desc())
            .first()
        )

    def last_fired_at(self, record_fingerprint: str) -> datetime | None:
        row = self.latest_record(record_fingerprint)
        if row is None or row.created_at is None:
            return None
        return to_local_naive(row.created_at)

    def evaluate_rule(
        self,
        rule: DpAlertRule,
        value: float,
        dimension: str = "global",
        breach_count: int = 1,
        now: datetime | None = None,
    ) -> AlertEvaluation:
        """Evaluate one observation for ``rule`` and persist a fired alert.

        Returns an ``AlertEvaluation`` describing the decision.  Configuration
        errors raise ``ValueError``; a failed record write raises
        ``DataPlatformError`` (500).  A webhook failure never raises.
        """
        if rule.id is None:
            raise ValueError("alert rule must be persisted before evaluation")
        moment = to_local_naive(now) if now is not None else to_local_naive(self._now())
        dimension = (dimension or "").strip() or "global"
        rule_value = to_rule(rule)
        record_fingerprint = fingerprint(rule_value.id, dimension)

        if not rule.enabled:
            return AlertEvaluation(
                rule_id=rule_value.id,
                fingerprint=record_fingerprint,
                fired=False,
                reason=DISABLED_REASON,
            )
        try:
            metric_value = float(value)
        except (TypeError, ValueError) as exc:
            raise ValueError(f"value must be numeric: {value}") from exc
        breaches = _integer("breach_count", breach_count, minimum=0)

        # First pass skips the cooldown so a value that does not breach the
        # threshold never needs the latest-record query.
        if not should_fire(rule_value, metric_value, breaches, None, moment):
            return AlertEvaluation(
                rule_id=rule_value.id,
                fingerprint=record_fingerprint,
                fired=False,
                reason=CONDITION_REASON,
            )
        last_fired = self.last_fired_at(record_fingerprint)
        if not should_fire(rule_value, metric_value, breaches, last_fired, moment):
            return AlertEvaluation(
                rule_id=rule_value.id,
                fingerprint=record_fingerprint,
                fired=False,
                reason=COOLDOWN_REASON,
            )

        event = build_alert_event(rule_value, metric_value, dimension, moment)
        record = DpAlertRecord(
            rule_id=rule_value.id,
            fingerprint=event.fingerprint,
            severity=event.severity,
            title=event.title[:200],
            content=event.content,
            status="OPEN",
            created_at=moment,
        )
        try:
            self.db.add(record)
            self.db.commit()
            self.db.refresh(record)
        except Exception as exc:
            self.db.rollback()
            raise DataPlatformError(
                500, f"failed to persist alert record: {exc}"
            ) from exc

        webhook_sent, webhook_error = self._notify(rule, event)
        return AlertEvaluation(
            rule_id=rule_value.id,
            fingerprint=record_fingerprint,
            fired=True,
            reason=FIRED_REASON,
            record_id=record.id,
            webhook_sent=webhook_sent,
            webhook_error=webhook_error,
        )

    # ------------------------------------------------------------- lifecycle

    def ack_alert(
        self, row: DpAlertRecord, now: datetime | None = None
    ) -> DpAlertRecord:
        """``OPEN -> ACKED``; a repeat ack is a no-op, a closed alert is an error."""
        if row.status == "CLOSED":
            raise ValueError("alert is already closed")
        if row.status == "ACKED":
            return row
        row.status = "ACKED"
        row.ack_at = to_local_naive(now) if now is not None else to_local_naive(self._now())
        self._commit_lifecycle(row)
        return row

    def close_alert(
        self, row: DpAlertRecord, now: datetime | None = None
    ) -> DpAlertRecord:
        """``OPEN|ACKED -> CLOSED``; closing a closed alert is a no-op."""
        if row.status == "CLOSED":
            return row
        row.status = "CLOSED"
        row.closed_at = (
            to_local_naive(now) if now is not None else to_local_naive(self._now())
        )
        self._commit_lifecycle(row)
        return row

    def _commit_lifecycle(self, row: DpAlertRecord) -> None:
        try:
            self.db.commit()
            self.db.refresh(row)
        except Exception as exc:
            self.db.rollback()
            raise DataPlatformError(
                500, f"failed to update alert {row.id}: {exc}"
            ) from exc

    # --------------------------------------------------------------- webhook

    def _effective_webhook_url(self) -> str:
        # Cached at construction; see ``__init__``.
        return self.webhook_url

    def _notify(self, rule: DpAlertRule, event: AlertEvent) -> tuple[bool, str | None]:
        """Send the webhook when configured; never raise.

        The alert record is already committed at this point, so a delivery
        problem is reported on the outcome and logged instead of failing the
        evaluation.
        """
        if (rule.channel or "").strip().lower() != "webhook":
            return False, None
        url = self._effective_webhook_url()
        if not url:
            logger.info(
                "alert rule %s fired but ALERT_WEBHOOK_URL is not configured", rule.id
            )
            return False, None
        sender = self.sender or default_webhook_sender
        try:
            sender(url, build_webhook_payload(event))
        except Exception as exc:
            message = f"webhook delivery failed: {exc}"
            logger.warning("alert rule %s: %s", rule.id, message)
            return False, message
        return True, None
