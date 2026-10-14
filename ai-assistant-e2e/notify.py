"""测试结果推送入口：读取 JUnit 报告并按 .env 配置推送到企微/飞书/邮件。

用法：.venv\\Scripts\\python.exe notify.py [报告路径，默认 reports/e2e-results.xml]
推送失败仅打印告警，不影响测试退出码（run-e2e.bat 中先保存 pytest 退出码再调用本脚本）。
"""

import os
import sys
from pathlib import Path

from dotenv import load_dotenv

from helpers.notify import build_notifiers, parse_junit


def main(argv: list[str]) -> int:
    load_dotenv()
    report = Path(argv[1]) if len(argv) > 1 else Path("reports/e2e-results.xml")
    if not report.exists():
        print(f"[NOTIFY] 报告不存在：{report}（先运行测试，或传入正确路径）")
        return 1

    summary = parse_junit(report)
    notifiers = build_notifiers(os.environ)
    if not notifiers:
        print("[NOTIFY] 未配置推送通道（E2E_NOTIFY_CHANNELS 为空），跳过")
        return 0

    for notifier in notifiers:
        try:
            notifier.send(summary, report)
            print(f"[NOTIFY] {notifier.name} 已发送")
        except Exception as ex:  # 单通道失败不阻断其余通道
            print(f"[NOTIFY] {notifier.name} 发送失败：{type(ex).__name__}: {ex}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
