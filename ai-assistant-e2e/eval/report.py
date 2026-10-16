"""评测报告构建与落盘（纯序列化，不依赖服务）"""

import json
from pathlib import Path


def build_report(metrics, per_case, gate_enabled, violations, base_url, timestamp):
    """组装报告结构"""
    return {
        "timestamp": timestamp,
        "base_url": base_url,
        "metrics": metrics,
        "per_case": per_case,
        "gate": {"enabled": gate_enabled, "violated": list(violations)},
    }


def render_markdown(report):
    """渲染 Markdown 摘要（指标表 + 门禁）"""
    lines = [f"# AI 评测报告 {report['timestamp']}", "", f"- base_url: {report['base_url']}", ""]
    for section in ("safety", "triage", "rag"):
        metrics = report["metrics"].get(section)
        if not metrics:
            continue
        lines.append(f"## {section}")
        for key, value in metrics.items():
            if key == "matrix":
                continue
            if isinstance(value, float):
                lines.append(f"- {key}: {value:.3f}")
            else:
                lines.append(f"- {key}: {value}")
        lines.append("")
    gate = report["gate"]
    lines.append(f"## 门禁 (enabled={gate['enabled']})")
    if gate["violated"]:
        lines += [f"- 违规: {v}" for v in gate["violated"]]
    else:
        lines.append("- 无违规")
    return "\n".join(lines) + "\n"


def write_report(report, out_dir):
    """写出 eval-<ts>.json 与 .md，返回两个路径"""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = (report["timestamp"].replace(":", "").replace("-", "").replace("+", "_"))
    json_path = out_dir / f"eval-{stamp}.json"
    md_path = out_dir / f"eval-{stamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(render_markdown(report), encoding="utf-8")
    return json_path, md_path
