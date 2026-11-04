# tests/unit/test_quality_service.py
import importlib.util
import unittest
from datetime import date, datetime
from types import SimpleNamespace

HAS_STACK = all(
    importlib.util.find_spec(name) is not None
    for name in ("sqlalchemy", "pydantic_settings")
)


class FakeQuery:
    def __init__(self, rows):
        self.rows = rows
        self.filters = []
        self.deleted = False

    def filter(self, *criteria):
        self.filters.append(criteria)
        return self

    def delete(self, synchronize_session=None):
        self.deleted = True
        return len(self.rows)


class FakeDb:
    def __init__(self, rules=None, results=None):
        self.rules = rules or {}
        self.results = results or []
        self.added = []
        self.deleted = []
        self.commits = 0
        self.rolled_back = False
        self.rule_query = FakeQuery(list(self.rules.values()))
        self.result_query = FakeQuery(self.results)
        self._next_id = 100

    def add(self, obj):
        self.added.append(obj)
        if getattr(obj, "id", None) is None:
            obj.id = self._next_id
            self._next_id += 1

    def commit(self):
        self.commits += 1

    def refresh(self, obj):
        return None

    def rollback(self):
        self.rolled_back = True

    def delete(self, obj):
        self.deleted.append(obj)

    def get(self, model, key):
        return self.rules.get(key)

    def query(self, model):
        from app.models.metadata import DpQualityResult

        if model is DpQualityResult:
            return self.result_query
        return self.rule_query


class FakeOlap:
    def __init__(self, rows=None, query_error=None, insert_error=None):
        self.rows = [] if rows is None else rows
        self.query_error = query_error
        self.insert_error = insert_error
        self.queries = []
        self.inserted = []

    def query(self, sql):
        self.queries.append(sql)
        if self.query_error is not None:
            raise self.query_error
        return self.rows

    def insert_json(self, table, rows):
        if self.insert_error is not None:
            raise self.insert_error
        self.inserted.append((table, rows))


def _entity(**overrides):
    defaults = dict(
        id=3,
        name="registration no not null",
        target_table="dwd_registration",
        rule_type="NOT_NULL",
        column_name="registration_no",
        params_json={},
        threshold=0.99,
        severity="P0",
        cron="0 * * * *",
        enabled=True,
    )
    defaults.update(overrides)
    return SimpleNamespace(**defaults)


@unittest.skipUnless(HAS_STACK, "sqlalchemy/pydantic_settings not installed")
class ToRuleTest(unittest.TestCase):
    def test_converts_orm_row_to_value_object(self):
        from app.services.quality_service import to_rule

        rule = to_rule(_entity())
        self.assertEqual(rule.id, 3)
        self.assertEqual(rule.name, "registration no not null")
        self.assertEqual(rule.table, "dwd_registration")
        self.assertEqual(rule.rule_type, "NOT_NULL")
        self.assertEqual(rule.column, "registration_no")
        self.assertEqual(rule.params, {})
        self.assertEqual(rule.threshold, 0.99)
        self.assertEqual(rule.severity, "P0")

    def test_missing_params_become_an_empty_dict(self):
        from app.services.quality_service import to_rule

        self.assertEqual(to_rule(_entity(params_json=None)).params, {})


@unittest.skipUnless(HAS_STACK, "sqlalchemy/pydantic_settings not installed")
class ValidateConfigTest(unittest.TestCase):
    def _validate(self, **overrides):
        from app.services.quality_service import _validated_config

        values = dict(
            name=" rule ",
            target_table="dwd_registration",
            rule_type="not_null",
            column_name=" registration_no ",
            params={},
            threshold=0.99,
            severity="p0",
        )
        values.update(overrides)
        return _validated_config(**values)

    def test_normalises_name_type_severity_and_column(self):
        config = self._validate()
        self.assertEqual(config["name"], "rule")
        self.assertEqual(config["rule_type"], "NOT_NULL")
        self.assertEqual(config["severity"], "P0")
        self.assertEqual(config["column_name"], "registration_no")
        self.assertEqual(config["threshold"], 0.99)

    def test_blank_name_is_rejected(self):
        with self.assertRaises(ValueError):
            self._validate(name="   ")

    def test_blank_table_is_rejected(self):
        with self.assertRaises(ValueError):
            self._validate(target_table="")

    def test_unknown_rule_type_is_rejected(self):
        with self.assertRaises(ValueError):
            self._validate(rule_type="ROW_COUNT")

    def test_unknown_severity_is_rejected(self):
        with self.assertRaises(ValueError):
            self._validate(severity="P9")

    def test_rate_threshold_above_one_is_rejected(self):
        with self.assertRaises(ValueError):
            self._validate(threshold=1.01)

    def test_freshness_threshold_is_seconds(self):
        config = self._validate(
            rule_type="FRESHNESS", column_name="update_time", threshold=3600
        )
        self.assertEqual(config["threshold"], 3600.0)

    def test_params_must_be_an_object(self):
        with self.assertRaises(ValueError):
            self._validate(params=["not", "an", "object"])

    def test_missing_column_is_rejected(self):
        with self.assertRaises(ValueError):
            self._validate(column_name=" ")

    def test_inverted_range_is_rejected(self):
        with self.assertRaises(ValueError):
            self._validate(
                rule_type="RANGE", column_name="age", params={"min": 10, "max": 1}
            )

    def test_custom_sql_must_be_read_only(self):
        with self.assertRaises(ValueError):
            self._validate(
                rule_type="CUSTOM_SQL", column_name=None, params={"sql": "DROP TABLE x"}
            )


