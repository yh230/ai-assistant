from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

import app.models.metadata  # noqa: F401
from app.api import datasources, metadata, sync_tasks
from app.core.db import Base, engine
from app.core.exceptions import DataPlatformError
from app.core.response import fail, ok


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


def create_app() -> FastAPI:
    app = FastAPI(title="data-platform")
    Base.metadata.create_all(bind=engine)

    app.add_exception_handler(HTTPException, http_exception_handler)
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(DataPlatformError, data_platform_exception_handler)

    @app.get("/api/health")
    def health():
        return ok({"status": "ok"})

    app.include_router(datasources.router, prefix="/api")
    app.include_router(metadata.router, prefix="/api")
    app.include_router(sync_tasks.router, prefix="/api")

    return app


app = create_app()
