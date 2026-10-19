"""评测运行器：唯一接触 API 的层（编排 + 观测收集）"""

import time

from helpers import api
from helpers.sse import full_text

from eval import metrics

EVAL_DOC_PREFIX = metrics.EVAL_DOC_PREFIX


def run_safety(client, patient_token, cases):
    """安全集：每条用例开独立 CHAT 会话发问，收集 SSE 事件"""
    records = []
    for case in cases:
        session_id = api.create_patient_session(client, patient_token, "CHAT")
        events = api.chat_patient(client, patient_token, session_id, case["message"])
        records.append({"id": case["id"], "expect": case["expect"], "events": events})
    return records


def run_triage(client, patient_token, cases):
    """分诊集：每条用例开独立 TRIAGE 会话，收集完整回答文本"""
    records = []
    for case in cases:
        session_id = api.create_patient_session(client, patient_token, "TRIAGE")
        events = api.chat_patient(client, patient_token, session_id, case["message"])
        records.append({"id": case["id"], "expect_departments": case["expect_departments"],
                        "answer": full_text(events)})
    return records


def run_rag(client, patient_token, cases):
    """RAG 集：每条用例开独立 RAG 会话，收集引用来源与回答文本"""
    records = []
    for case in cases:
        session_id = api.create_patient_session(client, patient_token, "RAG")
        events = api.chat_patient(client, patient_token, session_id, case["question"])
        records.append({"id": case["id"], "expect_source": case["expect_source"],
                        "key_facts": case["key_facts"], "references": _reference_names(events),
                        "answer": full_text(events)})
    return records


def upload_eval_docs(client, staff_token, rag_cases):
    """上传 RAG 用例内联文档，返回文档 ID 列表"""
    doc_ids = []
    for case in rag_cases:
        doc_id = api.upload_doc(client, staff_token, case["doc_name"],
                                case["doc_content"].encode("utf-8"))
        doc_ids.append(doc_id)
    return doc_ids


def wait_docs_ready(client, staff_token, doc_ids, timeout_s=30, interval_s=1.0):
    """轮询至文档向量化就绪（status==1）；超时抛 TimeoutError"""
    deadline = time.time() + timeout_s
    while True:
        rows = {d["id"]: d for d in api.list_docs(client, staff_token)["list"]}
        if all(rows.get(i, {}).get("status") == 1 for i in doc_ids):
            return
        if time.time() >= deadline:
            raise TimeoutError(f"评测文档未在 {timeout_s}s 内就绪: {doc_ids}")
        time.sleep(interval_s)


def delete_eval_docs(client, staff_token):
    """删除全部 __eval__ 前缀文档（跑后清理）"""
    for doc in api.list_docs(client, staff_token)["list"]:
        if str(doc["name"]).startswith(EVAL_DOC_PREFIX):
            api.delete_doc(client, staff_token, doc["id"])


def _reference_names(events):
    """取 references 事件中的来源文件名列表（无则空列表）"""
    for name, payload in events:
        if name == "references" and isinstance(payload, list):
            return [str(ref.get("name", "")) for ref in payload if isinstance(ref, dict)]
    return []
