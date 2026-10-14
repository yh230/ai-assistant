"""清单节四：RAG 知识库（#15-#18）。

#15 确定性硬断言；#16-#18 标记 ai（文档命中的问答内容由大模型生成）。
"""

import time
from pathlib import Path

import pytest

from helpers import api
from helpers.sse import full_text

NONCE = f"E2E-DOC-{int(time.time())}"
DISCLAIMER_KEY = "AI内容仅供参考，不能替代执业医师诊断"
QUESTION = "脑电生物反馈治疗的项目代号是什么？"


@pytest.fixture(scope="module")
def rag(client, cfg, staff1, new_patient):
    """知识库链：本院上传样例文档 → 本医院患者 RAG 会话；teardown 兜底清理"""
    sample = Path(__file__).resolve().parents[1] / "assets" / "sample_doc.md"
    content = sample.read_text(encoding="utf-8").replace("{{NONCE}}", NONCE).encode("utf-8")
    file_name = f"e2e_{NONCE}.md"
    doc_id = api.upload_doc(client, staff1.token, file_name, content)

    patient = new_patient(cfg["hospital1"])
    session_id = api.create_patient_session(client, patient.token, "RAG")
    yield {"doc_id": doc_id, "file_name": file_name, "patient": patient, "session_id": session_id}

    # teardown：按前缀清理（#18 正常路径已删除时此处为空操作）
    for doc in api.list_docs(client, staff1.token)["list"]:
        if doc["name"].startswith("e2e_E2E-DOC-"):
            api.delete_doc(client, staff1.token, doc["id"])


def test_knowledge_upload_listing_and_validation(client, cfg, admin, staff1, hospitals):
    """#15 上传 md 入列（含切片数）；非 md/txt、超 2MB、未绑定医院三类拒绝"""
    nonce = f"E2E-DOC-{int(time.time())}-V"
    content = f"# E2E 校验文档 {nonce}\n切片校验内容。".encode("utf-8")
    doc_id = api.upload_doc(client, staff1.token, f"e2e_{nonce}.md", content)
    try:
        page = api.list_docs(client, staff1.token)
        row = next(d for d in page["list"] if d["id"] == doc_id)
        assert row["name"] == f"e2e_{nonce}.md"
        assert row["chunkCount"] and row["chunkCount"] > 0

        with pytest.raises(api.ApiError) as ei:
            api.upload_doc(client, staff1.token, "e2e_bad.pdf", b"%PDF-1.4 e2e", "application/pdf")
        assert "md/txt" in str(ei.value)

        big = b"x" * (2 * 1024 * 1024 + 10)
        with pytest.raises(api.ApiError) as ei2:
            api.upload_doc(client, staff1.token, f"e2e_{nonce}_big.txt", big, "text/plain")
        assert "2MB" in str(ei2.value)

        with pytest.raises(api.ApiError) as ei3:
            api.upload_doc(client, admin.token, f"e2e_{nonce}_adm.md", content)
        assert "未绑定医院" in str(ei3.value)
    finally:
        api.delete_doc(client, staff1.token, doc_id)


@pytest.mark.ai
def test_rag_answers_from_uploaded_document(client, rag):
    """#16 命中文档：回答包含文档内部代号，且带免责尾注"""
    events = api.chat_patient(client, rag["patient"].token, rag["session_id"], QUESTION)
    answer = full_text(events)
    assert NONCE in answer, f"未命中已上传文档（回答未见代号）：{answer[-200:]}"
    assert DISCLAIMER_KEY in answer


@pytest.mark.ai
def test_rag_cross_hospital_isolation(client, cfg, rag, staff1, staff2, new_patient):
    """#17 跨院隔离：他院列表不可见该文档；他院患者问不出内部代号"""
    assert any(d["id"] == rag["doc_id"] for d in api.list_docs(client, staff1.token)["list"])
    assert all(d["id"] != rag["doc_id"] for d in api.list_docs(client, staff2.token)["list"]), \
        "他院知识库列表不应看到该文档"

    patient2 = new_patient(cfg["hospital2"])
    session2 = api.create_patient_session(client, patient2.token, "RAG")
    events = api.chat_patient(client, patient2.token, session2, QUESTION)
    assert NONCE not in full_text(events), "他院患者不应检索到该文档内容"


@pytest.mark.ai
def test_rag_delete_document_removes_hits(client, rag, staff1):
    """#18 删除文档后命中消失（本用例兼做清理）"""
    api.delete_doc(client, staff1.token, rag["doc_id"])
    assert all(d["id"] != rag["doc_id"] for d in api.list_docs(client, staff1.token)["list"])

    events = api.chat_patient(client, rag["patient"].token, rag["session_id"],
                              "再确认一次：脑电生物反馈治疗的项目代号是什么？")
    assert NONCE not in full_text(events), "删除后不应再命中文档内容"
