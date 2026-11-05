# tests/unit/test_quality_rules.py
import unittest
from datetime import date, datetime, timedelta, timezone
from unittest import mock

from etl.quality import (
    QUALITY_DAILY_TABLE,
    QualityResult,
    QualityRule,
    build_quality_row,
    build_rule_sql,
    evaluate_rule,
    platform_today,
    validate_rule,
    weighted_score,
)


class FakeOlap:
    """OlapStore double: returns canned rows and records executed SQL."""

    def __init__(self, rows=None, error=None):
        self.rows = [] if rows is None else rows
        self.error = error
        self.queries = []

    def query(self, sql):
        self.queries.append(sql)
        if self.error is not None:
            raise self.error
        return self.rows


def _rule(**overrides):
    defaults = dict(
        id=1,
        name="phone not null",
        table="dwd_registration",
        rule_type="NOT_NULL",
        column="registration_no",
        params={},
        threshold=0.99,
        severity="P0",
    )
    defaults.update(overrides)
    return QualityRule(**defaults)


def _platform_today():
    return platform_today()


def _volume_rows(counts, end=None):
    """Build VOLUME query rows, oldest first, ending on ``end`` (default today)."""
    end = end or _platform_today()
    start = end - timedelta(days=len(counts) - 1)
    return [
        {"metric_date": start + timedelta(days=index), "total_rows": count}
        for index, count in enumerate(counts)
    ]


class QualityRuleTest(unittest.TestCase):
    def test_platform_today_matches_the_shanghai_calendar(self):
        shanghai = timezone(timedelta(hours=8), "Asia/Shanghai")
        self.assertEqual(platform_today(), datetime.now(shanghai).date())

    def test_not_null_sql(self):
        rule = QualityRule(id=1, name="phone not null", table="dwd_registration", rule_type="NOT_NULL", column="registration_no", params={}, threshold=0.99, severity="P0")
        sql = build_rule_sql(rule)
        self.assertIn("countIf(registration_no IS NULL)", sql)
        self.assertIn("dwd_registration", sql)

    def test_enum_sql(self):
        rule = QualityRule(id=2, name="status enum", table="dwd_registration", rule_type="ENUM", column="status", params={"values": [1, 2, 3, 4, 5, 6]}, threshold=1.0, severity="P0")
        sql = build_rule_sql(rule)
        self.assertIn("status NOT IN (1, 2, 3, 4, 5, 6)", sql)

    def test_weighted_score(self):
        from etl.quality import QualityResult

        results = [
            QualityResult(rule_id=1, total_rows=100, bad_rows=0, pass_rate=1.0, status="PASS", severity="P0"),
            QualityResult(rule_id=2, total_rows=100, bad_rows=10, pass_rate=0.9, status="FAIL", severity="P2"),
        ]
        self.assertAlmostEqual(weighted_score(results), (1.0 * 3 + 0.9 * 1) / 4)


