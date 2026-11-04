from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.api.deps import get_session, require_staff
from app.core.config import get_settings
from app.core.response import ok
from app.models.metadata import DataSource, SyncRun, SyncTask
from app.schemas.sync import SyncTaskCreate, SyncTaskUpdate
from app.services.datasource_service import DatasourceService
from app.services.sync_service import SyncService
from etl.adapters.mysql_source_repository import MySQLSourceRepository

router = APIRouter(
    prefix="/sync-tasks",
    tags=["sync-tasks"],
    dependencies=[Depends(require_staff)],
)

# Run records live at ``/sync-runs`` (design §8), outside the task prefix.
runs_router = APIRouter(
    prefix="/sync-runs",
    tags=["sync-runs"],
    dependencies=[Depends(require_staff)],
)


def _service(db: Session) -> SyncService:
    return SyncService(db, get_settings().kafka_topic_prefix)


def _get_task(db: Session, task_id: int) -> SyncTask:
    task = db.get(SyncTask, task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="sync task not found")
    return task


def _iso(value: Any) -> str | None:
    return value.isoformat() if value is not None else None


def _task_view(task: SyncTask) -> dict[str, Any]:
    return {
        "id": task.id,
        "datasource_id": task.datasource_id,
        "source_schema": task.source_schema,
        "source_table": task.source_table,
        "target_table": task.target_table,
        "primary_key": task.primary_key,
        "watermark_column": task.watermark_column,
        "watermark_type": task.watermark_type,
        "batch_size": task.batch_size,
        "overlap_seconds": task.overlap_seconds,
        "cron": task.cron,
        "enabled": task.enabled,
        "full_load_done": task.full_load_done,
        "last_watermark": _iso(task.last_watermark),
        "last_watermark_num": (
            str(task.last_watermark_num) if task.last_watermark_num is not None else None
        ),
        "last_run_at": _iso(task.last_run_at),
        "running": bool(task.running),
        "locked_at": _iso(task.locked_at),
    }


def _run_view(run: SyncRun) -> dict[str, Any]:
    return {
        "id": run.id,
        "task_id": run.task_id,
        "run_type": run.run_type,
        "status": run.status,
        "started_at": _iso(run.started_at),
        "finished_at": _iso(run.finished_at),
        "rows_read": run.rows_read,
        "rows_written": run.rows_written,
        "error_msg": run.error_msg,
    }


MEMORY_BUS_WARNING = "memory event bus: events were not published to Kafka"


def _extract_view(result: Any, event_bus: str) -> dict[str, Any]:
    """Response payload for a manual run.

    ``EVENT_BUS=memory`` uses a throwaway in-memory bus: the extractor counted
    the rows it handed to it, but nothing reached Kafka, so the API must not
    report them as written.
    """
    memory_bus = (event_bus or "").lower() == "memory"
    return {
        "rows_read": result.rows_read,
        "rows_written": 0 if memory_bus else result.rows_written,
        "published": not memory_bus,
        "warning": MEMORY_BUS_WARNING if memory_bus else None,
        "last_watermark": _iso(result.last_watermark),
        "last_watermark_num": (
            str(result.last_watermark_num)
            if result.last_watermark_num is not None
            else None
        ),
    }


@runs_router.get("")
def list_runs(
    task_id: int | None = None,
    limit: int = Query(default=100, ge=1, le=1000),
    db: Session = Depends(get_session),
):
    query = db.query(SyncRun)
    if task_id is not None:
        query = query.filter(SyncRun.task_id == task_id)
    rows = query.order_by(SyncRun.id.desc()).limit(limit).all()
    return ok([_run_view(row) for row in rows])


@router.get("")
def list_tasks(db: Session = Depends(get_session)):
    rows = db.query(SyncTask).order_by(SyncTask.id.desc()).all()
    return ok([_task_view(row) for row in rows])


@router.post("")
def create_task(payload: SyncTaskCreate, db: Session = Depends(get_session)):
    if db.get(DataSource, payload.datasource_id) is None:
        raise HTTPException(status_code=404, detail="datasource not found")
    try:
        task = _service(db).create_task(**payload.model_dump())
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ok({"id": task.id})


@router.put("/{task_id}")
def update_task(
    task_id: int,
    payload: SyncTaskUpdate,
    db: Session = Depends(get_session),
):
    task = _get_task(db, task_id)
    changes = payload.model_dump(exclude_unset=True)
    if "datasource_id" in changes and db.get(DataSource, changes["datasource_id"]) is None:
        raise HTTPException(status_code=404, detail="datasource not found")
    try:
        task = _service(db).update_task(task, **changes)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ok(_task_view(task))


@router.delete("/{task_id}")
def delete_task(task_id: int, db: Session = Depends(get_session)):
    task = _get_task(db, task_id)
    _service(db).delete_task(task)
    return ok({"deleted": task_id})


@router.post("/{task_id}/run")
def run_task(
    task_id: int,
    mode: str = "INCREMENTAL",
    db: Session = Depends(get_session),
):
    task = _get_task(db, task_id)
    datasource = db.get(DataSource, task.datasource_id)
    if datasource is None:
        raise HTTPException(status_code=404, detail="datasource not found")
    settings = get_settings()
    try:
        from etl.adapters.factory import build_event_bus
    except ImportError as exc:
        # The adapter factory is delivered with the Kafka/ClickHouse adapters.
        raise HTTPException(
            status_code=503, detail="event bus backend is not available"
        ) from exc
    try:
        source_url = DatasourceService(db, settings.dp_secret_key).url_for(datasource)
    except ValueError as exc:
        # Missing DP_SECRET_KEY surfaces here when the URL is decrypted.
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    source = MySQLSourceRepository(source_url)
    publisher = build_event_bus(
        settings.event_bus, settings.kafka_bootstrap, settings.kafka_consumer_group
    )
    try:
        result = _service(db).run(task_id, mode, source, publisher)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ok(_extract_view(result, settings.event_bus))
