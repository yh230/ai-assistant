from dataclasses import dataclass
from datetime import datetime
from typing import Any, Protocol


@dataclass(frozen=True)
class SourceTable:
    schema: str
    name: str
    comment: str


@dataclass(frozen=True)
class SourceColumn:
    name: str
    data_type: str
    nullable: bool
    comment: str
    ordinal: int


class SourceRepository(Protocol):
    def test_connection(self) -> bool: ...

    def list_tables(self, schema: str) -> list[SourceTable]: ...

    def list_columns(self, schema: str, table: str) -> list[SourceColumn]: ...

    def fetch_full_batch(
        self,
        schema: str,
        table: str,
        columns: list[str],
        primary_key: str,
        after_pk: int | None,
        limit: int,
    ) -> list[dict[str, Any]]: ...

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
    ) -> list[dict[str, Any]]: ...

    def max_watermark(
        self, schema: str, table: str, watermark_column: str
    ) -> datetime | None: ...
