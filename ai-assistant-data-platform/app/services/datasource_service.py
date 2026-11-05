from sqlalchemy.engine import URL
from sqlalchemy.exc import InterfaceError, OperationalError
from sqlalchemy.orm import Session

from app.core.crypto import SecretBox
from app.core.exceptions import DataPlatformError
from app.core.time import now_shanghai
from app.models.metadata import ColumnMeta, DataSource, TableMeta
from etl.adapters.mysql_source_repository import MySQLSourceRepository


class DatasourceService:
    def __init__(self, db: Session, secret_key: str):
        self.db = db
        self._secret_key = secret_key
        self._box: SecretBox | None = None

    @property
    def box(self) -> SecretBox:
        # Build the cipher only when a password actually needs to be
        # encrypted/decrypted, so password-less updates work without a key.
        if self._box is None:
            self._box = SecretBox(self._secret_key)
        return self._box

    def create(
        self,
        name: str,
        host: str,
        port: int,
        database_name: str,
        username: str,
        password: str,
    ) -> DataSource:
        entity = DataSource(
            name=name,
            host=host,
            port=port,
            database_name=database_name,
            username=username,
            password_cipher=self.box.encrypt(password),
        )
        self.db.add(entity)
        self.db.commit()
        self.db.refresh(entity)
        return entity

    def update(
        self,
        entity: DataSource,
        *,
        name: str,
        host: str,
        port: int,
        database_name: str,
        username: str,
        password: str | None = None,
    ) -> DataSource:
        entity.name = name
        entity.host = host
        entity.port = port
        entity.database_name = database_name
        entity.username = username
        if password:
            entity.password_cipher = self.box.encrypt(password)
        self.db.commit()
        self.db.refresh(entity)
        return entity

    def delete(self, entity: DataSource) -> None:
        # There is no FK cascade on the metadata tables, so remove the
        # datasource's table/column snapshots explicitly before the row.
        tables = (
            self.db.query(TableMeta)
            .filter(TableMeta.datasource_id == entity.id)
            .all()
        )
        table_ids = [table.id for table in tables]
        if table_ids:
            self.db.query(ColumnMeta).filter(
                ColumnMeta.table_meta_id.in_(table_ids)
            ).delete(synchronize_session="fetch")
            self.db.query(TableMeta).filter(
                TableMeta.id.in_(table_ids)
            ).delete(synchronize_session="fetch")
        self.db.delete(entity)
        self.db.commit()

    def test_connection(self, entity: DataSource) -> bool:
        repo = self._repo(entity)
        try:
            connected = repo.test_connection()
        except (OperationalError, InterfaceError):
            # Network/authentication failures mean "not connected"; other
            # programming errors still propagate.
            connected = False
        except ValueError as exc:
            raise DataPlatformError(400, str(exc)) from exc
        # The last connection test is the source of truth for the status the
        # governance UI displays (1 = reachable, 0 = unreachable).
        entity.status = 1 if connected else 0
        entity.last_test_at = now_shanghai()
        self.db.commit()
        return connected

    def introspect(self, entity: DataSource, schema: str, table: str) -> int:
        repo = self._repo(entity)
        try:
            table_comment = ""
            for source_table in repo.list_tables(schema):
                if source_table.name == table:
                    table_comment = source_table.comment
                    break
            columns = repo.list_columns(schema, table)
        except ValueError as exc:
            # Invalid identifiers come from user input and map to HTTP 400.
            raise DataPlatformError(400, str(exc)) from exc
        table_meta = (
            self.db.query(TableMeta)
            .filter(
                TableMeta.datasource_id == entity.id,
                TableMeta.table_schema == schema,
                TableMeta.table_name == table,
            )
            .one_or_none()
        )
        if table_meta is None:
            table_meta = TableMeta(
                datasource_id=entity.id,
                table_schema=schema,
                table_name=table,
                table_comment=table_comment,
            )
            self.db.add(table_meta)
            self.db.flush()
        else:
            # Re-introspection replaces the previous column snapshot instead of
            # tripping the (table_meta_id, column_name) unique constraint.
            self.db.query(ColumnMeta).filter(
                ColumnMeta.table_meta_id == table_meta.id
            ).delete()
            table_meta.table_comment = table_comment
            table_meta.last_introspected_at = now_shanghai()
        for column in columns:
            self.db.add(
                ColumnMeta(
                    table_meta_id=table_meta.id,
                    column_name=column.name,
                    data_type=column.data_type,
                    nullable=column.nullable,
                    column_comment=column.comment,
                    ordinal=column.ordinal,
                )
            )
        self.db.commit()
        return len(columns)

    def _repo(self, entity: DataSource) -> MySQLSourceRepository:
        return MySQLSourceRepository(self.url_for(entity))

    def url_for(self, entity: DataSource) -> str:
        password = self.box.decrypt(entity.password_cipher)
        return URL.create(
            "mysql+pymysql",
            username=entity.username,
            password=password,
            host=entity.host,
            port=entity.port,
            database=entity.database_name,
        ).render_as_string(hide_password=False)
