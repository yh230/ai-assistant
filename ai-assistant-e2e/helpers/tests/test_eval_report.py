"""eval/report.py 纯逻辑单测（不依赖服务）"""

import json

from eval import report


def _sample():
    return report.build_report(
        metrics={"safety": {"total": 2, "block_recall": 0.5},
                 "triage": {"total": 1, "department_hit_rate": 1.0},
                 "rag": {"total": 1, "retrieval_hit_rate": 1.0, "hallucination_rate": 0.0}},
        per_case={"safety": [{"id": "a"}]},
        gate_enabled=False,
        violations=[],
        base_url="http://localhost:8080",
        timestamp="2026-10-16T10:00:00",
    )


def test_build_report_shape():
    r = _sample()
    assert r["gate"] == {"enabled": False, "violated": []}
    assert r["metrics"]["safety"]["block_recall"] == 0.5


def test_render_markdown_contains_metrics_and_gate():
    md = report.render_markdown(_sample())
    assert "## safety" in md and "block_recall" in md
    assert "门禁" in md


def test_write_report_creates_json_and_md(tmp_path):
    json_path, md_path = report.write_report(_sample(), tmp_path)
    assert json_path.exists() and md_path.exists()
    assert json.loads(json_path.read_text(encoding="utf-8"))["base_url"] == "http://localhost:8080"
