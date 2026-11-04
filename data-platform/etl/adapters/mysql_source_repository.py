import re
from datetime import datetime
from typing import Any

from sqlalchemy import create_engine, text

from etl.ports.source_repository import SourceColumn, SourceTable

_IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def validate_identifier(value: str) -> str:
    if not isinstance(value, str) or not _IDENT.fullmatch(value):
        raise ValueError(f"invalid identifier: {value}")
    return value


class MySQLSourceRepository:
    """Read-only MySQL adapter.

    Identifiers cannot be bound as SQL parameters, so every schema, table,
    column and primary-key name is validated against ``_IDENT`` before it is
    interpolated into the statement; values remain bound parameters.
    """

    def __init__(self, url: str):
        self._engine = create_engine(url, pool_pre_ping=True)

    def test_connection(self) -> bool:
        with self._engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        return True

    def list_tables(self, schema: str) -> list[SourceTable]:
        schema = validate_identifier(schema)
        sql = text(
            "SELECT table_name, table_comment FROM information_schema.tables "
            "WHERE table_schema = :schema AND table_type = 'BASE TABLE' ORDER BY table_name"
        )
        with self._engine.connect() as conn:
            rows = conn.execute(sql, {"schema": schema}).mappings().all()
        return [
            SourceTable(
                schema=schema,
                name=row["table_name"],
                comment=row["table_comment"] or "",
            )
            for row in rows
        ]

    def list_columns(self, schema: str, table: str) -> list[SourceColumn]:
        schema = validate_identifier(schema)
        table = validate_identifier(table)
        sql = text(
            "SELECT column_name, column_type, is_nullable, column_comment, ordinal_position "
            "FROM information_schema.columns WHERE table_schema = :schema AND table_name = :table "
            "ORDER BY ordinal_position"
        )
        with self._engine.connect() as conn:
            rows = conn.execute(sql, {"schema": schema, "table": table}).mappings().all()
        return [
            SourceColumn(
                name=row["column_name"],
                data_type=row["column_type"],
                nullable=row["is_nullable"] == "YES",
                comment=row["column_comment"] or "",
                ordinal=int(row["ordinal_position"]),
            )
            for row in rows
        ]

    def fetch_full_batch(
        self,
        schema: str,
        table: str,
        columns: list[str],
        primary_key: str,
        after_pk: int | None,
        limit: int,
    ) -> list[dict[str, Any]]:
        schema = validate_identifier(schema)
        table = validate_identifier(table)
        primary_key = validate_identifier(primary_key)
        column_sql = ", ".join(validate_identifier(column) for column in columns)
        where = f"WHERE {primary_key} > :after_pk" if after_pk is not None else ""
        sql = text(
            f"SELECT {column_sql} FROM {schema}.{table} {where} "
            f"ORDER BY {primary_key} LIMIT :limit"
        )
        params: dict[str, Any] = {"limit": limit}
        if after_pk is not None:
            params["after_pk"] = after_pk
        with self._engine.connect() as conn:
            rows = conn.execute(sql, params).mappings().all()
        return [dict(row) for row in rows]

    def fetch_incremental_batch(
        self,
        schema: str,
        table: str,
        columns: list[str],
        primary_key: str,
        watermark_column: str,
        after_watermark: datetime,
        after_pk: int | None,
        limit: int,
    ) -> list[dict[str, Any]]:
        schema = validate_identifier(schema)
        table = validate_identifier(table)
        primary_key = validate_identifier(primary_key)
        watermark_column = validate_identifier(watermark_column)
        column_sql = ", ".join(validate_identifier(column) for column in columns)
        if after_pk is None:
            where = f"WHERE {watermark_column} >= :after_watermark"
        else:
            where = (
                f"WHERE ({watermark_column}, {primary_key}) "
                "> (:after_watermark, :after_pk)"
            )
        sql = text(
            f"SELECT {column_sql} FROM {schema}.{table} {where} "
            f"ORDER BY {watermark_column}, {primary_key} LIMIT :limit"
        )
        params: dict[str, Any] = {"after_watermark": after_watermark, "limit": limit}
        if after_pk is not None:
            params["after_pk"] = after_pk
        with self._engine.connect() as conn:
            rows = conn.execute(sql, params).mappings().all()
        return [dict(row) for row in rows]

    def max_watermark(
        self, schema: str, table: str, watermark_column: str
    ) -> datetime | None:
        schema = validate_identifier(schema)
        table = validate_identifier(table)
        watermark_column = validate_identifier(watermark_column)
        sql = text(f"SELECT MAX({watermark_column}) AS max_value FROM {schema}.{table}")
        with self._engine.connect() as conn:
            value = conn.execute(sql).scalar()
        return value
