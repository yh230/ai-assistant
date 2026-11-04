import hashlib
import json
import logging
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Protocol
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from etl.cleaning import clean_row, sanitize_sample
from etl.envelope import Envelope
from etl.ports.event_bus import ConsumedEvent, EventConsumer, EventPublisher
from etl.ports.olap_store import OlapStore
from etl.realtime import REGISTRATION_EVENT_TOPIC

logger = logging.getLogger(__name__)

QUARANTINE_TABLE = "ops_quarantine"
AUDIT_TABLE = "ops_ingest_audit"
DLT_TOPIC = "dp.ods.DLT"
ODS_EVENT_TYPE = "ODS_ROW"

try:
    _SHANGHAI_TZ: timezone | ZoneInfo = ZoneInfo("Asia/Shanghai")
except ZoneInfoNotFoundError:  # Windows without the tzdata package
    _SHANGHAI_TZ = timezone(timedelta(hours=8), "Asia/Shanghai")


class RealtimeHandler(Protocol):
    """Sink for events that are not ODS rows (e.g. ``ai.registration.event``)."""

    def handle(self, event: ConsumedEvent) -> Any: ...


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
    realtime: int = 0


def event_batch_key(event: ConsumedEvent) -> str:
    """Stable identity of one polled event for the batch hash.

    A parsable envelope contributes its ``eventId``; anything that is not an
    envelope (realtime MQ payloads, malformed messages) falls back to the Kafka
    position, which is also stable when a batch is rewound and replayed.
    """
    try:
        return Envelope.from_bytes(event.value).event_id
    except ValueError:
        return f"{event.topic}:{event.partition}:{event.offset}"


def batch_id_for(events: Sequence[ConsumedEvent]) -> str:
    """Deterministic batch id, so a replayed batch hashes identically.

    Quarantine and audit rows carry this id; replaying the same polled events
    after a rewind therefore produces the same ``batch_id`` and
    ``ReplacingMergeTree`` collapses the bookkeeping rows on merge.
    """
    parts = [event_batch_key(event) for event in events]
    return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()[:16]


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
    The ``batch_id`` on those rows is a deterministic hash of the polled events
    (:func:`batch_id_for`), so a replayed batch reuses its id and the
    ``ReplacingMergeTree`` bookkeeping tables collapse instead of duplicating.

    When a ``realtime_handler`` is supplied, events from
    ``ai.registration.event`` and envelopes whose ``eventType`` is not
    ``ODS_ROW`` are routed to it instead of the ODS cleaner; everything else
    keeps the Task 4 behaviour.  Realtime handler calls get the same per-event
    retry budget and fall through to the same dead-letter topic.
    """

    def __init__(
        self,
        consumer: EventConsumer,
        olap: OlapStore,
        dlt_publisher: EventPublisher | None = None,
        max_retries: int = 3,
        realtime_handler: RealtimeHandler | None = None,
    ):
        self.consumer = consumer
        self.olap = olap
        self.dlt_publisher = dlt_publisher
        # At least one attempt is always made before an event is dead-lettered.
        self.max_retries = max(1, max_retries)
        self.realtime_handler = realtime_handler
        # The last polled batch, kept so the entrypoint can rewind the consumer
        # to it when the batch has to be replayed after a bookkeeping failure.
        self.last_events: list[ConsumedEvent] = []

    def run_once(self, max_records: int = 500) -> ConsumeResult:
        events = self.consumer.poll(max_records=max_records)
        self.last_events = events
        batch_id = batch_id_for(events)
        inserted = 0
        quarantined = 0
        failed = 0
        realtime = 0
        audit: dict[str, int] = {}
        seen_event_ids: set[str] = set()
        for event in events:
            try:
                if self.realtime_handler is not None and self._is_realtime(event):
                    event_id = self._realtime_event_id(event)
                    if event_id is not None and event_id in seen_event_ids:
                        # A duplicate event id is only processed once per batch,
                        # exactly like the ODS path.
                        continue
                    self._handle_realtime(event)
                    if event_id is not None:
                        seen_event_ids.add(event_id)
                    realtime += 1
                    continue
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
                    self._flush_dlt()
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
            consumed=len(events),
            inserted=inserted,
            quarantined=quarantined,
            failed=failed,
            realtime=realtime,
        )

    def _is_realtime(self, event: ConsumedEvent) -> bool:
        """True for the registration topic and any non-ODS envelope.

        ODS rows always carry ``eventType: "ODS_ROW"``.  The event-type
        fallback covers producers that publish the registration envelope on
        another topic (or buses that do not model topics), while a malformed
        value stays on the ODS path so the existing handler dead-letters it.
        """
        if event.topic == REGISTRATION_EVENT_TOPIC:
            return True
        try:
            body = json.loads(event.value.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return False
        return isinstance(body, dict) and body.get("eventType") not in (None, ODS_EVENT_TYPE)

    @staticmethod
    def _realtime_event_id(event: ConsumedEvent) -> str | None:
        """The envelope ``eventId`` used to deduplicate realtime events.

        The realtime payload is intentionally not decoded here (the handler
        owns that); a value that is not a JSON object cannot be deduplicated
        and is left to the handler to dead-letter.
        """
        try:
            body = json.loads(event.value.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None
        if not isinstance(body, dict):
            return None
        event_id = body.get("eventId")
        if event_id is None or str(event_id) == "":
            return None
        return str(event_id)

    def _handle_realtime(self, event: ConsumedEvent) -> None:
        for attempt in range(self.max_retries):
            try:
                self.realtime_handler.handle(event)
                return
            except Exception:
                if attempt + 1 >= self.max_retries:
                    raise

    def _flush_dlt(self) -> None:
        """Flush the dead-letter publisher without failing the batch.

        The batch is already being committed, so a flush error is logged and
        the message stays buffered for the next flush/close instead of
        aborting the loop.
        """
        flush = getattr(self.dlt_publisher, "flush", None)
        if not callable(flush):
            return
        try:
            flush()
        except Exception:
            logger.warning("dead letter flush failed; message may still be buffered", exc_info=True)

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