class BuildRuleSqlTest(unittest.TestCase):
    def test_not_null_sql_is_exact(self):
        self.assertEqual(
            build_rule_sql(_rule()),
            "SELECT count() AS total_rows, countIf(registration_no IS NULL) "
            "AS bad_rows FROM dwd_registration FINAL",
        )

    def test_unique_sql(self):
        sql = build_rule_sql(_rule(rule_type="UNIQUE", column="registration_no"))
        self.assertEqual(
            sql,
            "SELECT count() AS total_rows, count() - uniqExact(registration_no) "
            "AS bad_rows FROM dwd_registration FINAL",
        )

    def test_range_sql(self):
        sql = build_rule_sql(
            _rule(
                rule_type="RANGE",
                table="dwd_patient",
                column="age",
                params={"min": 0, "max": 150},
            )
        )
        self.assertEqual(
            sql,
            "SELECT count() AS total_rows, "
            "countIf(age < 0 OR age > 150) AS bad_rows FROM dwd_patient FINAL",
        )

    def test_range_accepts_float_and_numeric_string_bounds(self):
        sql = build_rule_sql(
            _rule(
                rule_type="RANGE",
                column="score",
                params={"min": "0.5", "max": 99.5},
            )
        )
        self.assertIn("score < 0.5 OR score > 99.5", sql)

    def test_enum_string_values_are_quoted_and_escaped(self):
        sql = build_rule_sql(
            _rule(
                rule_type="ENUM",
                column="status",
                params={"values": ["1", "x' OR '1'='1"]},
            )
        )
        self.assertIn("status NOT IN ('1', 'x\\' OR \\'1\\'=\\'1')", sql)

    def test_freshness_sql(self):
        sql = build_rule_sql(
            _rule(rule_type="FRESHNESS", column="update_time", threshold=300.0)
        )
        self.assertEqual(
            sql,
            "SELECT count() AS total_rows, "
            "dateDiff('second', max(update_time), now('Asia/Shanghai')) "
            "AS lag_seconds FROM dwd_registration FINAL",
        )

    def test_volume_sql_defaults_to_created_at(self):
        sql = build_rule_sql(_rule(rule_type="VOLUME", column=None))
        self.assertEqual(
            sql,
            "SELECT toDate(created_at, 'Asia/Shanghai') AS metric_date, "
            "count() AS total_rows FROM dwd_registration FINAL "
            "WHERE created_at >= toDate(now(), 'Asia/Shanghai') - 7 "
            "GROUP BY metric_date ORDER BY metric_date",
        )

    def test_enum_sql_reads_final(self):
        sql = build_rule_sql(
            _rule(rule_type="ENUM", column="status", params={"values": [1, 2]})
        )
        self.assertIn("FROM dwd_registration FINAL", sql)

    def test_every_standard_rule_reads_final(self):
        rules = (
            _rule(),
            _rule(rule_type="UNIQUE", column="registration_no"),
            _rule(rule_type="RANGE", column="age", params={"min": 0, "max": 150}),
            _rule(rule_type="ENUM", column="status", params={"values": [1, 2]}),
            _rule(rule_type="FRESHNESS", column="update_time"),
            _rule(rule_type="VOLUME", column=None),
        )
        for rule in rules:
            with self.subTest(rule=rule.rule_type):
                self.assertIn("FROM dwd_registration FINAL", build_rule_sql(rule))

    def test_volume_sql_uses_the_platform_timezone_for_today(self):
        sql = build_rule_sql(_rule(rule_type="VOLUME", column=None))
        # The window must not depend on the ClickHouse server time zone.
        self.assertIn("toDate(now(), 'Asia/Shanghai') - 7", sql)
        self.assertNotIn("today()", sql)

    def test_volume_sql_uses_configured_date_column(self):
        sql = build_rule_sql(
            _rule(
                rule_type="VOLUME",
                column=None,
                params={"date_column": "schedule_date"},
            )
        )
        self.assertIn("toDate(schedule_date, 'Asia/Shanghai') AS metric_date", sql)
        self.assertIn("schedule_date >= toDate(now(), 'Asia/Shanghai') - 7", sql)

    def test_custom_sql_is_used_verbatim(self):
        custom = (
            "SELECT count() AS total_rows, "
            "countIf(pay_amount < 0) AS bad_rows FROM dwd_registration"
        )
        sql = build_rule_sql(
            _rule(rule_type="CUSTOM_SQL", column=None, params={"sql": custom})
        )
        self.assertEqual(sql, custom)

    def test_custom_sql_accepts_cte(self):
        custom = "WITH x AS (SELECT 1 AS n) SELECT count() AS total_rows, 0 AS bad_rows FROM x"
        self.assertEqual(
            build_rule_sql(
                _rule(rule_type="CUSTOM_SQL", column=None, params={"sql": custom})
            ),
            custom,
        )

    def test_dot_qualified_table_is_accepted(self):
        sql = build_rule_sql(_rule(table="data_platform.dwd_registration"))
        self.assertIn("FROM data_platform.dwd_registration", sql)

    def test_unknown_rule_type_raises(self):
        with self.assertRaises(ValueError):
            build_rule_sql(_rule(rule_type="ROW_COUNT"))

    def test_injection_in_table_is_rejected(self):
        with self.assertRaises(ValueError):
            build_rule_sql(_rule(table="dwd_registration; DROP TABLE dwd_registration"))

    def test_injection_in_column_is_rejected(self):
        with self.assertRaises(ValueError):
            build_rule_sql(_rule(column="status) OR 1=1 --"))

    def test_injection_in_range_bound_is_rejected(self):
        with self.assertRaises(ValueError):
            build_rule_sql(
                _rule(rule_type="RANGE", column="age", params={"min": "0 OR 1=1", "max": 10})
            )

    def test_injection_in_volume_date_column_is_rejected(self):
        with self.assertRaises(ValueError):
            build_rule_sql(
                _rule(
                    rule_type="VOLUME",
                    column=None,
                    params={"date_column": "created_at; DROP TABLE x"},
                )
            )

    def test_custom_sql_cannot_write(self):
        for sql in (
            "DELETE FROM dwd_registration",
            "INSERT INTO dwd_registration VALUES (1)",
            "SELECT 1; DROP TABLE dwd_registration",
        ):
            with self.subTest(sql=sql), self.assertRaises(ValueError):
                build_rule_sql(
                    _rule(rule_type="CUSTOM_SQL", column=None, params={"sql": sql})
                )

    def test_custom_sql_rejects_external_read_functions(self):
        for sql in (
            "SELECT * FROM url('http://169.254.169.254/latest/meta-data', CSV)",
            "SELECT * FROM file('/etc/passwd', CSV)",
            "SELECT * FROM system.query_log",
            "SELECT 1 AS total_rows, 0 AS bad_rows INTO OUTFILE '/tmp/x'",
        ):
            with self.subTest(sql=sql), self.assertRaises(ValueError):
                build_rule_sql(
                    _rule(rule_type="CUSTOM_SQL", column=None, params={"sql": sql})
                )

    def test_custom_sql_rejects_multiple_statements(self):
        with self.assertRaises(ValueError):
            build_rule_sql(
                _rule(
                    rule_type="CUSTOM_SQL",
                    column=None,
                    params={"sql": "SELECT 1 AS total_rows, 0 AS bad_rows; DROP TABLE x"},
                )
            )

    def test_custom_sql_allows_a_trailing_semicolon(self):
        sql = build_rule_sql(
            _rule(
                rule_type="CUSTOM_SQL",
                column=None,
                params={"sql": "SELECT 1 AS total_rows, 0 AS bad_rows;"},
            )
        )
        self.assertEqual(sql, "SELECT 1 AS total_rows, 0 AS bad_rows;")

    def test_enum_rejects_empty_values(self):
        with self.assertRaises(ValueError):
            build_rule_sql(_rule(rule_type="ENUM", params={"values": []}))

    def test_range_rejects_inverted_bounds(self):
        with self.assertRaises(ValueError):
            build_rule_sql(
                _rule(rule_type="RANGE", column="age", params={"min": 10, "max": 1})
            )

    def test_column_is_required_for_column_rules(self):
        for rule_type in ("NOT_NULL", "UNIQUE", "RANGE", "ENUM", "FRESHNESS"):
            with self.subTest(rule_type=rule_type):
                kwargs = {"values": [1]} if rule_type == "ENUM" else {}
                if rule_type == "RANGE":
                    kwargs = {"min": 0, "max": 1}
                with self.assertRaises(ValueError):
                    build_rule_sql(
                        _rule(rule_type=rule_type, column=None, params=kwargs)
                    )

    def test_rate_threshold_must_be_within_unit_interval(self):
        with self.assertRaises(ValueError):
            build_rule_sql(_rule(threshold=1.5))
        with self.assertRaises(ValueError):
            build_rule_sql(_rule(threshold=-0.1))

    def test_freshness_threshold_is_seconds(self):
        sql = build_rule_sql(
            _rule(rule_type="FRESHNESS", column="update_time", threshold=3600.0)
        )
        self.assertIn("dateDiff", sql)

    def test_unsupported_severity_is_rejected(self):
        with self.assertRaises(ValueError):
            build_rule_sql(_rule(severity="P9"))

    def test_validate_rule_accepts_valid_rule(self):
        self.assertIsNone(validate_rule(_rule()))


