"""AI 评测套件（P0-B）：安全/分诊/RAG 三类指标 + 报告 + 可选阈值门禁。

运行：
  pytest -m eval                 全量（需运行栈 + 可用模型）
  pytest -m "eval and not ai"     仅安全集（不依赖 LLM）
  pytest -m eval --eval-gate      按 eval/thresholds.yaml 门禁
"""

import datetime
from pathlib import Path

import pytest

from eval import loader, metrics, report, runner
from helpers import api

pytestmark = pytest.mark.eval

REPORTS_DIR = Path(__file__).resolve().parents[1] / "reports"


def _now():
    return datetime.datetime.now().isoformat(timespec="seconds")


@pytest.fixture(scope="session")
def eval_sink(request, cfg):
    """收集各评测集指标；teardown 统一出报告并按 --eval-gate 判定"""
    sink = {"metrics": {}, "per_case": {}}
    yield sink
    thresholds = loader.load_thresholds()
    violations = metrics.evaluate_gate(sink["metrics"], thresholds)
    gate_enabled = bool(request.config.getoption("--eval-gate"))
    rep = report.build_report(sink["metrics"], sink["per_case"], gate_enabled,
                              violations, cfg["base_url"], _now())
    json_path, md_path = report.write_report(rep, REPORTS_DIR)
    print(f"\n[eval] 报告: {json_path} / {md_path}")
    if violations:
        print(f"[eval] 阈值告警: {violations}")
    if gate_enabled and violations:
        pytest.fail(f"评测阈值未达标: {violations}")


def test_safety_set(client, cfg, new_patient, eval_sink):
    """安全集（不依赖 LLM）：风控决策混淆矩阵与 precision/recall"""
    cases = loader.load_cases("safety")
    patient = new_patient(cfg["hospital1"])
    records = runner.run_safety(client, patient.token, cases)
    eval_sink["metrics"]["safety"] = metrics.safety_metrics(records)
    eval_sink["per_case"]["safety"] = records
    assert eval_sink["metrics"]["safety"]["total"] == len(cases)


@pytest.mark.ai
def test_triage_set(client, cfg, new_patient, departments1, eval_sink):
    """分诊集（依赖 LLM）：科室命中率 + 工具调用代理命中率"""
    cases = loader.load_cases("triage")
    patient = new_patient(cfg["hospital1"])
    records = runner.run_triage(client, patient.token, cases)
    eval_sink["metrics"]["triage"] = metrics.triage_metrics(records, departments1)
    eval_sink["per_case"]["triage"] = records
    assert eval_sink["metrics"]["triage"]["total"] == len(cases)


@pytest.mark.ai
def test_rag_set(client, cfg, staff1, new_patient, eval_sink):
    """RAG 集（依赖 LLM + 知识库）：检索/关键事实命中率与幻觉率；跑后清理 __eval__ 文档"""
    cases = loader.load_cases("rag")
    doc_ids = runner.upload_eval_docs(client, staff1.token, cases)
    try:
        runner.wait_docs_ready(client, staff1.token, doc_ids)
        patient = new_patient(cfg["hospital1"])
        records = runner.run_rag(client, patient.token, cases)
        eval_sink["metrics"]["rag"] = metrics.rag_metrics(records)
        eval_sink["per_case"]["rag"] = records
        assert eval_sink["metrics"]["rag"]["total"] == len(cases)
    finally:
        runner.delete_eval_docs(client, staff1.token)
    remaining = [d for d in api.list_docs(client, staff1.token)["list"]
                 if str(d["name"]).startswith(runner.EVAL_DOC_PREFIX)]
    assert remaining == [], f"__eval__ 文档未清理干净: {remaining}"
