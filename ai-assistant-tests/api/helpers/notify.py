"""测试结果推送：JUnit 报告解析 + 可插拔通知通道（企微/飞书/邮件）。

通道按 E2E_NOTIFY_CHANNELS 配置构建（见 build_notifiers）；新增通道实现
Notifier 抽象类并在 build_notifiers 注册即可。
"""

import smtplib
import xml.etree.ElementTree as ET
from abc import ABC, abstractmethod
from collections.abc import Mapping
from dataclasses import dataclass, field
from email.message import EmailMessage
from email.utils import formatdate
from pathlib import Path

import httpx

OK_ICON = "✅"
FAIL_ICON = "❌"


@dataclass
class ResultSummary:
    """一次运行的结果摘要（含失败用例清单）"""

    total: int = 0
    passed: int = 0
    failed: int = 0
    skipped: int = 0
    duration_s: float = 0.0
    failed_cases: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.failed == 0


def parse_junit(path: Path | str) -> ResultSummary:
    """解析 pytest junitxml 报告为结果摘要"""
    root = ET.parse(str(path)).getroot()
    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    summary = ResultSummary()
    for suite in suites:
        summary.total += int(suite.get("tests", "0"))
        summary.failed += int(suite.get("failures", "0")) + int(suite.get("errors", "0"))
        summary.skipped += int(suite.get("skipped", "0"))
        summary.duration_s += float(suite.get("time", "0"))
        for case in suite.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                classname = case.get("classname", "")
                name = case.get("name", "")
                summary.failed_cases.append(f"{classname}::{name}" if classname else name)
    summary.passed = summary.total - summary.failed - summary.skipped
    return summary


def format_summary_text(summary: ResultSummary, report_path: str = "") -> str:
    """三通道共用的摘要文案"""
    icon = OK_ICON if summary.ok else FAIL_ICON
    lines = [
        f"【AI 医院助手 E2E】{icon} 失败 {summary.failed} / 通过 {summary.passed} / "
        f"跳过 {summary.skipped}（耗时 {summary.duration_s:.0f}s）",
    ]
    if summary.failed_cases:
        lines.append("失败用例：")
        lines.extend(f"- {case}" for case in summary.failed_cases)
    if report_path:
        lines.append(f"报告：{report_path}")
    return "\n".join(lines)


class Notifier(ABC):
    """通知通道抽象基类：实现 name 与 send 即可接入新通道"""

    name: str = "unnamed"

    @abstractmethod
    def send(self, summary: ResultSummary, report_path: Path) -> None:
        """发送结果摘要；失败应抛异常，由调用方逐通道兜底"""


class WecomNotifier(Notifier):
    """企业微信群机器人（markdown 消息）"""

    name = "wecom"

    def __init__(self, webhook_url: str):
        self._webhook_url = webhook_url

    def send(self, summary: ResultSummary, report_path: Path) -> None:
        payload = {
            "msgtype": "markdown",
            "markdown": {"content": format_summary_text(summary, str(report_path))},
        }
        resp = httpx.post(self._webhook_url, json=payload, timeout=10)
        resp.raise_for_status()
        body = resp.json()
        if body.get("errcode") != 0:
            raise RuntimeError(f"企微返回错误：{body}")


class FeishuNotifier(Notifier):
    """飞书群机器人（text 消息）"""

    name = "feishu"

    def __init__(self, webhook_url: str):
        self._webhook_url = webhook_url

    def send(self, summary: ResultSummary, report_path: Path) -> None:
        payload = {
            "msg_type": "text",
            "content": {"text": format_summary_text(summary, str(report_path))},
        }
        resp = httpx.post(self._webhook_url, json=payload, timeout=10)
        resp.raise_for_status()
        body = resp.json()
        # 飞书 v2 webhook 成功返回 {"code":0}，旧版为 {"StatusCode":0}，两者兼容
        if body.get("code", body.get("StatusCode")) != 0:
            raise RuntimeError(f"飞书返回错误：{body}")


def build_email_message(summary: ResultSummary, report_path: Path, sender: str,
                        recipients: list[str]) -> EmailMessage:
    """构建邮件：正文摘要 + JUnit 报告附件（便于单测，不发送）"""
    icon = OK_ICON if summary.ok else FAIL_ICON
    msg = EmailMessage()
    msg["Subject"] = (f"[AI 医院助手 E2E] {icon} 失败 {summary.failed} / "
                      f"通过 {summary.passed} / 跳过 {summary.skipped}")
    msg["From"] = sender
    msg["To"] = ", ".join(recipients)
    msg["Date"] = formatdate(localtime=True)
    msg.set_content(format_summary_text(summary))
    if report_path.exists():
        msg.add_attachment(report_path.read_bytes(), maintype="application",
                           subtype="xml", filename=report_path.name)
    return msg


class EmailNotifier(Notifier):
    """SMTP 邮件（SSL）：正文摘要 + JUnit 报告附件"""

    name = "email"

    def __init__(self, host: str, port: int, user: str, password: str,
                 sender: str, recipients: list[str]):
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._sender = sender
        self._recipients = recipients

    def send(self, summary: ResultSummary, report_path: Path) -> None:
        msg = build_email_message(summary, report_path, self._sender, self._recipients)
        with smtplib.SMTP_SSL(self._host, self._port, timeout=15) as smtp:
            smtp.login(self._user, self._password)
            smtp.send_message(msg)


def _split_channels(value: str) -> list[str]:
    return [part.strip().lower() for part in (value or "").split(",") if part.strip()]


def _split_recipients(value: str) -> list[str]:
    return [part.strip() for part in (value or "").split(",") if part.strip()]


def build_notifiers(env: Mapping[str, str]) -> list[Notifier]:
    """按 E2E_NOTIFY_CHANNELS（逗号分隔：wecom,feishu,email）构建通道。

    未配置地址/收件人或未知通道名：跳过并打印告警，不抛异常。
    """
    notifiers: list[Notifier] = []
    for channel in _split_channels(env.get("E2E_NOTIFY_CHANNELS", "")):
        if channel == "wecom" and env.get("E2E_WECOM_WEBHOOK", "").strip():
            notifiers.append(WecomNotifier(env["E2E_WECOM_WEBHOOK"].strip()))
        elif channel == "feishu" and env.get("E2E_FEISHU_WEBHOOK", "").strip():
            notifiers.append(FeishuNotifier(env["E2E_FEISHU_WEBHOOK"].strip()))
        elif channel == "email" and env.get("E2E_SMTP_HOST", "").strip() \
                and _split_recipients(env.get("E2E_SMTP_TO", "")):
            notifiers.append(EmailNotifier(
                host=env["E2E_SMTP_HOST"].strip(),
                port=int(env.get("E2E_SMTP_PORT") or "465"),
                user=env.get("E2E_SMTP_USER", "").strip(),
                password=env.get("E2E_SMTP_PASSWORD", ""),
                sender=(env.get("E2E_SMTP_FROM") or env.get("E2E_SMTP_USER", "")).strip(),
                recipients=_split_recipients(env.get("E2E_SMTP_TO", "")),
            ))
        else:
            print(f"[NOTIFY] 通道 {channel} 未配置完整（或未知通道），已跳过")
    return notifiers