@unittest.skipUnless(HAS_STACK, "sqlalchemy/pydantic_settings not installed")
class QualityServiceRunTest(unittest.TestCase):
    def _service(self, olap, db=None, now=None):
        from app.services.quality_service import QualityService

        return QualityService(
            db or FakeDb(), olap, now=now or (lambda: datetime(2026, 11, 4, 12, 0, 0))
        )

    def test_run_rule_persists_result_and_appends_ads_row(self):
        db = FakeDb()
        olap = FakeOlap([{"total_rows": 100, "bad_rows": 1}])

        outcome = self._service(olap, db).run_rule(_entity())

        self.assertEqual(outcome.result.status, "PASS")
        self.assertEqual(outcome.result_id, 100)
        self.assertTrue(outcome.olap_written)
        self.assertIsNone(outcome.olap_error)
        self.assertEqual(len(db.added), 1)
        record = db.added[0]
        self.assertEqual(record.rule_id, 3)
        self.assertEqual(record.total_rows, 100)
        self.assertEqual(record.bad_rows, 1)
        self.assertEqual(record.pass_rate, 0.99)
        self.assertEqual(record.status, "PASS")
        self.assertEqual(record.checked_at, datetime(2026, 11, 4, 12, 0, 0))
        self.assertEqual(len(olap.inserted), 1)
        table, rows = olap.inserted[0]
        self.assertEqual(table, "ads_quality_daily")
        self.assertEqual(len(rows), 1)
        self.assertEqual(
            set(rows[0]),
            {
                "metric_date",
                "rule_id",
                "rule_name",
                "total_rows",
                "bad_rows",
                "pass_rate",
                "severity",
                "status",
                "checked_at",
            },
        )
        self.assertEqual(rows[0]["metric_date"], date(2026, 11, 4))
        self.assertEqual(rows[0]["rule_name"], "registration no not null")

    def test_warehouse_write_failure_keeps_the_metadata_result(self):
        db = FakeDb()
        olap = FakeOlap(
            [{"total_rows": 10, "bad_rows": 0}],
            insert_error=RuntimeError("clickhouse down"),
        )

        outcome = self._service(olap, db).run_rule(_entity())

        self.assertEqual(len(db.added), 1)
        self.assertFalse(outcome.olap_written)
        self.assertIn("saved but", outcome.olap_error)
        self.assertIn("clickhouse down", outcome.olap_error)
        self.assertEqual(outcome.result_id, db.added[0].id)

    def test_evaluation_failure_raises_without_persisting(self):
        from app.core.exceptions import DataPlatformError

        db = FakeDb()
        olap = FakeOlap(query_error=RuntimeError("connection refused"))

        with self.assertRaises(DataPlatformError) as context:
            self._service(olap, db).run_rule(_entity())

        self.assertEqual(context.exception.code, 502)
        self.assertIn("evaluation failed", context.exception.message)
        self.assertEqual(db.added, [])

    def test_missing_store_is_a_clear_error(self):
        from app.core.exceptions import DataPlatformError

        with self.assertRaises(DataPlatformError) as context:
            self._service(None).run_rule(_entity())

        self.assertEqual(context.exception.code, 503)

    def test_persistence_failure_rolls_back_and_raises(self):
        from app.core.exceptions import DataPlatformError

        class BrokenDb(FakeDb):
            def commit(self):
                raise RuntimeError("database is locked")

        db = BrokenDb()
        with self.assertRaises(DataPlatformError) as context:
            self._service(FakeOlap([{"total_rows": 1, "bad_rows": 0}]), db).run_rule(
                _entity()
            )

        self.assertEqual(context.exception.code, 500)
        self.assertTrue(db.rolled_back)


@unittest.skipUnless(HAS_STACK, "sqlalchemy/pydantic_settings not installed")
class QualityServiceCrudTest(unittest.TestCase):
    def test_create_rule_persists_a_normalised_entity(self):
        from app.services.quality_service import QualityService

        db = FakeDb()
        entity = QualityService(db).create_rule(
            name="status enum",
            target_table="dwd_registration",
            rule_type="enum",
            column_name="status",
            params={"values": [1, 2]},
            threshold=1.0,
            severity="p1",
        )
        self.assertEqual(db.commits, 1)
        self.assertIs(db.added[0], entity)
        self.assertEqual(entity.rule_type, "ENUM")
        self.assertEqual(entity.severity, "P1")
        self.assertEqual(entity.params_json, {"values": [1, 2]})
        self.assertEqual(entity.id, 100)

    def test_invalid_create_writes_nothing(self):
        from app.services.quality_service import QualityService

        db = FakeDb()
        with self.assertRaises(ValueError):
            QualityService(db).create_rule(
                name="bad",
                target_table="dwd_registration",
                rule_type="NOT_NULL",
                column_name=None,
            )
        self.assertEqual(db.added, [])

    def test_update_rule_merges_and_validates(self):
        from app.services.quality_service import QualityService

        db = FakeDb()
        entity = _entity()

        QualityService(db).update_rule(entity, threshold=0.95, severity="P1")

        self.assertEqual(entity.threshold, 0.95)
        self.assertEqual(entity.severity, "P1")
        self.assertEqual(db.commits, 1)

    def test_update_rejects_unknown_fields(self):
        from app.services.quality_service import QualityService

        with self.assertRaises(ValueError):
            QualityService(FakeDb()).update_rule(_entity(), owner="me")

    def test_update_rejects_null_required_fields(self):
        from app.services.quality_service import QualityService

        with self.assertRaises(ValueError):
            QualityService(FakeDb()).update_rule(_entity(), name=None)

    def test_delete_rule_removes_results_first(self):
        from app.services.quality_service import QualityService

        db = FakeDb()
        entity = _entity()

        QualityService(db).delete_rule(entity)

        self.assertTrue(db.result_query.deleted)
        self.assertEqual(db.deleted, [entity])


if __name__ == "__main__":
    unittest.main()
