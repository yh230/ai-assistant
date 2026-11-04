from fastapi import FastAPI

import app.models.metadata  # noqa: F401
from app.api import datasources, metadata
from app.core.db import Base, engine
from app.core.response import ok


def create_app() -> FastAPI:
    app = FastAPI(title="data-platform")
    Base.metadata.create_all(bind=engine)

    @app.get("/api/health")
    def health():
        return ok({"status": "ok"})

    app.include_router(datasources.router, prefix="/api")
    app.include_router(metadata.router, prefix="/api")

    return app


app = create_app()