class EvaluateRuleTest(unittest.TestCase):
    def test_not_null_pass(self):
        olap = FakeOlap([{"total_rows": 100, "bad_rows": 1}])
        result = evaluate_rule(_rule(threshold=0.99), olap)
        self.assertEqual(result.total_rows, 100)
        self.assertEqual(result.bad_rows, 1)
        self.assertAlmostEqual(result.pass_rate, 0.99)
        self.assertEqual(result.status, "PASS")
        self.assertEqual(result.severity, "P0")

    def test_not_null_fail(self):
        olap = FakeOlap([{"total_rows": 100, "bad_rows": 2}])
        result = evaluate_rule(_rule(threshold=0.99), olap)
        self.assertAlmostEqual(result.pass_rate, 0.98)
        self.assertEqual(result.status, "FAIL")

    def test_empty_result_set_passes(self):
        result = evaluate_rule(_rule(), FakeOlap([]))
        self.assertEqual((result.total_rows, result.bad_rows), (0, 0))
        self.assertEqual(result.pass_rate, 1.0)
        self.assertEqual(result.status, "PASS")

    def test_null_counts_are_treated_as_zero(self):
        result = evaluate_rule(_rule(), FakeOlap([{"total_rows": None, "bad_rows": None}]))
        self.assertEqual(result.pass_rate, 1.0)

    def test_missing_columns_raise(self):
        with self.assertRaises(ValueError):
            evaluate_rule(_rule(), FakeOlap([{"total_rows": 1}]))

    def test_custom_sql_runs_the_provided_statement(self):
        custom = "SELECT count() AS total_rows, countIf(x = 1) AS bad_rows FROM t"
        olap = FakeOlap([{"total_rows": 100, "bad_rows": 10}])
        result = evaluate_rule(
            _rule(rule_type="CUSTOM_SQL", column=None, params={"sql": custom}, threshold=0.95),
            olap,
        )
        self.assertEqual(olap.queries, [custom])
        self.assertAlmostEqual(result.pass_rate, 0.9)
        self.assertEqual(result.status, "FAIL")

    def test_custom_sql_must_return_both_columns(self):
        with self.assertRaises(ValueError):
            evaluate_rule(
                _rule(
                    rule_type="CUSTOM_SQL",
                    column=None,
                    params={"sql": "SELECT count() AS total_rows FROM t"},
                ),
                FakeOlap([{"total_rows": 1}]),
            )

    def test_custom_sql_must_return_a_row(self):
        with self.assertRaises(ValueError):
            evaluate_rule(
                _rule(
                    rule_type="CUSTOM_SQL",
                    column=None,
                    params={"sql": "SELECT 1 AS total_rows, 0 AS bad_rows"},
                ),
                FakeOlap([]),
            )

    def test_freshness_pass(self):
        result = evaluate_rule(
            _rule(rule_type="FRESHNESS", column="update_time", threshold=60.0),
            FakeOlap([{"total_rows": 10, "lag_seconds": 30}]),
        )
        self.assertEqual(result.total_rows, 1)
        self.assertEqual(result.bad_rows, 0)
        self.assertEqual(result.pass_rate, 1.0)
        self.assertEqual(result.status, "PASS")

    def test_freshness_fail(self):
        result = evaluate_rule(
            _rule(rule_type="FRESHNESS", column="update_time", threshold=60.0),
            FakeOlap([{"total_rows": 10, "lag_seconds": 120}]),
        )
        self.assertEqual(result.bad_rows, 1)
        self.assertEqual(result.pass_rate, 0.0)
        self.assertEqual(result.status, "FAIL")

    def test_freshness_null_lag_fails(self):
        result = evaluate_rule(
            _rule(rule_type="FRESHNESS", column="update_time", threshold=60.0),
            FakeOlap([{"total_rows": 0, "lag_seconds": None}]),
        )
        self.assertEqual(result.status, "FAIL")
        self.assertEqual(result.pass_rate, 0.0)

    def test_freshness_negative_lag_is_clamped(self):
        result = evaluate_rule(
            _rule(rule_type="FRESHNESS", column="update_time", threshold=0.0),
            FakeOlap([{"total_rows": 1, "lag_seconds": -5}]),
        )
        self.assertEqual(result.status, "PASS")

    def test_volume_stable_passes(self):
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=0.3),
            FakeOlap(_volume_rows([100] * 8)),
        )
        self.assertEqual(result.total_rows, 100)
        self.assertEqual(result.status, "PASS")

    def test_volume_spike_fails(self):
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=0.3),
            FakeOlap(_volume_rows([100] * 7 + [200])),
        )
        self.assertEqual(result.total_rows, 200)
        self.assertEqual(result.bad_rows, 1)
        self.assertEqual(result.status, "FAIL")

    def test_volume_drop_fails(self):
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=0.3),
            FakeOlap(_volume_rows([100] * 7 + [50])),
        )
        self.assertEqual(result.status, "FAIL")

    def test_volume_zero_baseline_zero_today_passes(self):
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=0.3),
            FakeOlap(_volume_rows([0] * 8)),
        )
        self.assertEqual(result.status, "PASS")

    def test_volume_zero_baseline_nonzero_today_fails(self):
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=0.3),
            FakeOlap(_volume_rows([0] * 7 + [10])),
        )
        self.assertEqual(result.status, "FAIL")

    def test_volume_missing_today_counts_as_zero(self):
        today = _platform_today()
        rows = _volume_rows([100] * 7, end=today - timedelta(days=1))
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=0.3),
            FakeOlap(rows),
        )
        self.assertEqual(result.total_rows, 0)
        self.assertEqual(result.status, "FAIL")

    def test_volume_missing_today_fails_even_with_a_permissive_threshold(self):
        today = _platform_today()
        rows = _volume_rows([100] * 7, end=today - timedelta(days=1))
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=10.0),
            FakeOlap(rows),
        )
        self.assertEqual(result.total_rows, 0)
        self.assertEqual(result.bad_rows, 1)
        self.assertEqual(result.pass_rate, 0.0)
        self.assertEqual(result.status, "FAIL")

    def test_volume_with_no_rows_fails(self):
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=10.0),
            FakeOlap([]),
        )
        self.assertEqual(result.total_rows, 0)
        self.assertEqual(result.status, "FAIL")

    def test_volume_rows_may_arrive_unsorted(self):
        rows = list(reversed(_volume_rows([100] * 7 + [100])))
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=0.3),
            FakeOlap(rows),
        )
        self.assertEqual(result.status, "PASS")

    def test_volume_accepts_iso_date_strings(self):
        rows = [
            {"metric_date": str(row["metric_date"]), "total_rows": row["total_rows"]}
            for row in _volume_rows([100] * 7 + [200])
        ]
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=0.3),
            FakeOlap(rows),
        )
        self.assertEqual(result.status, "FAIL")

    def test_volume_stale_data_fails(self):
        old = _platform_today() - timedelta(days=10)
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=10.0),
            FakeOlap([{"metric_date": old, "total_rows": 100}]),
        )
        self.assertEqual(result.total_rows, 0)
        self.assertEqual(result.status, "FAIL")

    def test_volume_deviation_boundary_passes(self):
        result = evaluate_rule(
            _rule(rule_type="VOLUME", column=None, threshold=1.0),
            FakeOlap(_volume_rows([100] * 7 + [200])),
        )
        self.assertEqual(result.status, "PASS")

    def test_volume_reference_date_comes_from_the_platform_helper(self):
        reference = date(2026, 11, 4)
        rows = [
            {"metric_date": reference - timedelta(days=offset), "total_rows": 100}
            for offset in range(0, 8)
        ]
        with mock.patch("etl.quality.platform_today", return_value=reference):
            result = evaluate_rule(
                _rule(rule_type="VOLUME", column=None, threshold=0.3),
                FakeOlap(rows),
            )
        self.assertEqual(result.total_rows, 100)
        self.assertEqual(result.status, "PASS")

    def test_volume_reference_date_detects_a_missing_today(self):
        reference = date(2026, 11, 4)
        rows = [
            {"metric_date": reference - timedelta(days=offset), "total_rows": 100}
            for offset in range(1, 8)
        ]
        with mock.patch("etl.quality.platform_today", return_value=reference):
            result = evaluate_rule(
                _rule(rule_type="VOLUME", column=None, threshold=0.3),
                FakeOlap(rows),
            )
        self.assertEqual(result.total_rows, 0)
        self.assertEqual(result.status, "FAIL")


