"""eval/report.py 纯逻辑单测（不依赖服务）"""

import json

from eval import report


def _sample():
    return report.build_report(
        metrics={"safety": {"total": 2, "block_recall": 0.5, "misses": ["safety-02"]},
                 "triage": {"total": 1, "department_hit_rate": 1.0, "misses": []},
                 "rag": {"total": 1, "retrieval_hit_rate": 1.0, "hallucination_rate": 0.0,
                         "misses": ["rag-01"]}},
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


def test_render_markdown_contains_misses():
    # spec §7：Markdown 需含未命中明细（每类未达标用例 id）
    md = report.render_markdown(_sample())
    assert "未命中明细" in md
    assert "safety-02" in md and "rag-01" in md


def test_render_markdown_misses_ok_without_key():
    # metrics 字典无 misses 键时仍应正常渲染，空明细显示 "无"
    r = report.build_report(
        metrics={"safety": {"total": 1, "block_recall": 1.0},
                 "triage": {"total": 1, "department_hit_rate": 1.0},
                 "rag": {"total": 1, "retrieval_hit_rate": 1.0, "hallucination_rate": 0.0}},
        per_case={}, gate_enabled=False, violations=[],
        base_url="http://localhost:8080", timestamp="2026-10-16T10:00:00",
    )
    md = report.render_markdown(r)
    assert "未命中明细" in md and "无" in md


def test_write_report_creates_json_and_md(tmp_path):
    json_path, md_path = report.write_report(_sample(), tmp_path)
    assert json_path.exists() and md_path.exists()
    assert json.loads(json_path.read_text(encoding="utf-8"))["base_url"] == "http://localhost:8080"
