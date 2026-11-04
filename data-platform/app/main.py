from fastapi import FastAPI

from app.core.db import Base, engine
from app.core.response import ok
import app.models.metadata  # noqa: F401


def create_app() -> FastAPI:
    app = FastAPI(title="data-platform")
    Base.metadata.create_all(bind=engine)

    @app.get("/api/health")
    def health():
        return ok({"status": "ok"})

    return app


app = create_app()
