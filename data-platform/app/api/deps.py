from fastapi import Depends, Header, HTTPException
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.core.db import get_db


def get_session(db: Session = Depends(get_db)) -> Session:
    return db


def require_staff(authorization: str | None = Header(default=None)) -> dict:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="missing token")
    settings = get_settings()
    if not settings.jwt_secret:
        raise HTTPException(status_code=500, detail="JWT secret not configured")
    token = authorization.removeprefix("Bearer ").strip()
    try:
        import jwt

        payload = jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
    except Exception as exc:
        raise HTTPException(status_code=401, detail="invalid token") from exc
    if payload.get("identity") != "staff":
        raise HTTPException(status_code=403, detail="staff identity required")
    return payload
