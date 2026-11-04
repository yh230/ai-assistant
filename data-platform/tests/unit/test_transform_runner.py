# tests/unit/test_transform_runner.py
import tempfile
import unittest
from pathlib import Path

from etl.transform import TransformRunner, render_sql, split_statements
from etl.transform_main import build_parser, run, validate_args


class FakeOlap:
    def __init__(self):
        self.executed: list[str] = []

    def execute(self, sql: str) -> None:
        self.executed.append(sql)


class RenderSqlTest(unittest.TestCase):
    def test_render_replaces_ds(self):
        self.assertEqual(
            render_sql("WHERE d = '{{ds}}'", {"ds": "2026-11-04"}),
            "WHERE d = '2026-11-04'",
        )

    def test_render_replaces_every_placeholder(self):
        sql = "SELECT '{{ds}}' AS a, '{{ds}}' AS b, {{limit}} AS c"

        rendered = render_sql(sql, {"ds": "2026-11-04", "limit": "10"})

        self.assertEqual(rendered, "SELECT '2026-11-04' AS a, '2026-11-04' AS b, 10 AS c")

    def test_render_keeps_sql_without_placeholders(self):
        self.assertEqual(render_sql("SELECT 1", {}), "SELECT 1")

    def test_missing_parameter_raises(self):
        with self.assertRaises(ValueError) as context:
            render_sql("SELECT * FROM t WHERE d = '{{ds}}'", {})

        self.assertIn("ds", str(context.exception))


class TransformRunnerTest(unittest.TestCase):
    def test_run_stage_executes_sql_files_in_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "dwd").mkdir()
            (root / "dwd" / "b.sql").write_text("SELECT 2", encoding="utf-8")
            (root / "dwd" / "a.sql").write_text("SELECT 1", encoding="utf-8")
            olap = FakeOlap()
            TransformRunner(olap, root).run_stage("dwd", {"ds": "2026-11-04"})
            self.assertEqual(olap.executed, ["SELECT 1", "SELECT 2"])

    def test_run_stage_returns_executed_file_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "dwd").mkdir()
            (root / "dwd" / "b.sql").write_text("SELECT 2", encoding="utf-8")
            (root / "dwd" / "a.sql").write_text("SELECT 1", encoding="utf-8")

            executed = TransformRunner(FakeOlap(), root).run_stage("dwd", {})

            self.assertEqual(executed, ["a.sql", "b.sql"])

    def test_run_stage_renders_parameters(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "ads").mkdir()
            (root / "ads" / "daily.sql").write_text(
                "SELECT * FROM t WHERE d = '{{ds}}'", encoding="utf-8"
            )
            olap = FakeOlap()

            TransformRunner(olap, root).run_stage("ads", {"ds": "2026-11-04"})

            self.assertEqual(olap.executed, ["SELECT * FROM t WHERE d = '2026-11-04'"])

    def test_run_stage_only_executes_the_requested_stage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "dwd").mkdir()
            (root / "ads").mkdir()
            (root / "dwd" / "a.sql").write_text("SELECT 'dwd'", encoding="utf-8")
            (root / "ads" / "a.sql").write_text("SELECT 'ads'", encoding="utf-8")
            olap = FakeOlap()

            TransformRunner(olap, root).run_stage("ads", {})

            self.assertEqual(olap.executed, ["SELECT 'ads'"])

    def test_run_stage_ignores_non_sql_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "dwd").mkdir()
            (root / "dwd" / "a.sql").write_text("SELECT 1", encoding="utf-8")
            (root / "dwd" / "notes.txt").write_text("not sql", encoding="utf-8")
            olap = FakeOlap()

            executed = TransformRunner(olap, root).run_stage("dwd", {})

            self.assertEqual(executed, ["a.sql"])
            self.assertEqual(olap.executed, ["SELECT 1"])

    def test_missing_parameter_executes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "dwd").mkdir()
            (root / "dwd" / "a.sql").write_text("SELECT '{{ds}}'", encoding="utf-8")
            olap = FakeOlap()

            with self.assertRaises(ValueError):
                TransformRunner(olap, root).run_stage("dwd", {})

            self.assertEqual(olap.executed, [])

    def test_unknown_stage_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileNotFoundError):
                TransformRunner(FakeOlap(), Path(tmp)).run_stage("ods", {})


