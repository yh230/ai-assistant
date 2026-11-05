from typing import Any
from urllib.parse import urlparse

_MISSING = "clickhouse-connect is not installed; install the optional 'clickhouse' dependency group"

# Every session runs on the platform clock.  Column types are pinned to
# DateTime('Asia/Shanghai') in the DDL; the session setting makes bare
# now()/today() agree, and input_format_null_as_default=1 lets an absent
# optional value fall back to the DDL default instead of rejecting the insert.
SESSION_SETTINGS = {
    "session_timezone": "Asia/Shanghai",
    "input_format_null_as_default": 1,
}


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
            settings=dict(SESSION_SETTINGS),
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
        # 质量规则等读路径使用只读会话，禁止 DDL/写入并限制执行时长；
        # readonly=2 才允许在查询级覆盖 max_execution_time（readonly=1 会拒绝 settings）
        result = self.client.query(sql, settings={"readonly": 2, "max_execution_time": 30})
        return [dict(zip(result.column_names, row)) for row in result.result_rows]

    def count(self, table: str) -> int:
        result = self.client.query(
            f"SELECT count() FROM {self.database}.{table}",
            settings={"readonly": 2, "max_execution_time": 30},
        )
        return int(result.result_rows[0][0])
