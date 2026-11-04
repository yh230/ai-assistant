# tests/unit/test_transform_runner.py
import tempfile
import unittest
from pathlib import Path

from etl.transform import TransformRunner, render_sql


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


if __name__ == "__main__":
    unittest.main()