class SplitStatementsTest(unittest.TestCase):
    def test_splits_on_semicolons(self):
        self.assertEqual(
            split_statements("SELECT 1; CREATE TABLE x (a UInt8);"),
            ["SELECT 1", "CREATE TABLE x (a UInt8)"],
        )

    def test_ignores_semicolons_in_string_literals(self):
        statements = split_statements(
            "SELECT 'a;b' AS value; SELECT 'it\\'s;ok' AS value;"
        )

        self.assertEqual(
            statements,
            ["SELECT 'a;b' AS value", "SELECT 'it\\'s;ok' AS value"],
        )

    def test_ignores_semicolons_in_line_comments(self):
        sql = "-- comment with ; inside\nCREATE TABLE x (a UInt8);\n-- tail ;\n"

        self.assertEqual(split_statements(sql), ["-- comment with ; inside\nCREATE TABLE x (a UInt8)"])

    def test_ignores_semicolons_in_block_comments(self):
        sql = "/* a ; b */ CREATE TABLE x (a UInt8);"

        self.assertEqual(split_statements(sql), ["/* a ; b */ CREATE TABLE x (a UInt8)"])

    def test_ignores_semicolons_in_quoted_identifiers(self):
        sql = 'SELECT 1 AS "a;b"; SELECT 2 AS `c;d`;'

        self.assertEqual(
            split_statements(sql), ['SELECT 1 AS "a;b"', "SELECT 2 AS `c;d`"]
        )

    def test_empty_input_returns_no_statements(self):
        self.assertEqual(split_statements("  ;; \n"), [])


class RunDdlTest(unittest.TestCase):
    def test_run_ddl_executes_each_statement(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "ddl").mkdir()
            (root / "ddl" / "schema.sql").write_text(
                "CREATE DATABASE IF NOT EXISTS d; CREATE TABLE t (a UInt8);",
                encoding="utf-8",
            )
            olap = FakeOlap()

            executed = TransformRunner(olap, root).run_ddl()

            self.assertEqual(executed, ["schema.sql"])
            self.assertEqual(
                olap.executed,
                ["CREATE DATABASE IF NOT EXISTS d", "CREATE TABLE t (a UInt8)"],
            )

    def test_run_ddl_splits_the_real_schema_file(self):
        sql_root = Path(__file__).resolve().parents[2] / "etl" / "sql"
        olap = FakeOlap()

        TransformRunner(olap, sql_root).run_ddl()

        # One CREATE DATABASE plus every table/view; the comment semicolons in
        # the header must not have produced bogus statements.
        self.assertEqual(
            sum("CREATE DATABASE" in statement for statement in olap.executed), 1
        )
        self.assertEqual(
            sum("CREATE TABLE" in statement for statement in olap.executed), 19
        )
        self.assertEqual(
            sum("CREATE VIEW" in statement for statement in olap.executed), 3
        )
        self.assertEqual(len(olap.executed), 23)


class TransformCliTest(unittest.TestCase):
    def test_ddl_needs_no_date(self):
        self.assertEqual(validate_args("ddl", None), {})
        self.assertEqual(validate_args("ddl", "2026-11-04"), {})

    def test_dwd_and_ads_require_a_date(self):
        for stage in ("dwd", "ads"):
            with self.subTest(stage=stage), self.assertRaises(ValueError):
                validate_args(stage, None)

    def test_invalid_date_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_args("dwd", "2026/11/04")

    def test_parser_accepts_the_three_stages(self):
        for stage in ("ddl", "dwd", "ads"):
            args = build_parser().parse_args(["--stage", stage, "--ds", "2026-11-04"])
            self.assertEqual(args.stage, stage)
            self.assertEqual(args.ds, "2026-11-04")

    def test_unknown_stage_is_rejected_by_the_parser(self):
        with self.assertRaises(SystemExit):
            build_parser().parse_args(["--stage", "ods"])

    def test_run_dispatches_ddl_and_stages(self):
        class Runner:
            def __init__(self):
                self.calls = []

            def run_ddl(self, params):
                self.calls.append(("ddl", params))
                return ["schema.sql"]

            def run_stage(self, stage, params):
                self.calls.append((stage, params))
                return ["x.sql"]

        runner = Runner()
        run(runner, "ddl", None)
        run(runner, "dwd", "2026-11-04")
        run(runner, "ads", "2026-11-04")

        self.assertEqual(
            runner.calls,
            [
                ("ddl", {}),
                ("dwd", {"ds": "2026-11-04"}),
                ("ads", {"ds": "2026-11-04"}),
            ],
        )


if __name__ == "__main__":
    unittest.main()
