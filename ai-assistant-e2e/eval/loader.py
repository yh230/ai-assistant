"""评测用例与阈值加载（纯函数，不依赖环境）"""

from pathlib import Path

import yaml

EVAL_DIR = Path(__file__).resolve().parent
CASES_DIR = EVAL_DIR / "cases"
THRESHOLDS_PATH = EVAL_DIR / "thresholds.yaml"

# 各评测集的必需字段
REQUIRED_KEYS = {
    "safety": {"id", "message", "expect"},
    "triage": {"id", "message", "expect_departments"},
    "rag": {"id", "doc_name", "doc_content", "question", "expect_source", "key_facts"},
}


def load_cases(set_name, cases_dir=CASES_DIR):
    """加载 <set_name>.yaml 并校验：非空 list[dict]、含非空 id、含该集合必需字段"""
    if set_name not in REQUIRED_KEYS:
        raise ValueError(f"未知评测集: {set_name}")
    path = Path(cases_dir) / f"{set_name}.yaml"
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, list) or not data:
        raise ValueError(f"{path} 应为非空列表")
    required = REQUIRED_KEYS[set_name]
    for i, item in enumerate(data):
        if not isinstance(item, dict):
            raise ValueError(f"{path} 第 {i + 1} 项应为映射")
        missing = required - set(item)
        if missing:
            raise ValueError(f"{path} 第 {i + 1} 项缺少字段: {sorted(missing)}")
        if not str(item.get("id", "")).strip():
            raise ValueError(f"{path} 第 {i + 1} 项 id 为空")
    return data


def load_thresholds(path=THRESHOLDS_PATH):
    """加载阈值配置；文件缺失或非映射时返回空映射"""
    p = Path(path)
    if not p.exists():
        return {}
    data = yaml.safe_load(p.read_text(encoding="utf-8"))
    return data if isinstance(data, dict) else {}
