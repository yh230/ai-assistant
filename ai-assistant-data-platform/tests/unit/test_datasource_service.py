# tests/unit/test_datasource_service.py
import importlib.util
import unittest

HAS_STACK = all(
    importlib.util.find_spec(name) is not None
    for name in ("sqlalchemy", "pydantic_settings", "cryptography")
)


class FakeSourceRepository:
    def __init__(self, columns, tables=None):
        self.columns = columns
        self.tables = tables or []
        self.calls = []
        self.table_calls = []

    def list_tables(self, schema):
        self.table_calls.append(schema)
        return list(self.tables)

    def list_columns(self, schema, table):
        self.calls.append((schema, table))
        return list(self.columns)


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class DatasourceServiceTest(unittest.TestCase):
    def setUp(self):
        from sqlalchemy import create_engine
        from sqlalchemy.orm import sessionmaker

        import app.models.metadata  # noqa: F401
        from app.core.crypto import generate_key
        from app.core.db import Base
        from app.services.datasource_service import DatasourceService

        self.engine = create_engine("sqlite:///:memory:")
        Base.metadata.create_all(self.engine)
        self.db = sessionmaker(bind=self.engine, expire_on_commit=False)()
        self.secret_key = generate_key()
        self.service = DatasourceService(self.db, self.secret_key)

    def tearDown(self):
        self.db.close()
        self.engine.dispose()

    def _create(self, name="hospital", password="secret"):
        return self.service.create(
            name=name,
            host="127.0.0.1",
            port=3306,
            database_name="hospital_db",
            username="readonly",
            password=password,
        )

    def test_create_encrypts_password(self):
        from app.models.metadata import DataSource

        entity = self._create()
        self.assertTrue(entity.password_cipher)
        self.assertNotIn("secret", entity.password_cipher)
        stored = self.db.get(DataSource, entity.id)
        self.assertNotEqual(stored.password_cipher, "secret")

    def test_url_for_renders_decrypted_password(self):
        entity = self._create()
        self.assertEqual(
            self.service.url_for(entity),
            "mysql+pymysql://readonly:secret@127.0.0.1:3306/hospital_db",
        )

    def test_update_only_reencrypts_when_password_given(self):
        entity = self._create()
        original_cipher = entity.password_cipher

        self.service.update(
            entity,
            name="hospital-renamed",
            host="10.0.0.8",
            port=3307,
            database_name="hospital_db_2",
            username="reader",
        )
        self.assertEqual(entity.password_cipher, original_cipher)
        self.assertEqual(entity.host, "10.0.0.8")
        self.assertEqual(entity.port, 3307)

        self.service.update(
            entity,
            name="hospital-renamed",
            host="10.0.0.8",
            port=3307,
            database_name="hospital_db_2",
            username="reader",
            password="next-secret",
        )
        self.assertNotEqual(entity.password_cipher, original_cipher)
        self.assertEqual(self.service.box.decrypt(entity.password_cipher), "next-secret")

    def test_update_without_password_does_not_need_secret_key(self):
        from app.services.datasource_service import DatasourceService

        entity = self._create()
        original_cipher = entity.password_cipher
        service = DatasourceService(self.db, "")

        service.update(
            entity,
            name="hospital-lazy",
            host="127.0.0.1",
            port=3306,
            database_name="hospital_db",
            username="readonly",
            password=None,
        )
        self.assertEqual(entity.password_cipher, original_cipher)
        self.assertEqual(entity.name, "hospital-lazy")
        with self.assertRaises(ValueError):
            service.box

    def test_delete_removes_datasource_metadata_and_entity(self):
        from app.models.metadata import ColumnMeta, DataSource, TableMeta

        entity = self._create()
        table_meta = TableMeta(
            datasource_id=entity.id,
            table_schema="hospital_db",
            table_name="hospital",
            table_comment="hospital master",
        )
        self.db.add(table_meta)
        self.db.flush()
        column_meta = ColumnMeta(
            table_meta_id=table_meta.id,
            column_name="id",
            data_type="bigint",
            nullable=False,
            column_comment="",
            ordinal=1,
        )
        self.db.add(column_meta)
        self.db.commit()
        entity_id = entity.id
        table_id = table_meta.id
        column_id = column_meta.id

        self.service.delete(entity)

        self.assertIsNone(self.db.get(DataSource, entity_id))
        self.assertIsNone(self.db.get(TableMeta, table_id))
        self.assertIsNone(self.db.get(ColumnMeta, column_id))

    def test_introspect_persists_columns_and_is_idempotent(self):
        from app.models.metadata import ColumnMeta, TableMeta
        from app.services.datasource_service import DatasourceService
        from etl.ports.source_repository import SourceColumn

        entity = self._create()
        fake = FakeSourceRepository(
            [
                SourceColumn(
                    name="id", data_type="bigint", nullable=False, comment="pk", ordinal=1
                ),
                SourceColumn(
                    name="name",
                    data_type="varchar(64)",
                    nullable=False,
                    comment="",
                    ordinal=2,
                ),
            ]
        )

        class FakeRepoService(DatasourceService):
            def _repo(self, datasource):
                return fake

        service = FakeRepoService(self.db, self.secret_key)
        self.assertEqual(service.introspect(entity, "hospital_db", "hospital"), 2)
        self.assertEqual(self.db.query(TableMeta).count(), 1)
        self.assertEqual(self.db.query(ColumnMeta).count(), 2)

        fake.columns = [
            SourceColumn(
                name="id", data_type="bigint", nullable=False, comment="pk", ordinal=1
            ),
            SourceColumn(
                name="name",
                data_type="varchar(64)",
                nullable=False,
                comment="",
                ordinal=2,
            ),
            SourceColumn(
                name="address",
                data_type="varchar(255)",
                nullable=True,
                comment="",
                ordinal=3,
            ),
        ]
        self.assertEqual(service.introspect(entity, "hospital_db", "hospital"), 3)
        self.assertEqual(self.db.query(TableMeta).count(), 1)
        self.assertEqual(self.db.query(ColumnMeta).count(), 3)
        self.assertEqual(fake.calls, [("hospital_db", "hospital"), ("hospital_db", "hospital")])

    def test_introspect_populates_table_comment(self):
        from app.models.metadata import TableMeta
        from app.services.datasource_service import DatasourceService
        from etl.ports.source_repository import SourceColumn, SourceTable

        entity = self._create()
        fake = FakeSourceRepository(
            [
                SourceColumn(
                    name="id", data_type="bigint", nullable=False, comment="", ordinal=1
                )
            ],
            tables=[
                SourceTable(schema="hospital_db", name="other", comment="ignored"),
                SourceTable(schema="hospital_db", name="hospital", comment="hospital master"),
            ],
        )

        class FakeRepoService(DatasourceService):
            def _repo(self, datasource):
                return fake

        service = FakeRepoService(self.db, self.secret_key)
        service.introspect(entity, "hospital_db", "hospital")
        table_meta = self.db.query(TableMeta).one()
        self.assertEqual(table_meta.table_comment, "hospital master")
        self.assertEqual(fake.table_calls, ["hospital_db"])

        fake.tables = [
            SourceTable(schema="hospital_db", name="hospital", comment="renamed comment")
        ]
        service.introspect(entity, "hospital_db", "hospital")
        self.db.expire_all()
        table_meta = self.db.query(TableMeta).one()
        self.assertEqual(table_meta.table_comment, "renamed comment")

    def test_introspect_maps_invalid_identifier_to_http_400(self):
        from app.core.exceptions import DataPlatformError
        from app.models.metadata import TableMeta
        from app.services.datasource_service import DatasourceService

        class BadSchemaRepository(FakeSourceRepository):
            def list_tables(self, schema):
                raise ValueError(f"invalid identifier: {schema}")

        fake = BadSchemaRepository([])

        class FakeRepoService(DatasourceService):
            def _repo(self, datasource):
                return fake

        entity = self._create()
        service = FakeRepoService(self.db, self.secret_key)
        with self.assertRaises(DataPlatformError) as ctx:
            service.introspect(entity, "hospital; DROP TABLE x", "hospital")
        self.assertEqual(ctx.exception.code, 400)
        self.assertEqual(self.db.query(TableMeta).count(), 0)

    def test_test_connection_returns_false_on_database_error(self):
        from sqlalchemy.exc import OperationalError

        from app.services.datasource_service import DatasourceService

        class UnreachableRepository:
            def test_connection(self):
                raise OperationalError("SELECT 1", {}, Exception("connection refused"))

        class FakeRepoService(DatasourceService):
            def _repo(self, datasource):
                return UnreachableRepository()

        entity = self._create()
        service = FakeRepoService(self.db, self.secret_key)
        self.assertFalse(service.test_connection(entity))
        self.assertIsNotNone(entity.last_test_at)
        self.assertEqual(entity.status, 0)

    def test_test_connection_marks_a_reachable_datasource(self):
        from app.services.datasource_service import DatasourceService

        class ReachableRepository:
            def test_connection(self):
                return True

        class FakeRepoService(DatasourceService):
            def _repo(self, datasource):
                return ReachableRepository()

        entity = self._create()
        entity.status = 0
        self.db.commit()
        service = FakeRepoService(self.db, self.secret_key)

        self.assertTrue(service.test_connection(entity))
        self.assertEqual(entity.status, 1)
        self.assertIsNotNone(entity.last_test_at)

    def test_test_connection_does_not_swallow_programming_errors(self):
        from app.services.datasource_service import DatasourceService

        class BrokenRepository:
            def test_connection(self):
                raise RuntimeError("programming error")

        class FakeRepoService(DatasourceService):
            def _repo(self, datasource):
                return BrokenRepository()

        entity = self._create()
        service = FakeRepoService(self.db, self.secret_key)
        with self.assertRaises(RuntimeError):
            service.test_connection(entity)

    def test_test_connection_maps_value_error_to_http_400(self):
        from app.core.exceptions import DataPlatformError
        from app.services.datasource_service import DatasourceService

        class InvalidRepository:
            def test_connection(self):
                raise ValueError("invalid identifier: hospital; DROP TABLE x")

        class FakeRepoService(DatasourceService):
            def _repo(self, datasource):
                return InvalidRepository()

        entity = self._create()
        service = FakeRepoService(self.db, self.secret_key)
        with self.assertRaises(DataPlatformError) as ctx:
            service.test_connection(entity)
        self.assertEqual(ctx.exception.code, 400)
