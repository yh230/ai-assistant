import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from etl.cleaning import clean_row, sanitize_sample
from etl.envelope import Envelope
from etl.ports.event_bus import EventConsumer, EventPublisher
from etl.ports.olap_store import OlapStore

QUARANTINE_TABLE = "ops_quarantine"
AUDIT_TABLE = "ops_ingest_audit"
DLT_TOPIC = "dp.ods.DLT"

try:
    _SHANGHAI_TZ: timezone | ZoneInfo = ZoneInfo("Asia/Shanghai")
except ZoneInfoNotFoundError:  # Windows without the tzdata package
    _SHANGHAI_TZ = timezone(timedelta(hours=8), "Asia/Shanghai")


def now_shanghai() -> datetime:
    """Current Asia/Shanghai wall-clock time as a naive datetime.

    The ODS ``*_ingested_at`` columns are ``DateTime`` without a timezone, so
    the value is converted to Asia/Shanghai and the tzinfo is dropped to keep
    the representation consistent regardless of the host timezone.
    """
    return datetime.now(_SHANGHAI_TZ).replace(tzinfo=None)


class BookkeepingWriteError(RuntimeError):
    """A quarantine/audit write failed; the batch offsets must stay uncommitted."""


@dataclass(frozen=True)
class ConsumeResult:
    consumed: int
    inserted: int
    quarantined: int
    failed: int


class ConsumerPipeline:
    """Batch consumer that cleans ODS row events and loads them idempotently.

    Idempotency is per event: duplicate ``event_id`` values inside one polled
    batch are skipped before cleaning, and the in-memory store's global
    ``_event_id`` dedupe is a test-only convenience.  ClickHouse
    ``ReplacingMergeTree`` deduplicates by the table's ``ORDER BY`` business
    key when parts are merged in the background, not by ``_event_id``.

    A quarantine or audit write is bookkeeping: if it fails after retries the
    offsets are not committed and :class:`BookkeepingWriteError` is raised, so
    the polled batch is replayed instead of silently losing the rejection.
    """

    def __init__(
        self,
        consumer: EventConsumer,
        olap: OlapStore,
        dlt_publisher: EventPublisher | None = None,
        max_retries: int = 3,
    ):
        self.consumer = consumer
        self.olap = olap
        self.dlt_publisher = dlt_publisher
        # At least one attempt is always made before an event is dead-lettered.
        self.max_retries = max(1, max_retries)

    def run_once(self, max_records: int = 500) -> ConsumeResult:
        events = self.consumer.poll(max_records=max_records)
        batch_id = str(uuid4())
        inserted = 0
        quarantined = 0
        failed = 0
        audit: dict[str, int] = {}
        seen_event_ids: set[str] = set()
        for event in events:
            try:
                envelope = Envelope.from_bytes(event.value)
                if envelope.event_id in seen_event_ids:
                    continue
                table = envelope.payload["source_table"]
                row = envelope.payload["row"]
                result = clean_row(table, row)
                if result.errors or result.row is None:
                    self._write_quarantine(
                        envelope.event_id,
                        batch_id,
                        table,
                        "; ".join(result.errors) or "row is not usable",
                        row,
                    )
                    seen_event_ids.add(envelope.event_id)
                    quarantined += 1
                    continue
                cleaned = dict(result.row)
                cleaned["_event_id"] = envelope.event_id
                cleaned["_source"] = envelope.payload["source_db"]
                cleaned["_ingested_at"] = now_shanghai()
                self._insert_with_retry(f"ods_{table}", [cleaned])
                seen_event_ids.add(envelope.event_id)
                inserted += 1
                audit[table] = audit.get(table, 0) + 1
            except BookkeepingWriteError:
                # The event would be lost without its quarantine/audit record,
                # so no offset is committed and the batch is replayed.
                raise
            except Exception:
                if self.dlt_publisher is not None:
                    self.dlt_publisher.publish(DLT_TOPIC, event.key or "", event.value)
                failed += 1
        if audit:
            audit_rows = [
                {
                    "batch_id": batch_id,
                    "source_table": table,
                    "rows_ingested": rows_ingested,
                    "_ingested_at": now_shanghai(),
                }
                for table, rows_ingested in audit.items()
            ]
            try:
                self._insert_with_retry(AUDIT_TABLE, audit_rows)
            except Exception as exc:
                raise BookkeepingWriteError(
                    f"audit write failed for batch {batch_id}; offsets not committed"
                ) from exc
        self.consumer.commit()
        return ConsumeResult(
            consumed=len(events), inserted=inserted, quarantined=quarantined, failed=failed
        )

    def _write_quarantine(
        self,
        event_id: str,
        batch_id: str,
        table: str,
        errors: str,
        row: Any,
    ) -> None:
        payload = {
            "_event_id": event_id,
            "batch_id": batch_id,
            "source_table": table,
            "errors": errors,
            # ops_quarantine.sample is a String column, so the rejected row is
            # stored as JSON text for replay; PII keys are stripped first.
            "sample": json.dumps(sanitize_sample(row), ensure_ascii=False, default=str),
            "_ingested_at": now_shanghai(),
        }
        try:
            self._insert_with_retry(QUARANTINE_TABLE, [payload])
        except Exception as exc:
            raise BookkeepingWriteError(
                f"quarantine write failed for event {event_id}; offsets not committed"
            ) from exc

    def _insert_with_retry(self, table: str, rows: list[dict[str, Any]]) -> None:
        last_error: Exception | None = None
        for _ in range(self.max_retries):
            try:
                self.olap.insert_json(table, rows)
                return
            except Exception as exc:
                last_error = exc
        if last_error is not None:
            raise last_error
