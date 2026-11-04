from typing import Any


class InMemoryOlapStore:
    """In-memory OLAP fake with the same idempotency contract as the ODS tables.

    Rows are deduplicated by ``_event_id`` across all tables, mirroring the
    Kafka ``eventId`` + ClickHouse ``ReplacingMergeTree`` guarantee: replaying
    an event never adds a second row.
    """

    def __init__(self):
        self.tables: dict[str, list[dict[str, Any]]] = {}
        self.executed: list[str] = []
        self._event_ids: set[str] = set()

    def insert_json(self, table: str, rows: list[dict[str, Any]]) -> None:
        for row in rows:
            event_id = row.get("_event_id")
            if event_id and event_id in self._event_ids:
                continue
            if event_id:
                self._event_ids.add(event_id)
            self.tables.setdefault(table, []).append(dict(row))

    def execute(self, sql: str) -> None:
        self.executed.append(sql)

    def query(self, sql: str) -> list[dict[str, Any]]:
        return []

    def count(self, table: str) -> int:
        return len(self.tables.get(table, []))
