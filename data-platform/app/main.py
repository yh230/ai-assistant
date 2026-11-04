import logging
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

import app.models.metadata  # noqa: F401
from app.api import alerts, datasources, metadata, quality, screen, sync_tasks
from app.core.config import get_settings
from app.core.db import Base, engine
from app.core.exceptions import DataPlatformError
from app.core.response import fail, ok

logger = logging.getLogger(__name__)


def _message(detail: Any) -> str:
    return detail if isinstance(detail, str) else str(detail)


async def http_exception_handler(
    request: Request, exc: StarletteHTTPException
) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status_code,
        content=fail(exc.status_code, _message(exc.detail), None),
        headers=getattr(exc, "headers", None),
    )


async def validation_exception_handler(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    return JSONResponse(
        status_code=422,
        content=fail(422, "request validation failed", jsonable_encoder(exc.errors())),
    )


async def data_platform_exception_handler(
    request: Request, exc: DataPlatformError
) -> JSONResponse:
    return JSONResponse(
        status_code=exc.code,
        content=fail(exc.code, exc.message, None),
    )


async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Last-resort envelope: never leak a stack trace to the caller.

    The handler runs inside Starlette's error middleware except block, so
    ``logger.exception`` records the traceback server-side while the response
    carries only a generic message.
    """
    logger.exception("unhandled error on %s %s", request.method, request.url.path)
    return JSONResponse(
        status_code=500,
        content=fail(500, "internal error", None),
    )


def create_app() -> FastAPI:
    app = FastAPI(title="data-platform")
    Base.metadata.create_all(bind=engine)

    app.add_exception_handler(HTTPException, http_exception_handler)
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(DataPlatformError, data_platform_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)

    @app.get("/api/health")
    def health():
        return ok({"status": "ok"})

    app.include_router(datasources.router, prefix="/api")
    app.include_router(metadata.router, prefix="/api")
    app.include_router(sync_tasks.router, prefix="/api")
    app.include_router(sync_tasks.runs_router, prefix="/api")
    app.include_router(quality.router, prefix="/api")
    app.include_router(alerts.router, prefix="/api")
    app.include_router(screen.router, prefix="/api")

    if get_settings().dp_enable_scheduler:
        # Opt-in background scheduler; the default deployment stays API-only.
        from app.scheduler import start_scheduler

        start_scheduler()

    return app


app = create_app()
