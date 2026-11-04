from datetime import datetime
from typing import Any

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.core.exceptions import DataPlatformError
from app.models.metadata import SyncRun, SyncTask
from etl.extractor import RUN_MODES, ExtractResult, Extractor, SyncTaskSpec
from etl.ports.event_bus import EventPublisher
from etl.ports.source_repository import SourceRepository
from etl.watermark import to_decimal

WATERMARK_TYPES = ("datetime", "numeric")

UPDATABLE_FIELDS = frozenset(
    {
        "datasource_id",
        "source_schema",
        "source_table",
        "target_table",
        "primary_key",
        "watermark_column",
        "watermark_type",
        "batch_size",
        "overlap_seconds",
        "cron",
        "enabled",
    }
)

# ``watermark_column`` is the only nullable task field; everything else must
# keep a value, so an explicit ``null`` is rejected instead of blanking it.
REQUIRED_FIELDS = UPDATABLE_FIELDS - {"watermark_column"}


def _validate_watermark_type(value: str) -> str:
    normalised = (value or "").lower()
    if normalised not in WATERMARK_TYPES:
        raise ValueError(f"unsupported watermark type: {value}")
    return normalised


class SqlRunStore:
    def __init__(self, db: Session):
        self.db = db

    def start_run(self, task_id: int, run_type: str) -> int:
        run = SyncRun(task_id=task_id, run_type=run_type, status="RUNNING")
        self.db.add(run)
        self.db.commit()
        self.db.refresh(run)
        return run.id

    def checkpoint(
        self, run_id: int, rows_read: int, rows_written: int, cursor: dict[str, Any]
    ) -> None:
        run = self.db.get(SyncRun, run_id)
        if run is None:
            raise DataPlatformError(404, f"sync run not found: {run_id}")
        run.rows_read = rows_read
        run.rows_written = rows_written
        run.checkpoint_json = cursor
        self.db.commit()

    def finish_run(
        self,
        run_id: int,
        status: str,
        rows_read: int,
        rows_written: int,
        error: str | None = None,
    ) -> None:
        run = self.db.get(SyncRun, run_id)
        if run is None:
            raise DataPlatformError(404, f"sync run not found: {run_id}")
        run.status = status
        run.rows_read = rows_read
        run.rows_written = rows_written
        run.error_msg = error
        run.finished_at = datetime.utcnow()
        self.db.commit()


