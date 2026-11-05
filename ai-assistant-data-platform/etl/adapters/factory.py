from etl.adapters.inmemory_event_bus import InMemoryEventBus
from etl.adapters.inmemory_olap_store import InMemoryOlapStore


def build_event_bus(kind: str, bootstrap: str, group_id: str):
    if kind == "memory":
        return InMemoryEventBus()
    if kind == "kafka":
        # Imported lazily so the in-memory path never loads the optional
        # client library (the adapter itself also imports it lazily).
        from etl.adapters.kafka_event_bus import KafkaEventBus

        return KafkaEventBus(bootstrap, group_id)
    raise ValueError(f"unknown event bus: {kind}")


def build_olap_store(kind: str, url: str, database: str):
    if kind == "memory":
        return InMemoryOlapStore()
    if kind == "clickhouse":
        from etl.adapters.clickhouse_olap_store import ClickHouseOlapStore

        return ClickHouseOlapStore(url, database)
    raise ValueError(f"unknown olap store: {kind}")
