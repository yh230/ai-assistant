from typing import Any
from urllib.parse import urlparse

_MISSING = "clickhouse-connect is not installed; install the optional 'clickhouse' dependency group"


class ClickHouseOlapStore:
    """ClickHouse implementation of ``OlapStore`` over the HTTP protocol.

    The client library is imported lazily so this module can be imported (and
    unit tested) without the optional ``clickhouse`` dependency; the
    constructor raises a clear ``RuntimeError`` instead of an ``ImportError``.
    """

    def __init__(self, url: str, database: str):
        try:
            import clickhouse_connect
        except ImportError as exc:
            raise RuntimeError(_MISSING) from exc
        parsed = urlparse(url)
        self.database = database
        self.client = clickhouse_connect.get_client(
            host=parsed.hostname or "127.0.0.1",
            port=parsed.port or 8123,
            username=parsed.username or "default",
            password=parsed.password or "",
            database=database,
        )

    def insert_json(self, table: str, rows: list[dict[str, Any]]) -> None:
        if not rows:
            return
        columns = list(rows[0].keys())
        data = [[row.get(column) for column in columns] for row in rows]
        self.client.insert(f"{self.database}.{table}", data, column_names=columns)

    def execute(self, sql: str) -> None:
        self.client.command(sql)

    def query(self, sql: str) -> list[dict[str, Any]]:
        result = self.client.query(sql)
        return [dict(zip(result.column_names, row)) for row in result.result_rows]

    def count(self, table: str) -> int:
        result = self.client.query(f"SELECT count() FROM {self.database}.{table}")
        return int(result.result_rows[0][0])
