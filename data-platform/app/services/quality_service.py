"""Quality rule CRUD and execution.

The service owns the conversion from the ``dp_quality_rule`` row to the pure
``etl.quality.QualityRule`` value object, validates configuration before it is
persisted, and records every run twice: ``dp_quality_result`` in the metadata
database (always) and one ``ads_quality_daily`` row in the OLAP store (when
available).  A warehouse write failure never hides the metadata result; the
caller receives an outcome carrying the error so the API can return a clear
failure envelope.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Callable

from sqlalchemy.orm import Session

from app.core.exceptions import DataPlatformError
from app.models.metadata import DpQualityResult, DpQualityRule
from etl.ports.olap_store import OlapStore
from etl.quality import (
    QUALITY_DAILY_TABLE,
    QualityResult,
    QualityRule,
    build_quality_row,
    evaluate_rule,
    validate_rule,
)

logger = logging.getLogger(__name__)

UPDATABLE_FIELDS = frozenset(
    {
        "name",
        "target_table",
        "rule_type",
        "column_name",
        "params_json",
        "threshold",
        "severity",
        "cron",
        "enabled",
    }
)
# ``column_name`` is the only nullable rule field; an explicit null anywhere
# else must not blank a required column.
REQUIRED_FIELDS = UPDATABLE_FIELDS - {"column_name"}


@dataclass(frozen=True)
class QualityRunOutcome:
    """Result of one rule execution, including the warehouse write status."""

    result: QualityResult
    result_id: int
    checked_at: datetime
    olap_written: bool
    olap_error: str | None = None


def to_rule(row: DpQualityRule) -> QualityRule:
    """Convert the ORM row into the pure rule value object."""
    params = row.params_json if isinstance(row.params_json, dict) else {}
    return QualityRule(
        id=row.id,
        name=row.name,
        table=row.target_table,
        rule_type=row.rule_type,
        column=row.column_name,
        params=dict(params),
        threshold=float(row.threshold),
        severity=row.severity,
    )


def _validated_config(
    *,
    name: str,
    target_table: str,
    rule_type: str,
    column_name: str | None,
    params: dict[str, Any] | None,
    threshold: Any,
    severity: str,
) -> dict[str, Any]:
    name = (name or "").strip()
    if not name:
        raise ValueError("name is required")
    target_table = (target_table or "").strip()
    if not target_table:
        raise ValueError("target_table is required")
    rule_type = (rule_type or "").strip().upper()
    severity = (severity or "").strip().upper()
    if isinstance(column_name, str):
        column_name = column_name.strip() or None
    if params is None:
        params = {}
    if not isinstance(params, dict):
        raise ValueError("params must be an object")
    try:
        threshold_value = float(threshold)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"threshold must be numeric: {threshold}") from exc
    # Reuse the SQL builder's validation so stored rules and rendered rules
    # cannot diverge (identifiers, enum/range params, severity, threshold).
    validate_rule(
        QualityRule(
            id=0,
            name=name,
            table=target_table,
            rule_type=rule_type,
            column=column_name,
            params=dict(params),
            threshold=threshold_value,
            severity=severity,
        )
    )
    return {
        "name": name,
        "target_table": target_table,
        "rule_type": rule_type,
        "column_name": column_name,
        "params_json": dict(params),
        "threshold": threshold_value,
        "severity": severity,
    }


class QualityService:
    def __init__(
        self,
        db: Session,
        olap: OlapStore | None = None,
        now: Callable[[], datetime] | None = None,
    ):
        self.db = db
        self.olap = olap
        self._now = now or datetime.utcnow

    def create_rule(
        self,
        name: str,
        target_table: str,
        rule_type: str,
        column_name: str | None = None,
        params: dict[str, Any] | None = None,
        threshold: float = 1.0,
        severity: str = "P1",
        cron: str = "0 * * * *",
        enabled: bool = True,
    ) -> DpQualityRule:
        config = _validated_config(
            name=name,
            target_table=target_table,
            rule_type=rule_type,
            column_name=column_name,
            params=params,
            threshold=threshold,
            severity=severity,
        )
        cron = (cron or "").strip()
        if not cron:
            raise ValueError("cron is required")
        entity = DpQualityRule(cron=cron, enabled=bool(enabled), **config)
        self.db.add(entity)
        self.db.commit()
        self.db.refresh(entity)
        return entity

    def update_rule(self, entity: DpQualityRule, **changes: Any) -> DpQualityRule:
        unknown = set(changes) - UPDATABLE_FIELDS
        if unknown:
            raise ValueError(
                "unsupported quality rule fields: " + ", ".join(sorted(unknown))
            )
        for name in REQUIRED_FIELDS:
            if name in changes and changes[name] is None:
                raise ValueError(f"{name} cannot be null")
        new_cron = None
        if "cron" in changes:
            new_cron = (changes["cron"] or "").strip()
            if not new_cron:
                raise ValueError("cron is required")
        config = _validated_config(
            name=changes.get("name", entity.name),
            target_table=changes.get("target_table", entity.target_table),
            rule_type=changes.get("rule_type", entity.rule_type),
            column_name=changes.get("column_name", entity.column_name),
            params=changes.get("params_json", entity.params_json),
            threshold=changes.get("threshold", entity.threshold),
            severity=changes.get("severity", entity.severity),
        )
        for field, value in config.items():
            setattr(entity, field, value)
        if new_cron is not None:
            entity.cron = new_cron
        if "enabled" in changes:
            entity.enabled = bool(changes["enabled"])
        self.db.commit()
        self.db.refresh(entity)
        return entity

    def delete_rule(self, entity: DpQualityRule) -> None:
        # Results are meaningless without their rule and there is no FK
        # cascade; history is removed with the rule (mirrors SyncService).
        self.db.query(DpQualityResult).filter(
            DpQualityResult.rule_id == entity.id
        ).delete(synchronize_session="fetch")
        self.db.delete(entity)
        self.db.commit()

    def run_rule(self, entity: DpQualityRule) -> QualityRunOutcome:
        """Evaluate one rule, persist the result and append the ADS row.

        Evaluation failures raise ``DataPlatformError`` (502) because there is
        no result to persist.  A warehouse append failure after evaluation is
        reported through ``olap_written``/``olap_error`` while the MySQL result
        stays committed, so the caller can return an error envelope instead of
        silently reporting success.
        """
        if self.olap is None:
            raise DataPlatformError(503, "quality olap store is not available")
        try:
            rule = to_rule(entity)
            result = evaluate_rule(rule, self.olap)
        except Exception as exc:
            raise DataPlatformError(
                502, f"quality rule {entity.id} evaluation failed: {exc}"
            ) from exc

        checked_at = self._now()
        record = DpQualityResult(
            rule_id=rule.id,
            total_rows=result.total_rows,
            bad_rows=result.bad_rows,
            pass_rate=result.pass_rate,
            status=result.status,
            checked_at=checked_at,
        )
        try:
            self.db.add(record)
            self.db.commit()
            self.db.refresh(record)
        except Exception as exc:
            self.db.rollback()
            raise DataPlatformError(
                500, f"failed to persist quality result: {exc}"
            ) from exc

        olap_error = None
        try:
            self.olap.insert_json(
                QUALITY_DAILY_TABLE, [build_quality_row(rule, result, checked_at)]
            )
            olap_written = True
        except Exception as exc:
            # Adapter failures (missing driver, unavailable server, rejected
            # insert) must surface; the metadata result above is already durable.
            olap_written = False
            olap_error = (
                f"quality result {record.id} saved but {QUALITY_DAILY_TABLE} "
                f"write failed: {exc}"
            )
            logger.warning("quality rule %s: %s", entity.id, olap_error)

        return QualityRunOutcome(
            result=result,
            result_id=record.id,
            checked_at=checked_at,
            olap_written=olap_written,
            olap_error=olap_error,
        )
