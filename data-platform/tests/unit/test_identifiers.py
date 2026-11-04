# tests/unit/test_identifiers.py
import unittest

try:
    from etl.adapters.mysql_source_repository import validate_identifier
except ImportError:
    validate_identifier = None


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
