# tests/unit/test_datasource_service.py
import importlib.util
import unittest

HAS_STACK = all(
    importlib.util.find_spec(name) is not None
    for name in ("sqlalchemy", "pydantic_settings", "cryptography")
)


class FakeSourceRepository:
    def __init__(self, columns):
        self.columns = columns
        self.calls = []

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

    def test_delete_removes_entity(self):
        from app.models.metadata import DataSource

        entity = self._create()
        entity_id = entity.id
        self.service.delete(entity)
        self.assertIsNone(self.db.get(DataSource, entity_id))

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
