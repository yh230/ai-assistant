from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session

from app.api.deps import get_session, require_staff
from app.core.config import get_settings
from app.core.response import fail, ok
from app.models.metadata import DpQualityResult, DpQualityRule
from app.schemas.quality import QualityRuleCreate, QualityRuleUpdate
from app.services.quality_service import QualityRunOutcome, QualityService
from etl.adapters.factory import build_olap_store

router = APIRouter(
    prefix="/quality",
    tags=["quality"],
    dependencies=[Depends(require_staff)],
)


def _iso(value: Any) -> str | None:
    return value.isoformat() if value is not None else None


def _get_rule(db: Session, rule_id: int) -> DpQualityRule:
    rule = db.get(DpQualityRule, rule_id)
    if rule is None:
        raise HTTPException(status_code=404, detail="quality rule not found")
    return rule


def _rule_view(row: DpQualityRule) -> dict[str, Any]:
    return {
        "id": row.id,
        "name": row.name,
        "target_table": row.target_table,
        "rule_type": row.rule_type,
        "column_name": row.column_name,
        "params": dict(row.params_json or {}),
        "threshold": row.threshold,
        "severity": row.severity,
        "cron": row.cron,
        "enabled": bool(row.enabled),
        "create_time": _iso(row.create_time),
    }


def _result_view(row: DpQualityResult) -> dict[str, Any]:
    return {
        "id": row.id,
        "rule_id": row.rule_id,
        "total_rows": row.total_rows,
        "bad_rows": row.bad_rows,
        "pass_rate": row.pass_rate,
        "status": row.status,
        "checked_at": _iso(row.checked_at),
    }


def _run_view(outcome: QualityRunOutcome) -> dict[str, Any]:
    return {
        "result_id": outcome.result_id,
        "rule_id": outcome.result.rule_id,
        "total_rows": outcome.result.total_rows,
        "bad_rows": outcome.result.bad_rows,
        "pass_rate": outcome.result.pass_rate,
        "status": outcome.result.status,
        "severity": outcome.result.severity,
        "checked_at": _iso(outcome.checked_at),
        "olap_written": outcome.olap_written,
    }


def _build_store():
    settings = get_settings()
    try:
        return build_olap_store(
            settings.olap_store, settings.clickhouse_url, settings.clickhouse_database
        )
    except Exception as exc:
        # A missing driver or an unreachable server is reported as 503 so the
        # caller knows the rule could not be evaluated, not that it passed.
        raise HTTPException(
            status_code=503, detail=f"quality olap store unavailable: {exc}"
        ) from exc


@router.get("/rules")
def list_rules(db: Session = Depends(get_session)):
    rows = db.query(DpQualityRule).order_by(DpQualityRule.id.desc()).all()
    return ok([_rule_view(row) for row in rows])


@router.post("/rules")
def create_rule(payload: QualityRuleCreate, db: Session = Depends(get_session)):
    try:
        rule = QualityService(db).create_rule(
            name=payload.name,
            target_table=payload.target_table,
            rule_type=payload.rule_type,
            column_name=payload.column_name,
            params=payload.params,
            threshold=payload.threshold,
            severity=payload.severity,
            cron=payload.cron,
            enabled=payload.enabled,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ok({"id": rule.id})


@router.put("/rules/{rule_id}")
def update_rule(
    rule_id: int,
    payload: QualityRuleUpdate,
    db: Session = Depends(get_session),
):
    rule = _get_rule(db, rule_id)
    changes = payload.model_dump(exclude_unset=True)
    if "params" in changes:
        changes["params_json"] = changes.pop("params")
    try:
        rule = QualityService(db).update_rule(rule, **changes)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ok(_rule_view(rule))


@router.delete("/rules/{rule_id}")
def delete_rule(rule_id: int, db: Session = Depends(get_session)):
    rule = _get_rule(db, rule_id)
    QualityService(db).delete_rule(rule)
    return ok({"deleted": rule_id})


@router.post("/rules/{rule_id}/run")
def run_rule(rule_id: int, db: Session = Depends(get_session)):
    rule = _get_rule(db, rule_id)
    outcome = QualityService(db, _build_store()).run_rule(rule)
    payload = _run_view(outcome)
    if not outcome.olap_written:
        # The MySQL result is committed; surface the warehouse failure with the
        # payload attached instead of returning a misleading success envelope.
        return JSONResponse(
            status_code=502,
            content=fail(502, outcome.olap_error or "quality warehouse write failed", payload),
        )
    return ok(payload)


@router.get("/results")
def list_results(
    rule_id: int | None = None,
    limit: int = Query(default=100, ge=1, le=1000),
    db: Session = Depends(get_session),
):
    query = db.query(DpQualityResult)
    if rule_id is not None:
        query = query.filter(DpQualityResult.rule_id == rule_id)
    rows = query.order_by(DpQualityResult.id.desc()).limit(limit).all()
    return ok([_result_view(row) for row in rows])
