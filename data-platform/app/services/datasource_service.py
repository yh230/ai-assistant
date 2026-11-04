from datetime import datetime

from sqlalchemy.engine import URL
from sqlalchemy.orm import Session

from app.core.crypto import SecretBox
from app.models.metadata import ColumnMeta, DataSource, TableMeta
from etl.adapters.mysql_source_repository import MySQLSourceRepository


class DatasourceService:
    def __init__(self, db: Session, secret_key: str):
        self.db = db
        self.box = SecretBox(secret_key)

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
        self.db.delete(entity)
        self.db.commit()

    def test_connection(self, entity: DataSource) -> bool:
        repo = self._repo(entity)
        connected = repo.test_connection()
        entity.last_test_at = datetime.utcnow()
        self.db.commit()
        return connected

    def introspect(self, entity: DataSource, schema: str, table: str) -> int:
        repo = self._repo(entity)
        columns = repo.list_columns(schema, table)
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
            )
            self.db.add(table_meta)
            self.db.flush()
        else:
            # Re-introspection replaces the previous column snapshot instead of
            # tripping the (table_meta_id, column_name) unique constraint.
            self.db.query(ColumnMeta).filter(
                ColumnMeta.table_meta_id == table_meta.id
            ).delete()
            table_meta.last_introspected_at = datetime.utcnow()
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
