import logging

from etl.ports.event_bus import ConsumedEvent

logger = logging.getLogger(__name__)

_MISSING = "confluent-kafka is not installed; install the optional 'kafka' dependency group"


class KafkaEventBus:
    """Kafka implementation of ``EventPublisher`` and ``EventConsumer``.

    The client library is imported lazily so this module can be imported (and
    unit tested) on machines without the optional ``kafka`` dependency; the
    constructor raises a clear ``RuntimeError`` instead of an ``ImportError``.

    Offsets are committed manually (``enable.auto.commit=False``) so the
    consumer pipeline only advances the group position after a batch has been
    loaded and its bookkeeping written.
    """

    def __init__(self, bootstrap: str, group_id: str):
        try:
            from confluent_kafka import Consumer, Producer
        except ImportError as exc:
            raise RuntimeError(_MISSING) from exc
        self._producer = Producer(
            {
                "bootstrap.servers": bootstrap,
                "enable.idempotence": True,
                "acks": "all",
                # Design §6.3: batch the per-event publishes and compress with
                # lz4; a few milliseconds of linger is enough to coalesce one
                # consumer batch without adding visible latency.
                "compression.type": "lz4",
                "linger.ms": 5,
                "batch.size": 65536,
            }
        )
        self._consumer = Consumer(
            {
                "bootstrap.servers": bootstrap,
                "group.id": group_id,
                "auto.offset.reset": "earliest",
                "enable.auto.commit": False,
            }
        )

    def publish(self, topic: str, key: str, value: bytes) -> None:
        self._producer.produce(topic, key=key, value=value)

    def flush(self) -> None:
        self._producer.flush()

    def subscribe(self, topics: list[str]) -> None:
        self._consumer.subscribe(topics)

    def poll(self, timeout_seconds: float = 1.0, max_records: int = 500) -> list[ConsumedEvent]:
        events: list[ConsumedEvent] = []
        for _ in range(max_records):
            message = self._consumer.poll(timeout_seconds if not events else 0)
            if message is None:
                break
            if message.error():
                # Partition EOF/heartbeat notifications are not records; real
                # broker errors are surfaced in the log instead of dropped.
                logger.warning("kafka poll error: %s", message.error())
                continue
            events.append(
                ConsumedEvent(
                    topic=message.topic(),
                    key=message.key().decode("utf-8") if message.key() else None,
                    value=message.value(),
                    partition=message.partition(),
                    offset=message.offset(),
                )
            )
        return events

    def commit(self) -> None:
        self._consumer.commit(asynchronous=False)

    def rewind(self, events: list[ConsumedEvent]) -> None:
        """Seek each partition back to the first offset of the failed batch.

        The batch stays uncommitted after a ``BookkeepingWriteError``, but the
        in-process consumer position has already advanced past it; seeking back
        replays the same records on the next poll instead of waiting for a
        rebalance.
        """
        if not events:
            return
        try:
            from confluent_kafka import TopicPartition
        except ImportError as exc:
            raise RuntimeError(_MISSING) from exc
        first_offsets: dict[tuple[str, int], int] = {}
        for event in events:
            key = (event.topic, event.partition)
            if key not in first_offsets or event.offset < first_offsets[key]:
                first_offsets[key] = event.offset
        for (topic, partition), offset in first_offsets.items():
            self._consumer.seek(TopicPartition(topic, partition, offset))

    def close(self) -> None:
        # The dead-letter and ODS messages are produced asynchronously: flush
        # them before closing so a buffered message is not lost on shutdown.
        self._producer.flush()
        self._consumer.close()
