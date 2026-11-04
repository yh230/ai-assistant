"""Resident consumer process for ODS row events and real-time registration events.

Run with ``python -m etl.consumer_main``.  The loop is deliberately sequential
(no thread pool / bounded queue in V1): one polled batch is loaded, bookkept and
committed before the next one is fetched, which keeps Kafka offsets and
ClickHouse rows consistent at the cost of throughput.
"""

import logging
import time

from etl.adapters.factory import build_event_bus, build_olap_store
from etl.consumer import BookkeepingWriteError, ConsumerPipeline
from etl.ports.event_bus import EventConsumer
from etl.ports.olap_store import OlapStore
from etl.realtime import REGISTRATION_EVENT_TOPIC, RegistrationEventHandler

logger = logging.getLogger(__name__)

SOURCE_TABLES = (
    "registration",
    "chat_session",
    "chat_message",
    "doctor_schedule",
    "number_source",
    "hospital",
    "department",
)
SOURCE_SCHEMAS = {
    "registration": "registration_db",
    "chat_session": "chat_db",
    "chat_message": "chat_db",
    "doctor_schedule": "registration_db",
    "number_source": "registration_db",
    "hospital": "hospital_db",
    "department": "hospital_db",
}

IDLE_SLEEP_SECONDS = 1.0
# Bookkeeping failures leave the batch uncommitted, so the same records are
# replayed after a pause instead of killing the process.
BACKOFF_SECONDS = 5.0


def subscribe_topics(topic_prefix: str) -> list[str]:
    """ODS topics for every source table plus the real-time registration topic."""
    topics = [
        f"{topic_prefix}.{SOURCE_SCHEMAS[table]}.{table}" for table in SOURCE_TABLES
    ]
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
                # The pipeline raises before commit(), so the batch is replayed
                # after the pause; the offset is not advanced here.
                logger.warning(
                    "bookkeeping write failed; offsets not committed, retrying in %ss: %s",
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