class SyncService:
    def __init__(self, db: Session, topic_prefix: str = "dp.ods"):
        self.db = db
        self.topic_prefix = topic_prefix

    def create_task(
        self,
        datasource_id: int,
        source_schema: str,
        source_table: str,
        target_table: str,
        primary_key: str = "id",
        watermark_column: str | None = None,
        watermark_type: str = "datetime",
        batch_size: int = 500,
        overlap_seconds: int = 60,
        cron: str = "*/10 * * * *",
        enabled: bool = True,
    ) -> SyncTask:
        task = SyncTask(
            datasource_id=datasource_id,
            source_schema=source_schema,
            source_table=source_table,
            target_table=target_table,
            primary_key=primary_key,
            watermark_column=watermark_column,
            watermark_type=_validate_watermark_type(watermark_type),
            batch_size=batch_size,
            overlap_seconds=overlap_seconds,
            cron=cron,
            enabled=enabled,
        )
        self.db.add(task)
        self.db.commit()
        self.db.refresh(task)
        return task

    def update_task(self, entity: SyncTask, **changes: Any) -> SyncTask:
        unknown = set(changes) - UPDATABLE_FIELDS
        if unknown:
            raise ValueError("unsupported sync task fields: " + ", ".join(sorted(unknown)))
        for name in REQUIRED_FIELDS:
            if name in changes and changes[name] is None:
                raise ValueError(f"{name} cannot be null")
        if changes.get("watermark_type") is not None:
            changes["watermark_type"] = _validate_watermark_type(changes["watermark_type"])

        # Re-pointing the watermark invalidates the stored position: the next
        # run starts with a full load and rebuilds the watermark.
        watermark_changed = (
            changes.get("watermark_column", entity.watermark_column)
            != entity.watermark_column
            or changes.get("watermark_type", entity.watermark_type)
            != entity.watermark_type
        )
        for name, value in changes.items():
            setattr(entity, name, value)
        if watermark_changed:
            entity.full_load_done = False
            entity.last_watermark = None
            entity.last_watermark_num = None
        self.db.commit()
        self.db.refresh(entity)
        return entity

    def delete_task(self, entity: SyncTask) -> None:
        # Runs are meaningless without their task and there is no FK cascade.
        self.db.query(SyncRun).filter(SyncRun.task_id == entity.id).delete(
            synchronize_session="fetch"
        )
        self.db.delete(entity)
        self.db.commit()

    def get_spec(self, task_id: int) -> SyncTaskSpec:
        return self._to_spec(self._get(task_id))

    def run(
        self,
        task_id: int,
        mode: str,
        source: SourceRepository,
        publisher: EventPublisher,
    ) -> ExtractResult:
        mode = (mode or "").upper()
        if mode not in RUN_MODES:
            raise ValueError(f"unsupported sync mode: {mode}")
        task = self._get(task_id)
        self.acquire_lock(task_id)
        try:
            # Another trigger may have completed between the read above and the
            # lock: reload so the resumed watermark is the latest committed one.
            self.db.refresh(task)
            spec = self._to_spec(task)
            result = Extractor(source, publisher, self.topic_prefix).run(
                spec, mode, SqlRunStore(self.db)
            )
            task.full_load_done = True
            task.last_run_at = datetime.utcnow()
            if spec.watermark_type == "numeric":
                task.last_watermark_num = result.last_watermark_num
            else:
                task.last_watermark = result.last_watermark
            self.db.commit()
            return result
        finally:
            self.release_lock(task_id)

    def acquire_lock(self, task_id: int) -> None:
        """Claim the task's row-level run mutex.

        The conditional UPDATE is the atomic gate: only the transaction that
        flips ``running`` from 0 to 1 wins, so a second trigger (manual or
        scheduler) is rejected instead of double-extracting the source.
        """
        result = self.db.execute(
            update(SyncTask)
            .where(SyncTask.id == task_id, SyncTask.running.is_(False))
            .values(running=True, locked_at=datetime.utcnow())
            .execution_options(synchronize_session=False)
        )
        self.db.commit()
        if result.rowcount == 1:
            return
        if not self._exists(task_id):
            raise DataPlatformError(404, f"sync task not found: {task_id}")
        raise DataPlatformError(409, f"sync task {task_id} is already running")

    def release_lock(self, task_id: int) -> None:
        if not self.db.is_active:
            # A failed flush/commit leaves the session in a partial rollback;
            # recover it so the mutex is always released.
            self.db.rollback()
        self.db.execute(
            update(SyncTask)
            .where(SyncTask.id == task_id)
            .values(running=False, locked_at=None)
            .execution_options(synchronize_session=False)
        )
        self.db.commit()

    def _get(self, task_id: int) -> SyncTask:
        task = self.db.get(SyncTask, task_id)
        if task is None:
            raise DataPlatformError(404, f"sync task not found: {task_id}")
        return task

    def _exists(self, task_id: int) -> bool:
        row = self.db.execute(select(SyncTask.id).where(SyncTask.id == task_id)).first()
        return row is not None

    def _to_spec(self, task: SyncTask) -> SyncTaskSpec:
        watermark_type = (task.watermark_type or "datetime").lower()
        # Only the field matching the configured watermark type is exposed, so
        # a stale value left by a previous configuration can never leak into a
        # run (datetime watermarks live in ``last_watermark`` only).
        is_numeric = watermark_type == "numeric"
        return SyncTaskSpec(
            id=task.id,
            source_schema=task.source_schema,
            source_table=task.source_table,
            target_table=task.target_table,
            primary_key=task.primary_key,
            watermark_column=task.watermark_column,
            batch_size=task.batch_size,
            overlap_seconds=task.overlap_seconds,
            full_load_done=task.full_load_done,
            last_watermark=None if is_numeric else task.last_watermark,
            watermark_type=watermark_type,
            last_watermark_num=to_decimal(task.last_watermark_num) if is_numeric else None,
        )
