from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import get_session, require_staff
from app.core.config import get_settings
from app.core.response import ok
from app.demo import DEMO_CONNECTION_MESSAGE
from app.models.metadata import DataSource
from app.schemas.datasource import DatasourceCreate, DatasourceUpdate, IntrospectRequest
from app.services.datasource_service import DatasourceService

router = APIRouter(
    prefix="/datasources",
    tags=["datasources"],
    dependencies=[Depends(require_staff)],
)


def _service(db: Session) -> DatasourceService:
    return DatasourceService(db, get_settings().dp_secret_key)


def _get_entity(db: Session, datasource_id: int) -> DataSource:
    entity = db.get(DataSource, datasource_id)
    if entity is None:
        raise HTTPException(status_code=404, detail="datasource not found")
    return entity


def _duplicate_name() -> HTTPException:
    return HTTPException(status_code=409, detail="datasource name already exists")


@router.post("")
def create(payload: DatasourceCreate, db: Session = Depends(get_session)):
    try:
        entity = _service(db).create(
            name=payload.name,
            host=payload.host,
            port=payload.port,
            database_name=payload.database_name,
            username=payload.username,
            password=payload.password,
        )
    except IntegrityError as exc:
        db.rollback()
        raise _duplicate_name() from exc
    except ValueError as exc:
        # Missing DP_SECRET_KEY surfaces here when the password is encrypted.
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return ok({"id": entity.id, "name": entity.name})


@router.get("")
def list_datasources(db: Session = Depends(get_session)):
    rows = db.query(DataSource).order_by(DataSource.id.desc()).all()
    return ok(
        [
            {
                "id": row.id,
                "name": row.name,
                "host": row.host,
                "database_name": row.database_name,
                "status": row.status,
            }
            for row in rows
        ]
    )


@router.put("/{datasource_id}")
def update(
    datasource_id: int,
    payload: DatasourceUpdate,
    db: Session = Depends(get_session),
):
    entity = _get_entity(db, datasource_id)
    try:
        entity = _service(db).update(
            entity,
            name=payload.name,
            host=payload.host,
            port=payload.port,
            database_name=payload.database_name,
            username=payload.username,
            password=payload.password,
        )
    except IntegrityError as exc:
        db.rollback()
        raise _duplicate_name() from exc
    except ValueError as exc:
        # Missing DP_SECRET_KEY surfaces here when the password is encrypted.
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return ok({"id": entity.id, "name": entity.name})


@router.delete("/{datasource_id}")
def delete(datasource_id: int, db: Session = Depends(get_session)):
    entity = _get_entity(db, datasource_id)
    _service(db).delete(entity)
    return ok({"deleted": datasource_id})


@router.post("/{datasource_id}/test")
def test_connection(datasource_id: int, db: Session = Depends(get_session)):
    entity = _get_entity(db, datasource_id)
    if get_settings().demo_mode:
        return ok({"connected": False, "demo": True, "message": DEMO_CONNECTION_MESSAGE})
    try:
        connected = _service(db).test_connection(entity)
    except ValueError as exc:
        # Missing DP_SECRET_KEY surfaces here when the URL is decrypted.
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return ok({"connected": connected})


@router.post("/{datasource_id}/introspect")
def introspect(
    datasource_id: int,
    payload: IntrospectRequest,
    db: Session = Depends(get_session),
):
    entity = _get_entity(db, datasource_id)
    if get_settings().demo_mode:
        return ok({"columns": 0, "demo": True, "message": DEMO_CONNECTION_MESSAGE})
    try:
        count = _service(db).introspect(entity, payload.schema_name, payload.table)
    except ValueError as exc:
        # Missing DP_SECRET_KEY surfaces here when the URL is decrypted.
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return ok({"columns": count})
