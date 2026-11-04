from etl.ports.event_bus import ConsumedEvent


class InMemoryEventBus:
    """In-process stand-in for the Kafka producer/consumer pair.

    Implements both ``EventPublisher`` and ``EventConsumer`` so unit tests can
    drive the consumer pipeline without a broker.  ``subscribe`` only records
    the topics; published events are delivered regardless of subscription, which
    keeps single-purpose tests free of topic bookkeeping.
    """

    def __init__(self):
        self.pending: list[ConsumedEvent] = []
        self.committed = False
        self.closed = False
        self.subscribed_topics: list[str] = []

    def subscribe(self, topics: list[str]) -> None:
        self.subscribed_topics = list(topics)

    def publish(self, topic: str, key: str, value: bytes) -> None:
        self.pending.append(ConsumedEvent(topic=topic, key=key, value=value))

    def flush(self) -> None:
        pass

    def poll(self, timeout_seconds: float = 1.0, max_records: int = 500) -> list[ConsumedEvent]:
        batch = self.pending[:max_records]
        self.pending = self.pending[max_records:]
        return batch

    def commit(self) -> None:
        self.committed = True

    def close(self) -> None:
        self.closed = True
