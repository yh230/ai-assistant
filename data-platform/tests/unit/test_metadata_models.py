# tests/unit/test_metadata_models.py
import unittest

try:
    from app.core.db import Base
    import app.models.metadata  # noqa: F401
except ImportError:
    Base = None


@unittest.skipIf(Base is None, "sqlalchemy not installed")
class MetadataModelTest(unittest.TestCase):
    def test_tables_registered(self):
        names = set(Base.metadata.tables)
        for name in ("dp_datasource", "dp_sync_task", "dp_sync_run", "dp_quality_rule", "dp_alert_record"):
            self.assertIn(name, names)