class WeightedScoreTest(unittest.TestCase):
    def test_empty_results_score_1(self):
        self.assertEqual(weighted_score([]), 1.0)

    def test_all_severities_are_weighted(self):
        results = [
            QualityResult(1, 10, 0, 1.0, "PASS", "P0"),
            QualityResult(2, 10, 5, 0.5, "FAIL", "P1"),
            QualityResult(3, 10, 10, 0.0, "FAIL", "P2"),
        ]
        self.assertAlmostEqual(weighted_score(results), (3 + 1.0 + 0.0) / 6)

    def test_unknown_severity_uses_weight_one(self):
        results = [QualityResult(1, 10, 0, 0.5, "FAIL", "P9")]
        self.assertAlmostEqual(weighted_score(results), 0.5)


class QualityDailyRowTest(unittest.TestCase):
    def test_row_has_the_ads_quality_daily_columns(self):
        rule = QualityRule(
            id=7,
            name="status enum",
            table="dwd_registration",
            rule_type="ENUM",
            column="status",
            params={"values": [1, 2]},
            threshold=1.0,
            severity="P0",
        )
        result = QualityResult(7, 100, 2, 0.98, "FAIL", "P0")
        row = build_quality_row(rule, result, datetime(2026, 11, 4, 12, 0, 0))
        self.assertEqual(
            set(row),
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
        self.assertEqual(row["rule_id"], 7)
        self.assertEqual(row["rule_name"], "status enum")
        self.assertEqual(row["total_rows"], 100)
        self.assertEqual(row["bad_rows"], 2)
        self.assertEqual(row["pass_rate"], 0.98)
        self.assertEqual(row["severity"], "P0")
        self.assertEqual(row["status"], "FAIL")
        # The service supplies platform-local (Asia/Shanghai) naive values.
        self.assertEqual(row["checked_at"], datetime(2026, 11, 4, 12, 0, 0))

    def test_metric_date_uses_the_platform_calendar(self):
        rule = _rule()
        result = QualityResult(1, 1, 0, 1.0, "PASS", "P0")
        # A naive value is already platform-local: 20:00 stays on 11-04.
        row = build_quality_row(rule, result, datetime(2026, 11, 4, 20, 0, 0))
        self.assertEqual(str(row["metric_date"]), "2026-11-04")

    def test_aware_checked_at_is_converted_once(self):
        rule = _rule()
        result = QualityResult(1, 1, 0, 1.0, "PASS", "P0")
        # 20:00 UTC is already the next day in Asia/Shanghai (UTC+8).
        row = build_quality_row(
            rule, result, datetime(2026, 11, 4, 20, 0, 0, tzinfo=timezone.utc)
        )
        self.assertEqual(str(row["metric_date"]), "2026-11-05")
        self.assertEqual(row["checked_at"], datetime(2026, 11, 5, 4, 0, 0))

    def test_checked_at_is_stored_as_platform_wall_clock(self):
        rule = _rule()
        result = QualityResult(1, 1, 0, 1.0, "PASS", "P0")
        checked_at = datetime(2026, 11, 4, 9, 0, 0, tzinfo=timezone(timedelta(hours=8)))
        row = build_quality_row(rule, result, checked_at)
        self.assertEqual(row["checked_at"], datetime(2026, 11, 4, 9, 0, 0))

    def test_table_name_matches_the_ddl(self):
        self.assertEqual(QUALITY_DAILY_TABLE, "ads_quality_daily")


if __name__ == "__main__":
    unittest.main()
