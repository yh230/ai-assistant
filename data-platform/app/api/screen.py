"""Read-only dashboard endpoints and the SSE stream.

Screen aggregates are public read-only views (design section 8) and therefore
do not carry the staff authentication dependency used by the governance APIs.
Every endpoint except ``/stream`` returns the standard ``{code,message,data}``
envelope; the stream emits raw ``data: {...}\\n\\n`` frames so an EventSource can
consume the payload directly.
"""

import asyncio
import logging
from typing import Annotated, Any, Callable

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import StreamingResponse

from app.core.config import get_settings
from app.core.response import ok
from app.services.screen_service import (
    ClickHouseScreenRepository,
    ScreenService,
    format_sse,
)
from etl.adapters.factory import build_olap_store

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/screen", tags=["screen"])

_SSE_HEADERS = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    # Disable proxy buffering so each frame reaches the browser immediately.
    "X-Accel-Buffering": "no",
}
_MIN_STREAM_INTERVAL = 0.5
_MAX_STREAM_INTERVAL = 300.0
_FALLBACK_STREAM_INTERVAL = 5.0


def _service() -> ScreenService:
    settings = get_settings()
    olap = build_olap_store(
        settings.olap_store, settings.clickhouse_url, settings.clickhouse_database
    )
    repository = ClickHouseScreenRepository(
        olap,
        database=settings.clickhouse_database,
        max_trend_days=settings.screen_max_trend_days,
        max_ranking_limit=settings.screen_max_ranking_limit,
    )
    return ScreenService(repository, alert_limit=settings.screen_recent_alert_limit)


def _query(call: Callable[[], Any]) -> Any:
    try:
        return call()
    except HTTPException:
        raise
    except Exception as exc:
        # An unreachable warehouse must not be rendered as an all-zero
        # dashboard: report 503 so the screen can show a degraded state.
        logger.exception("screen query failed")
        raise HTTPException(
            status_code=503, detail=f"screen query failed: {exc}"
        ) from exc


def _window(value: int | None, default: int, maximum: int) -> int:
    window = default if value is None else value
    return max(1, min(int(window), max(1, int(maximum))))


def _stream_interval(value: float) -> float:
    try:
        seconds = float(value)
    except (TypeError, ValueError):
        return _FALLBACK_STREAM_INTERVAL
    if not (seconds > 0):
        return _FALLBACK_STREAM_INTERVAL
    return max(_MIN_STREAM_INTERVAL, min(seconds, _MAX_STREAM_INTERVAL))


@router.get("/overview")
def overview():
    return ok(_query(lambda: _service().overview()))


@router.get("/trend")
def trend(days: Annotated[int | None, Query(ge=1)] = None):
    settings = get_settings()
    window = _window(days, settings.screen_trend_days, settings.screen_max_trend_days)
    return ok(_query(lambda: _service().trend(window)))


@router.get("/ranking")
def ranking(limit: Annotated[int | None, Query(ge=1)] = None):
    settings = get_settings()
    bounded = _window(
        limit, settings.screen_ranking_limit, settings.screen_max_ranking_limit
    )
    return ok(_query(lambda: _service().ranking(bounded)))


@router.get("/quality")
def quality():
    return ok(_query(lambda: _service().quality()))


@router.get("/stream")
async def stream():
    settings = get_settings()
    interval = _stream_interval(settings.screen_stream_interval_seconds)

    async def events():
        # Build once and reuse: a new ClickHouse client per frame would leak
        # connections.  A failed build/query clears the cache so the next tick
        # can recover without terminating the stream.
        service: ScreenService | None = None
        try:
            while True:
                try:
                    if service is None:
                        service = await asyncio.to_thread(_service)
                    # ClickHouse access is blocking; keep it off the event loop.
                    payload = await asyncio.to_thread(service.overview)
                except asyncio.CancelledError:
                    raise
                except Exception as exc:
                    service = None
                    logger.warning("screen stream query failed: %s", exc)
                    yield format_sse({"error": "screen query failed"})
                else:
                    yield format_sse(payload)
                await asyncio.sleep(interval)
        except asyncio.CancelledError:
            # Client disconnect: log it and let the cancellation propagate so
            # Starlette can close the response and release the generator.
            logger.info("screen stream client disconnected")
            raise
        finally:
            logger.debug("screen stream closed")

    return StreamingResponse(
        events(), media_type="text/event-stream", headers=_SSE_HEADERS
    )
