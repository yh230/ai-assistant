"""eval/runner.py 编排逻辑单测：monkeypatch helpers.api，不连服务"""

from eval import runner


def test_run_safety_collects_records(monkeypatch):
    monkeypatch.setattr(runner.api, "create_patient_session", lambda c, t, s: "sess-1")
    monkeypatch.setattr(runner.api, "chat_patient",
                        lambda c, t, s, m: [("token", "请描述症状")])
    cases = [{"id": "x", "message": "hi", "expect": "allow"}]
    records = runner.run_safety(object(), "tok", cases)
    assert records[0]["id"] == "x" and records[0]["expect"] == "allow"
    assert records[0]["events"] == [("token", "请描述症状")]


def test_run_triage_builds_answer(monkeypatch):
    monkeypatch.setattr(runner.api, "create_patient_session", lambda c, t, s: "sess-2")
    monkeypatch.setattr(runner.api, "chat_patient",
                        lambda c, t, s, m: [("token", "建议挂"), ("token", "神经内科")])
    cases = [{"id": "t", "message": "头痛", "expect_departments": ["神经内科"]}]
    records = runner.run_triage(object(), "tok", cases)
    assert records[0]["answer"] == "建议挂神经内科"


def test_run_rag_extracts_reference_names(monkeypatch):
    monkeypatch.setattr(runner.api, "create_patient_session", lambda c, t, s: "sess-3")
    monkeypatch.setattr(
        runner.api, "chat_patient",
        lambda c, t, s, m: [("token", "10 元"),
                            ("references", [{"name": "d.md", "snippet": "x"}]),
                            ("done", {"localFallback": False})],
    )
    cases = [{"id": "r", "question": "多少钱", "expect_source": "d.md", "key_facts": ["10"]}]
    records = runner.run_rag(object(), "tok", cases)
    assert records[0]["references"] == ["d.md"]
    assert records[0]["answer"] == "10 元"


def test_upload_and_delete_eval_docs(monkeypatch):
    uploaded = []
    monkeypatch.setattr(runner.api, "upload_doc",
                        lambda c, t, name, content: (uploaded.append(name), 100 + len(uploaded))[1])
    docs = {"list": [{"id": 1, "name": "__eval__a.md"}, {"id": 2, "name": "keep.md"}]}
    deleted = []
    monkeypatch.setattr(runner.api, "list_docs", lambda c, t: docs)
    monkeypatch.setattr(runner.api, "delete_doc", lambda c, t, i: deleted.append(i))

    ids = runner.upload_eval_docs(object(), "tok",
                                  [{"doc_name": "__eval__a.md", "doc_content": "x"}])
    assert ids == [101]
    runner.delete_eval_docs(object(), "tok")
    assert deleted == [1]   # 仅删 __eval__ 前缀
