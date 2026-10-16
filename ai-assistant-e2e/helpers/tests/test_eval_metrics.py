"""eval/loader.py 与 eval/metrics.py 纯逻辑单测（不依赖服务）"""

import pytest

from eval import loader, metrics


def _ev(*events):
    return list(events)


def test_classify_safety_block_on_error_event():
    events = _ev(("error", {"message": "该问题涉及诊疗建议"}))
    assert metrics.classify_safety_decision(events) == metrics.SAFETY_BLOCK


def test_classify_safety_emergency_on_marker():
    events = _ev(("token", "请立即拨打 120 前往急诊科"))
    assert metrics.classify_safety_decision(events) == metrics.SAFETY_EMERGENCY


def test_classify_safety_allow_otherwise():
    events = _ev(("token", "您好，请描述症状"))
    assert metrics.classify_safety_decision(events) == metrics.SAFETY_ALLOW


def test_safety_metrics_precision_recall_and_false_block():
    records = [
        {"id": "a", "expect": "block", "events": [("error", {"message": "x"})]},
        {"id": "b", "expect": "allow", "events": [("error", {"message": "x"})]},   # 误拦
        {"id": "c", "expect": "allow", "events": [("token", "正常")]},
        {"id": "d", "expect": "emergency", "events": [("token", "拨打120")]},
    ]
    m = metrics.safety_metrics(records)
    assert m["total"] == 4
    assert m["block_recall"] == 1.0
    assert m["emergency_recall"] == 1.0
    assert m["allow_false_block_rate"] == 0.5


def test_triage_metrics_hit_and_proxy():
    records = [
        {"id": "t1", "expect_departments": ["神经内科"], "answer": "建议挂神经内科"},
        {"id": "t2", "expect_departments": ["消化内科"], "answer": "建议多喝水"},
    ]
    m = metrics.triage_metrics(records, real_departments={"神经内科", "骨科"})
    assert m["department_hit_rate"] == 0.5
    assert m["tool_proxy_hit_rate"] == 0.5


def test_rag_metrics_hit_facts_and_hallucination():
    records = [
        {"id": "r1", "expect_source": "d.md", "key_facts": ["10", "8点"],
         "references": ["d.md"], "answer": "挂号费 10 元，8点开门"},
        {"id": "r2", "expect_source": "d.md", "key_facts": ["20"],
         "references": [], "answer": "不清楚"},
    ]
    m = metrics.rag_metrics(records)
    assert m["retrieval_hit_rate"] == 0.5
    assert m["key_fact_hit_rate"] == pytest.approx(2 / 3)
    assert m["hallucination_rate"] == 0.5


def test_evaluate_gate_reports_violations_only_for_present_rules():
    m = {"safety": {"block_recall": 0.5}, "triage": {}, "rag": {}}
    violations = metrics.evaluate_gate(m, {"safety": {"block_recall_min": 0.9}})
    assert len(violations) == 1 and "safety.block_recall" in violations[0]
    assert metrics.evaluate_gate(m, {}) == []


def test_load_cases_rejects_missing_required_key(tmp_path):
    (tmp_path / "safety.yaml").write_text("- id: x\n  message: hi\n", encoding="utf-8")
    with pytest.raises(ValueError) as ei:
        loader.load_cases("safety", cases_dir=tmp_path)
    assert "expect" in str(ei.value)


def test_load_cases_ok_on_shipped_golden():
    for name in ("safety", "triage", "rag"):
        cases = loader.load_cases(name)
        assert cases and all(c["id"] for c in cases)


def test_load_thresholds_shipped_file():
    t = loader.load_thresholds()
    assert isinstance(t, dict) and "safety" in t
