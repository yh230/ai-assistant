"""Resident consumer process for ODS row events and real-time registration events.

Run with ``python -m etl.consumer_main``.  The loop is deliberately sequential
(no thread pool / bounded queue in V1): one polled batch is loaded, bookkept and
committed before the next one is fetched, which keeps Kafka offsets and
ClickHouse rows consistent at the cost of throughput.
"""

import logging
import signal
import time

from etl.adapters.factory import build_event_bus, build_olap_store
from etl.consumer import BookkeepingWriteError, ConsumerPipeline
from etl.ports.event_bus import ConsumedEvent, EventConsumer
from etl.ports.olap_store import OlapStore
from etl.realtime import REGISTRATION_EVENT_TOPIC, RegistrationEventHandler
from etl.source_registry import SOURCES, SOURCE_SCHEMAS, SOURCE_TABLES, topic_for

logger = logging.getLogger(__name__)

IDLE_SLEEP_SECONDS = 1.0
# Bookkeeping failures leave the batch uncommitted, so the same records are
# replayed after a pause instead of killing the process.
BACKOFF_SECONDS = 5.0


def subscribe_topics(topic_prefix: str) -> list[str]:
    """ODS topics for every registered source table plus the realtime topic."""
    topics = [topic_for(topic_prefix, spec) for spec in SOURCES]
    topics.append(REGISTRATION_EVENT_TOPIC)
    return topics


def build_pipeline(consumer: EventConsumer, olap: OlapStore) -> ConsumerPipeline:
    """Wire DLT publishing and real-time handling into the consumer pipeline.

    Failed ODS rows are dead-lettered through the consumer itself so they land
    on ``dp.ods.DLT`` instead of being dropped.
    """
    return ConsumerPipeline(
        consumer,
        olap,
        dlt_publisher=consumer,
        realtime_handler=RegistrationEventHandler(olap),
    )


def serve(
    pipeline: ConsumerPipeline,
    consumer: EventConsumer,
    sleep=time.sleep,
) -> None:
    """Consume until interrupted; back off on bookkeeping failures."""
    try:
        while True:
            try:
                result = pipeline.run_once()
            except BookkeepingWriteError as exc:
                # The pipeline raises before commit(), so the offsets are not
                # advanced.  Rewind the consumer to the first offset of the
                # failed batch so the next poll replays it; without rewind the
                # in-process position has moved on and only a restart or
                # rebalance would replay the records.
                events = list(getattr(pipeline, "last_events", []) or [])
                if _rewind(consumer, events):
                    logger.warning(
                        "bookkeeping write failed; rewound %d event(s), retrying in %ss: %s",
                        len(events),
                        BACKOFF_SECONDS,
                        exc,
                    )
                else:
                    logger.warning(
                        "bookkeeping write failed; offsets not committed, replay requires "
                        "a restart or rebalance; retrying in %ss: %s",
                        BACKOFF_SECONDS,
                        exc,
                    )
                sleep(BACKOFF_SECONDS)
                continue
            if result.consumed == 0:
                sleep(IDLE_SLEEP_SECONDS)
    except KeyboardInterrupt:
        logger.info("consumer interrupted; shutting down")
    finally:
        consumer.close()


def _rewind(consumer: EventConsumer, events: list[ConsumedEvent]) -> bool:
    """Seek ``consumer`` back to ``events``; False when it cannot be replayed here."""
    rewind = getattr(consumer, "rewind", None)
    if not callable(rewind):
        return False
    try:
        rewind(events)
    except Exception:
        logger.exception("rewind failed; the failed batch needs a restart or rebalance")
        return False
    return True


def handle_sigterm(signum, frame) -> None:
    """Turn SIGTERM into the same graceful shutdown path as Ctrl+C."""
    raise KeyboardInterrupt


def _load_settings():
    # Imported lazily so this module (and its topic/back-off helpers) stays
    # importable without the FastAPI/pydantic dependency set; the process
    # itself always runs inside the application environment.
    from app.core.config import get_settings

    return get_settings()


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    try:
        signal.signal(signal.SIGTERM, handle_sigterm)
    except (AttributeError, ValueError, OSError):
        # signal handlers can only be installed from the main thread and are
        # not available on every platform; the loop still handles Ctrl+C.
        logger.warning("SIGTERM handler could not be installed", exc_info=True)
    settings = _load_settings()
    consumer = build_event_bus(
        settings.event_bus, settings.kafka_bootstrap, settings.kafka_consumer_group
    )
    olap = build_olap_store(
        settings.olap_store, settings.clickhouse_url, settings.clickhouse_database
    )
    topics = subscribe_topics(settings.kafka_topic_prefix)
    consumer.subscribe(topics)
    logger.info("consuming topics: %s", ", ".join(topics))
    serve(build_pipeline(consumer, olap), consumer)


if __name__ == "__main__":
    main()
