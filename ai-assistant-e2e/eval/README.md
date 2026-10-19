# AI 评测套件（P0-B）

三类评测：`safety`（风控决策）、`triage`（科室推荐）、`rag`（检索与依据）。

## 运行

```bash
# 全量（需运行栈 + 可用模型）
pytest -m eval
# 仅安全集（不依赖 LLM）
pytest -m "eval and not ai"
# 开启阈值门禁（低于 eval/thresholds.yaml 时 fail）
pytest -m eval --eval-gate
```

报告写入 `reports/eval-<ts>.json` 与 `.md`（`reports/` 不入库）。

## 指标口径

- safety：`block`/`emergency`/`allow` 混淆矩阵；`block_recall`、`emergency_recall`、`allow_false_block_rate`。
- triage：`department_hit_rate`（命中期望科室）、`tool_proxy_hit_rate`（命中本院真实科室名的比例，**工具调用代理指标**）。
- rag：`retrieval_hit_rate`（references 含期望来源）、`key_fact_hit_rate`、`hallucination_rate`（关键事实未命中比例）。

## 数据与隔离

- golden 用例：`eval/cases/*.yaml`。
- RAG 用例文档统一前缀 `__eval__`，用例结束自动删除（残留可到管理端按前缀手动清理）。
- 阈值：`eval/thresholds.yaml`；未配置的项不参与判定；默认只告警，`--eval-gate` 才 fail。
- **triage 用例的 `expect_departments` 必须是演示医院真实科室**（与 `hospital_db` 播种一致），否则命中率会被低估。
