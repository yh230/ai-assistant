import json
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from uuid import uuid4

from etl.cleaning import clean_row
from etl.envelope import Envelope
from etl.ports.event_bus import EventConsumer, EventPublisher
from etl.ports.olap_store import OlapStore

QUARANTINE_TABLE = "ops_quarantine"
AUDIT_TABLE = "ops_ingest_audit"
DLT_TOPIC = "dp.ods.DLT"


@dataclass(frozen=True)
class ConsumeResult:
    consumed: int
    inserted: int
    quarantined: int
    failed: int


class ConsumerPipeline:
    """Batch consumer that cleans ODS row events and loads them idempotently.

    Idempotency has two layers: events already seen in the batch are skipped
    before cleaning, and the OLAP store deduplicates by ``_event_id`` (the
    in-memory fake and ClickHouse ``ReplacingMergeTree`` both honour it), so a
    replayed Kafka batch never yields duplicate rows or audit counts.
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
                    self.olap.insert_json(QUARANTINE_TABLE, [{
                        "_event_id": envelope.event_id,
                        "source_table": table,
                        "errors": "; ".join(result.errors) or "row is not usable",
                        # ops_quarantine.sample is a String column, so the
                        # rejected row is stored as JSON text for replay.
                        "sample": json.dumps(row, ensure_ascii=False, default=str),
                        "_ingested_at": datetime.now(),
                    }])
                    seen_event_ids.add(envelope.event_id)
                    quarantined += 1
                    continue
                cleaned = dict(result.row)
                cleaned["_event_id"] = envelope.event_id
                cleaned["_source"] = envelope.payload["source_db"]
                cleaned["_ingested_at"] = datetime.now()
                self._insert_with_retry(f"ods_{table}", [cleaned])
                seen_event_ids.add(envelope.event_id)
                inserted += 1
                audit[table] = audit.get(table, 0) + 1
            except Exception:
                if self.dlt_publisher is not None:
                    self.dlt_publisher.publish(DLT_TOPIC, event.key or "", event.value)
                failed += 1
        for table, rows_ingested in audit.items():
            self.olap.insert_json(AUDIT_TABLE, [{
                "batch_id": batch_id,
                "source_table": table,
                "rows_ingested": rows_ingested,
                "_ingested_at": datetime.now(),
            }])
        self.consumer.commit()
        return ConsumeResult(
            consumed=len(events), inserted=inserted, quarantined=quarantined, failed=failed
        )

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
