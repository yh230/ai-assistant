from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Any
from uuid import uuid4

from etl.envelope import Envelope
from etl.ports.event_bus import EventPublisher
from etl.ports.run_store import RunStore
from etl.ports.source_repository import SourceRepository
from etl.privacy import redact_source_row
from etl.watermark import advance_numeric, advance_watermark, lower_bound, to_decimal

RUN_MODES = ("FULL", "INCREMENTAL")
WATERMARK_TYPES = ("datetime", "numeric")


@dataclass(frozen=True)
class SyncTaskSpec:
    id: int
    source_schema: str
    source_table: str
    target_table: str
    primary_key: str
    watermark_column: str | None
    batch_size: int = 500
    overlap_seconds: int = 60
    full_load_done: bool = False
    last_watermark: datetime | None = None
    watermark_type: str = "datetime"
    last_watermark_num: Decimal | None = None


@dataclass(frozen=True)
class ExtractResult:
    rows_read: int
    rows_written: int
    last_watermark: datetime | None = None
    last_watermark_num: Decimal | None = None


def _cursor_value(value: Any) -> Any:
    """Render a checkpoint cursor value as JSON-friendly data."""
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, Decimal):
        return str(value)
    return value


def _watermark_cursor(value: Any, watermark_type: str) -> str:
    """Render the watermark checkpoint as a stable string across dialects.

    MySQL returns ``Decimal``/``int`` depending on the column type while
    SQLite returns plain numbers; the checkpoint stores one shape.
    """
    if watermark_type == "numeric":
        return str(to_decimal(value))
    return value.isoformat() if isinstance(value, datetime) else str(value)


class Extractor:
    def __init__(self, source: SourceRepository, publisher: EventPublisher, topic_prefix: str):
        self.source = source
        self.publisher = publisher
        self.topic_prefix = topic_prefix

    def run(self, task: SyncTaskSpec, mode: str, run_store: RunStore) -> ExtractResult:
        mode = (mode or "").upper()
        if mode not in RUN_MODES:
            raise ValueError(f"unsupported sync mode: {mode}")
        watermark_type = (task.watermark_type or "datetime").lower()
        if watermark_type not in WATERMARK_TYPES:
            raise ValueError(f"unsupported watermark type: {watermark_type}")

        run_type = "FULL" if mode == "FULL" or not task.full_load_done else "INCREMENTAL"
        run_id = run_store.start_run(task.id, run_type)
        rows_read = 0
        rows_written = 0
        last_watermark = task.last_watermark
        last_watermark_num = to_decimal(task.last_watermark_num)
        try:
            if run_type == "FULL":
                after_pk = None
                while True:
                    rows = self.source.fetch_full_batch(
                        task.source_schema,
                        task.source_table,
                        ["*"],
                        task.primary_key,
                        after_pk,
                        task.batch_size,
                    )
                    if not rows:
                        break
                    self._publish_rows(task, rows)
                    rows_read += len(rows)
                    rows_written += len(rows)
                    after_pk = rows[-1][task.primary_key]
                    run_store.checkpoint(
                        run_id,
                        rows_read,
                        rows_written,
                        {"after_pk": _cursor_value(after_pk)},
                    )
                if task.watermark_column:
                    maximum = self.source.max_watermark(
                        task.source_schema, task.source_table, task.watermark_column
                    )
                    if watermark_type == "numeric":
                        last_watermark_num = advance_numeric(last_watermark_num, maximum)
                    else:
                        last_watermark = advance_watermark(last_watermark, maximum)
            else:
                if not task.watermark_column:
                    raise ValueError(
                        "incremental task requires a watermark column and a last watermark"
                    )
                if watermark_type == "numeric":
                    if last_watermark_num is None:
                        raise ValueError(
                            "incremental numeric task requires a last numeric watermark"
                        )
                    cursor_watermark: Any = last_watermark_num
                else:
                    if task.last_watermark is None:
                        raise ValueError(
                            "incremental task requires a watermark column and a last watermark"
                        )
                    cursor_watermark = (
                        lower_bound(task.last_watermark, task.overlap_seconds)
                        or task.last_watermark
                    )
                # Freeze the run at the source's current maximum watermark:
                # rows written while this run is in progress are left for the
                # next run instead of extending it without a bound.
                upper_watermark = self.source.max_watermark(
                    task.source_schema, task.source_table, task.watermark_column
                )
                cursor_pk = None
                while True:
                    rows = self.source.fetch_incremental_batch(
                        task.source_schema,
                        task.source_table,
                        ["*"],
                        task.primary_key,
                        task.watermark_column,
                        cursor_watermark,
                        cursor_pk,
                        task.batch_size,
                        upper_watermark=upper_watermark,
                    )
                    if not rows:
                        break
                    self._publish_rows(task, rows)
                    rows_read += len(rows)
                    rows_written += len(rows)
                    cursor_watermark = rows[-1][task.watermark_column]
                    cursor_pk = rows[-1][task.primary_key]
                    if watermark_type == "numeric":
                        last_watermark_num = advance_numeric(last_watermark_num, cursor_watermark)
                    else:
                        last_watermark = advance_watermark(last_watermark, cursor_watermark)
                    run_store.checkpoint(
                        run_id,
                        rows_read,
                        rows_written,
                        {
                            "after_watermark": _watermark_cursor(
                                cursor_watermark, watermark_type
                            ),
                            "after_pk": _cursor_value(cursor_pk),
                        },
                    )
                    if len(rows) < task.batch_size:
                        break
            run_store.finish_run(run_id, "SUCCESS", rows_read, rows_written)
            return ExtractResult(rows_read, rows_written, last_watermark, last_watermark_num)
        except Exception as exc:
            run_store.finish_run(run_id, "FAILED", rows_read, rows_written, str(exc))
            raise

    def _publish_rows(self, task: SyncTaskSpec, rows: list[dict]) -> None:
        topic = f"{self.topic_prefix}.{task.source_schema}.{task.source_table}"
        for row in rows:
            envelope = Envelope(
                event_id=str(uuid4()),
                event_type="ODS_ROW",
                occurred_at=int(datetime.now().timestamp() * 1000),
                payload={
                    "source_db": task.source_schema,
                    "source_table": task.source_table,
                    # The consumer validates this against the source registry;
                    # carrying it makes the target explicit in the event
                    # contract instead of deriving it from the cleaner name.
                    "target_table": task.target_table,
                    # Direct PII (patient name/phone/id-card, chat body and
                    # references) never reaches Kafka or the DLT.
                    "row": redact_source_row(task.source_table, row),
                },
            )
            self.publisher.publish(topic, self._partition_key(task, row), envelope.to_bytes())
        self.publisher.flush()

    @staticmethod
    def _partition_key(task: SyncTaskSpec, row: dict) -> str:
        # Same-hospital rows must stay ordered, so ``hospital_id`` wins when
        # the source table has it; otherwise fall back to the business key.
        value = row.get("hospital_id")
        if value is None:
            value = row.get(task.primary_key)
        return "" if value is None else str(value)
