# AI 评测

AI 功能不能只靠“聊起来感觉不错”。仓库在 `ai-assistant-e2e/` 内提供了一套可重复的评测套件，用固定用例衡量安全、分诊与 RAG 三类能力。

## 1. 评测集结构

| 套件 | 关注点 | 是否依赖大模型 |
|------|--------|----------------|
| `safety` | 敏感输入拦截、急症识别、正常问题放行 | 否 |
| `triage` | 推荐科室是否命中、是否基于真实科室工具 | 是 |
| `rag` | 检索是否命中、关键事实是否覆盖、是否幻觉 | 是 |

用例是 `eval/cases/*.yaml` 中的 golden 数据；阈值在 `eval/thresholds.yaml`；运行器、指标与报告在 `eval/` 目录。

## 2. 指标口径

### 安全

- `block_recall`：应拦截的用例中被拦截的比例。
- `emergency_recall`：应触发急症红线的比例。
- `allow_false_block_rate`：正常问题被误拦的比例。

### 分诊

- `department_hit_rate`：推荐科室命中期望科室的比例。
- `tool_proxy_hit_rate`：回答中出现本院真实科室名的比例，用来近似判断是否调用了真实工具而非编造。

### RAG

- `retrieval_hit_rate`：`references` 中包含期望来源的比例。
- `key_fact_hit_rate`：回答覆盖关键事实的比例。
- `hallucination_rate`：关键事实未命中的比例，作为幻觉的代理指标。

## 3. 运行方式

```bash
cd ai-assistant-e2e

# 全量评测（需要运行栈与可用模型）
pytest -m eval

# 仅安全集（不依赖 LLM）
pytest -m "eval and not ai"

# 开启阈值门禁：低于阈值时 fail
pytest -m eval --eval-gate
```

报告写入 `reports/eval-<timestamp>.json` 与 `.md`，包含未命中明细。`reports/` 不入库。

## 4. 数据隔离

- RAG 评测文档统一使用 `__eval__` 前缀，用例结束自动删除；异常残留可在管理端按前缀清理。
- 评测不会修改既有知识库文档，也不会复用真实患者数据。
- 分诊用例的 `expect_departments` 必须是演示医院真实播种的科室，否则命中率会被低估。

## 5. 门禁策略

- 默认只生成报告并告警，不让评测阻塞日常开发。
- `--eval-gate` 才按阈值 fail，适合放在 nightly 或发布前流水线。
- 未在 `thresholds.yaml` 中配置的指标不参与判定，避免“为了全绿而全配”。

## 6. 已知限制

- 分诊与 RAG 依赖真实模型，结果会随模型版本、温度和知识库内容变化。
- `tool_proxy_hit_rate` 是工具调用的代理指标，不等于真实的工具调用参数正确率。
- RAG 引用目前到文档级，尚未做片段级人工标注。
- 幻觉率是“关键事实未命中”的代理口径，不能替代人工评审。
- 仓库暂未公开固定基线数据；建议在自己的环境跑完后，把报告中的聚合指标记录到 Release Notes。

## 7. 如何新增用例

1. 在 `eval/cases/` 对应 YAML 中追加一条用例，写清输入、期望结果与判定字段。
2. 安全用例优先覆盖否定句、繁简、空格拆分与注入句式。
3. 分诊用例使用本院真实科室名，并注明期望依据。
4. RAG 用例准备一段可检索的评测文档，关键事实要能从片段中直接得到。
5. 本地先跑单套件，确认不是数据问题，再跑全量并更新阈值。

## 8. 与 CI 的关系

LLM 相关用例需要模型密钥与运行栈，不适合在每个 PR 上执行。建议：

- PR：只跑离线单测与 `pytest -m "not ai and not slow"`。
- nightly / release：跑 `pytest -m eval --eval-gate`，把报告归档。

更多命令与用例映射见 [E2E 套件说明](../../ai-assistant-e2e/README.md)。
