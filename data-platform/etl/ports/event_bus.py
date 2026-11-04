from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class ConsumedEvent:
    topic: str
    key: str | None
    value: bytes
    partition: int = 0
    offset: int = 0


class EventPublisher(Protocol):
    def publish(self, topic: str, key: str, value: bytes) -> None: ...

    def flush(self) -> None: ...


class EventConsumer(Protocol):
    def subscribe(self, topics: list[str]) -> None: ...

    def poll(self, timeout_seconds: float = 1.0, max_records: int = 500) -> list[ConsumedEvent]: ...

    def commit(self) -> None: ...

    def close(self) -> None: ...
