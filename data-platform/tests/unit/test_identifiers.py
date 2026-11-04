# tests/unit/test_identifiers.py
import unittest
from datetime import datetime

try:
    from etl.adapters.mysql_source_repository import (
        MySQLSourceRepository,
        validate_identifier,
    )
except ImportError:
    MySQLSourceRepository = None
    validate_identifier = None


class _FakeMappings:
    def all(self):
        return []


class _FakeResult:
    def mappings(self):
        return _FakeMappings()


class _FakeConnection:
    def __init__(self, statements):
        self._statements = statements

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def execute(self, statement, params=None):
        self._statements.append((str(statement), params))
        return _FakeResult()


class _FakeEngine:
    def __init__(self):
        self.statements = []

    def connect(self):
        return _FakeConnection(self.statements)


def _repository_with_fake_engine():
    repo = MySQLSourceRepository.__new__(MySQLSourceRepository)
    repo._engine = _FakeEngine()
    return repo


@unittest.skipIf(validate_identifier is None, "sqlalchemy not installed")
class IdentifierTest(unittest.TestCase):
    def test_accepts_safe_identifier(self):
        self.assertEqual(validate_identifier("registration_db"), "registration_db")

    def test_rejects_injection(self):
        with self.assertRaises(ValueError):
            validate_identifier("registration; drop table x")

    def test_rejects_leading_digit(self):
        with self.assertRaises(ValueError):
            validate_identifier("1registration")

    def test_rejects_empty_identifier(self):
        with self.assertRaises(ValueError):
            validate_identifier("")

    def test_rejects_trailing_newline(self):
        with self.assertRaises(ValueError):
            validate_identifier("registration\n")


@unittest.skipIf(MySQLSourceRepository is None, "sqlalchemy not installed")
class StarColumnsTest(unittest.TestCase):
    def test_full_batch_accepts_star_columns(self):
        repo = _repository_with_fake_engine()
        rows = repo.fetch_full_batch("hospital_db", "hospital", ["*"], "id", None, 100)
        self.assertEqual(rows, [])
        statement, params = repo._engine.statements[0]
        self.assertIn("SELECT * FROM hospital_db.hospital", statement)
        self.assertEqual(params, {"limit": 100})

    def test_full_batch_still_validates_explicit_columns(self):
        repo = _repository_with_fake_engine()
        with self.assertRaises(ValueError):
            repo.fetch_full_batch(
                "hospital_db",
                "hospital",
                ["id", "name; DROP TABLE hospital"],
                "id",
                None,
                100,
            )
        self.assertEqual(repo._engine.statements, [])

    def test_incremental_batch_accepts_star_columns(self):
        repo = _repository_with_fake_engine()
        rows = repo.fetch_incremental_batch(
            "hospital_db",
            "hospital",
            ["*"],
            "id",
            "updated_at",
            datetime(2026, 1, 1),
            None,
            100,
        )
        self.assertEqual(rows, [])
        statement, params = repo._engine.statements[0]
        self.assertIn("SELECT * FROM hospital_db.hospital", statement)
        self.assertIn("updated_at >= :after_watermark", statement)
        self.assertEqual(params["limit"], 100)

    def test_incremental_batch_still_validates_explicit_columns(self):
        repo = _repository_with_fake_engine()
        with self.assertRaises(ValueError):
            repo.fetch_incremental_batch(
                "hospital_db",
                "hospital",
                ["*", "id"],
                "id",
                "updated_at",
                datetime(2026, 1, 1),
                None,
                100,
            )
        self.assertEqual(repo._engine.statements, [])
