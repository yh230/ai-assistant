from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.api.deps import get_session, require_staff
from app.core.response import ok
from app.models.metadata import DpAlertRecord, DpAlertRule
from app.schemas.alert import AlertRuleCreate, AlertRuleUpdate
from app.services.alert_service import AlertService
from etl.alerting import ALERT_STATUSES, SEVERITIES

router = APIRouter(
    prefix="/alerts",
    tags=["alerts"],
    dependencies=[Depends(require_staff)],
)


def _iso(value: Any) -> str | None:
    return value.isoformat() if value is not None else None


def _get_rule(db: Session, rule_id: int) -> DpAlertRule:
    rule = db.get(DpAlertRule, rule_id)
    if rule is None:
        raise HTTPException(status_code=404, detail="alert rule not found")
    return rule


def _get_alert(db: Session, alert_id: int) -> DpAlertRecord:
    alert = db.get(DpAlertRecord, alert_id)
    if alert is None:
        raise HTTPException(status_code=404, detail="alert not found")
    return alert


def _rule_view(row: DpAlertRule) -> dict[str, Any]:
    return {
        "id": row.id,
        "name": row.name,
        "metric_type": row.metric_type,
        "operator": row.operator,
        "threshold": row.threshold,
        "window_minutes": row.window_minutes,
        "consecutive_periods": row.consecutive_periods,
        "severity": row.severity,
        "channel": row.channel,
        "cooldown_minutes": row.cooldown_minutes,
        "enabled": bool(row.enabled),
    }


def _alert_view(row: DpAlertRecord) -> dict[str, Any]:
    return {
        "id": row.id,
        "rule_id": row.rule_id,
        "fingerprint": row.fingerprint,
        "severity": row.severity,
        "title": row.title,
        "content": row.content,
        "status": row.status,
        "created_at": _iso(row.created_at),
        "ack_at": _iso(row.ack_at),
        "closed_at": _iso(row.closed_at),
    }


@router.get("/rules")
def list_alert_rules(db: Session = Depends(get_session)):
    rows = db.query(DpAlertRule).order_by(DpAlertRule.id.desc()).all()
    return ok([_rule_view(row) for row in rows])


@router.post("/rules")
def create_alert_rule(payload: AlertRuleCreate, db: Session = Depends(get_session)):
    try:
        rule = AlertService(db).create_rule(**payload.model_dump())
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ok(_rule_view(rule))


@router.put("/rules/{rule_id}")
def update_alert_rule(
    rule_id: int,
    payload: AlertRuleUpdate,
    db: Session = Depends(get_session),
):
    rule = _get_rule(db, rule_id)
    changes = payload.model_dump(exclude_unset=True)
    try:
        rule = AlertService(db).update_rule(rule, **changes)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ok(_rule_view(rule))


@router.delete("/rules/{rule_id}")
def delete_alert_rule(rule_id: int, db: Session = Depends(get_session)):
    rule = _get_rule(db, rule_id)
    AlertService(db).delete_rule(rule)
    return ok({"deleted": rule_id})


@router.get("")
def list_alerts(
    status: str | None = None,
    severity: str | None = None,
    rule_id: int | None = None,
    limit: int = Query(default=100, ge=1, le=1000),
    db: Session = Depends(get_session),
):
    query = db.query(DpAlertRecord)
    if status is not None:
        normalized = status.strip().upper()
        if normalized not in ALERT_STATUSES:
            raise HTTPException(status_code=400, detail=f"unsupported alert status: {status}")
        query = query.filter(DpAlertRecord.status == normalized)
    if severity is not None:
        normalized_severity = severity.strip().upper()
        if normalized_severity not in SEVERITIES:
            raise HTTPException(
                status_code=400, detail=f"unsupported alert severity: {severity}"
            )
        query = query.filter(DpAlertRecord.severity == normalized_severity)
    if rule_id is not None:
        query = query.filter(DpAlertRecord.rule_id == rule_id)
    rows = query.order_by(DpAlertRecord.id.desc()).limit(limit).all()
    return ok([_alert_view(row) for row in rows])


@router.post("/{alert_id}/ack")
def ack_alert(alert_id: int, db: Session = Depends(get_session)):
    row = _get_alert(db, alert_id)
    try:
        row = AlertService(db).ack_alert(row)
    except ValueError as exc:
        # Only a CLOSED alert raises here; acknowledge is otherwise idempotent.
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return ok(_alert_view(row))


@router.post("/{alert_id}/close")
def close_alert(alert_id: int, db: Session = Depends(get_session)):
    row = _get_alert(db, alert_id)
    row = AlertService(db).close_alert(row)
    return ok(_alert_view(row))
