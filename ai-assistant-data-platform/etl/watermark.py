from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any


def lower_bound(last_watermark: datetime | None, overlap_seconds: int) -> datetime | None:
    """Datetime watermark rewound by the overlap window.

    The overlap re-reads rows committed around the previous watermark, so a
    transaction that committed late is not skipped.  Deduplication is handled
    downstream by ``eventId`` and ClickHouse ``ReplacingMergeTree``.
    """
    if last_watermark is None:
        return None
    return last_watermark - timedelta(seconds=overlap_seconds)


def advance_watermark(current: datetime | None, candidate: datetime | None) -> datetime | None:
    if candidate is None:
        return current
    if current is None or candidate > current:
        return candidate
    return current


def to_decimal(value: Any) -> Decimal | None:
    """Normalise an integer/float/string/Decimal watermark to ``Decimal``.

    Numeric watermarks are persisted in ``Numeric(20, 0)``; converting through
    ``str`` avoids the binary float representation of ``Decimal(float)``.
    """
    if value is None:
        return None
    if isinstance(value, Decimal):
        return value
    return Decimal(str(value))


def advance_numeric(current: Any, candidate: Any) -> Decimal | None:
    """Monotonic numeric watermark with no time overlap.

    Numeric watermarks identify rows exactly, so there is no overlap window:
    the next run resumes from the highest value already extracted.
    """
    current_num = to_decimal(current)
    candidate_num = to_decimal(candidate)
    if candidate_num is None:
        return current_num
    if current_num is None or candidate_num > current_num:
        return candidate_num
    return current_num
