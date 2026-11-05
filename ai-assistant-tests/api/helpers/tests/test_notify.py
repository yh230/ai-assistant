"""helpers/notify.py 纯逻辑单测（不真实发送：webhook 用 monkeypatch，邮件只构建不发送）"""

from pathlib import Path

import httpx
import pytest

from helpers.notify import (
    EmailNotifier,
    FeishuNotifier,
    ResultSummary,
    WecomNotifier,
    build_email_message,
    build_notifiers,
    format_summary_text,
    parse_junit,
)

SAMPLE_XML = """<?xml version="1.0" encoding="utf-8"?>
<testsuites>
  <testsuite name="pytest" errors="0" failures="1" skipped="1" tests="3" time="12.5">
    <testcase classname="tests.test_a" name="test_ok" time="1.0" />
    <testcase classname="tests.test_a" name="test_bad" time="2.0">
      <failure message="assert False">traceback...</failure>
    </testcase>
    <testcase classname="tests.test_b" name="test_skip" time="0.1">
      <skipped message="manual" />
    </testcase>
  </testsuite>
</testsuites>
"""

ALL_PASS_XML = """<?xml version="1.0" encoding="utf-8"?>
<testsuites>
  <testsuite name="pytest" errors="0" failures="0" skipped="0" tests="2" time="3.0">
    <testcase classname="tests.test_a" name="test_1" time="1.5" />
    <testcase classname="tests.test_a" name="test_2" time="1.5" />
  </testsuite>
</testsuites>
"""


def _write_xml(tmp_path: Path, content: str, name: str = "e2e-results.xml") -> Path:
    report = tmp_path / name
    report.write_text(content, encoding="utf-8")
    return report


def _fake_post(responses: list[dict]):
    calls = []

    def fake_post(url, json, timeout):
        calls.append({"url": url, "json": json, "timeout": timeout})
        return httpx.Response(200, json=responses[min(len(calls) - 1, len(responses) - 1)],
                              request=httpx.Request("POST", url))

    return calls, fake_post


def test_parse_junit_counts_and_failed_cases(tmp_path):
    summary = parse_junit(_write_xml(tmp_path, SAMPLE_XML))
    assert summary.total == 3
    assert summary.failed == 1
    assert summary.skipped == 1
    assert summary.passed == 1
    assert summary.duration_s == 12.5
    assert summary.failed_cases == ["tests.test_a::test_bad"]
    assert not summary.ok


def test_parse_junit_all_pass(tmp_path):
    summary = parse_junit(_write_xml(tmp_path, ALL_PASS_XML))
    assert summary.ok
    assert summary.passed == 2
    assert summary.failed_cases == []


def test_format_summary_text_contains_counts_failures_and_path():
    summary = ResultSummary(total=3, passed=1, failed=1, skipped=1, duration_s=12.5,
                            failed_cases=["tests.test_a::test_bad"])
    text = format_summary_text(summary, "reports/e2e-results.xml")
    assert "失败 1" in text and "通过 1" in text and "跳过 1" in text
    assert "tests.test_a::test_bad" in text
    assert "reports/e2e-results.xml" in text


def test_wecom_send_posts_markdown_payload(monkeypatch):
    calls, fake_post = _fake_post([{"errcode": 0, "errmsg": "ok"}])
    monkeypatch.setattr("helpers.notify.httpx.post", fake_post)

    WecomNotifier("https://wecom.example/hook").send(ResultSummary(total=2, passed=2), Path("x.xml"))

    assert calls[0]["url"] == "https://wecom.example/hook"
    assert calls[0]["json"]["msgtype"] == "markdown"
    assert "通过 2" in calls[0]["json"]["markdown"]["content"]


def test_wecom_send_raises_on_business_error(monkeypatch):
    calls, fake_post = _fake_post([{"errcode": 93000, "errmsg": "invalid webhook"}])
    monkeypatch.setattr("helpers.notify.httpx.post", fake_post)

    with pytest.raises(RuntimeError, match="93000"):
        WecomNotifier("https://wecom.example/hook").send(ResultSummary(), Path("x.xml"))


def test_feishu_send_posts_text_payload(monkeypatch):
    calls, fake_post = _fake_post([{"code": 0, "msg": "success"}])
    monkeypatch.setattr("helpers.notify.httpx.post", fake_post)

    FeishuNotifier("https://feishu.example/hook").send(ResultSummary(total=2, passed=2), Path("x.xml"))

    assert calls[0]["json"]["msg_type"] == "text"
    assert "通过 2" in calls[0]["json"]["content"]["text"]


def test_email_message_has_summary_and_attachment(tmp_path):
    report = _write_xml(tmp_path, ALL_PASS_XML)
    msg = build_email_message(ResultSummary(total=1, failed=1, failed_cases=["t::x"]),
                              report, "from@example.com", ["a@example.com", "b@example.com"])

    assert "[AI 医院助手 E2E]" in msg["Subject"]
    assert "t::x" in msg.get_body(preferencelist=("plain",)).get_content()
    assert [part.get_filename() for part in msg.iter_attachments()] == ["e2e-results.xml"]


def test_email_notifier_uses_ssl_smtp(monkeypatch, tmp_path):
    sent = {}

    class FakeSmtp:
        def __init__(self, host, port, timeout):
            sent["target"] = (host, port)

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def login(self, user, password):
            sent["login"] = (user, password)

        def send_message(self, msg):
            sent["msg"] = msg

    monkeypatch.setattr("helpers.notify.smtplib.SMTP_SSL", FakeSmtp)
    notifier = EmailNotifier("smtp.example.com", 465, "u@example.com", "pwd", "u@example.com", ["a@example.com"])
    notifier.send(ResultSummary(total=1, passed=1), _write_xml(tmp_path, ALL_PASS_XML, "r.xml"))

    assert sent["target"] == ("smtp.example.com", 465)
    assert sent["login"] == ("u@example.com", "pwd")
    assert sent["msg"]["To"] == "a@example.com"


def test_build_notifiers_selects_known_channels_and_skips_unconfigured(capsys):
    env = {
        "E2E_NOTIFY_CHANNELS": "wecom, feishu, email, dingtalk",
        "E2E_WECOM_WEBHOOK": "https://wecom.example/hook",
        "E2E_FEISHU_WEBHOOK": "",
        "E2E_SMTP_HOST": "smtp.example.com",
        "E2E_SMTP_PORT": "465",
        "E2E_SMTP_USER": "u@example.com",
        "E2E_SMTP_PASSWORD": "p",
        "E2E_SMTP_TO": "a@example.com,b@example.com",
    }
    notifiers = build_notifiers(env)
    assert [n.name for n in notifiers] == ["wecom", "email"]

    out = capsys.readouterr().out
    assert "feishu" in out and "dingtalk" in out


def test_build_notifiers_empty_or_absent_channels():
    assert build_notifiers({"E2E_NOTIFY_CHANNELS": ""}) == []
    assert build_notifiers({}) == []
