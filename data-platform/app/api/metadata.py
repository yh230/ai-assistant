from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_session, require_staff
from app.core.response import ok
from app.models.metadata import ColumnMeta, TableMeta

router = APIRouter(
    prefix="/metadata",
    tags=["metadata"],
    dependencies=[Depends(require_staff)],
)


@router.get("/tables")
def list_tables(db: Session = Depends(get_session)):
    rows = db.query(TableMeta).order_by(TableMeta.id.desc()).all()
    return ok(
        [
            {
                "id": row.id,
                "schema": row.table_schema,
                "table": row.table_name,
                "comment": row.table_comment,
            }
            for row in rows
        ]
    )


@router.get("/tables/{table_id}/columns")
def list_columns(table_id: int, db: Session = Depends(get_session)):
    rows = (
        db.query(ColumnMeta)
        .filter(ColumnMeta.table_meta_id == table_id)
        .order_by(ColumnMeta.ordinal)
        .all()
    )
    return ok(
        [
            {
                "name": row.column_name,
                "data_type": row.data_type,
                "nullable": row.nullable,
                "comment": row.column_comment,
            }
            for row in rows
        ]
    )
