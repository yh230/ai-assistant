"""Response shapes for the read-only dashboard (screen) API.

The dashboard handlers return plain dictionaries built by ``ScreenService``;
these ``TypedDict`` definitions document the contract without adding request
bodies or per-endpoint Pydantic validation.  Every screen endpoint except
``/api/screen/stream`` wraps its payload in the ``{code,message,data}``
envelope; the SSE stream emits the raw overview payload as ``data: ...``
frames.
"""

from typing import Any, NotRequired, TypedDict


class KpiItem(TypedDict):
    key: str
    label: str
    value: float | int


class ScreenOverview(TypedDict):
    kpis: list[KpiItem]
    raw: dict[str, Any]


class TrendPoint(TypedDict):
    date: Any
    value: float | int


class RankingEntry(TypedDict):
    name: NotRequired[str | None]
    value: NotRequired[float | int]
    hospital_id: NotRequired[int]
    department_id: NotRequired[int]


class ScreenRanking(TypedDict):
    hospitals: list[RankingEntry]
    departments: list[RankingEntry]


class ScreenHealth(TypedDict):
    quality_score: float | int
    lag_seconds: float | int
    open_alerts: float | int


class QualityAlert(TypedDict):
    severity: NotRequired[str]
    title: NotRequired[str]
    checked_at: NotRequired[Any]
    rule_id: NotRequired[int]
    pass_rate: NotRequired[float]


class ScreenQuality(TypedDict):
    health: ScreenHealth
    alerts: list[QualityAlert]
