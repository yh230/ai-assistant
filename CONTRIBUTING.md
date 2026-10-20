# 贡献指南

欢迎 Issue、文档修正与 PR。这个项目以学习和展示为目的，代码可读性与边界清晰比“功能多”更重要。

## 开始之前

1. 阅读 [README](README.md) 的「环境要求」与「快速开始」。
2. 阅读 [架构说明](docs/design/architecture.md) 和 [阅读路线](docs/design/reading-guide.md)。
3. 搜索现有 Issue，避免重复。

## 分支与提交

- `main`：稳定分支，只接受 PR。
- `refactor/spring-cloud`：长期开发分支，请勿删除。
- 功能开发从 `main` 或当前开发分支切出，分支名建议 `feat/<topic>`、`fix/<topic>`、`docs/<topic>`。
- 提交信息使用 Conventional Commits：
  - `feat(ai): 支持多轮工具调用`
  - `fix(registration): 修复已支付预约过期释放`
  - `docs: 补充阅读路线`

## 代码约定

- 后端保持现有模块边界：域独立包根，公共能力放 `common` / `common-security` / `api`。
- 统一返回 `Result<T>` / `PageResult<T>`，业务失败抛 `BusinessException`。
- 控制器只做参数与权限；业务规则放 Service。
- 数据权限优先在查询层过滤；AI 写工具必须做服务端归属校验。
- AI 输出必须带免责文案，不得绕过输入/输出风控。
- 新增配置优先放 Nacos 共享配置，代码保留可本地启动的默认值。
- 新增业务字段需写清中文含义；注释只描述当前状态，不写变更历史。

## 测试

```bash
# 后端单测
cd ai-assistant-backend
./mvnw test                 # Windows: mvnw.cmd test

# 前端单测与构建
cd ai-assistant-frontend
npm ci
npm test
npm run build

# E2E 确定性用例（需服务已启动）
cd ai-assistant-e2e
pytest -m "not ai and not slow"
```

涉及 LLM 的用例不要放进 PR 必过流程，放在本地或 nightly 执行。

## PR 检查清单

- [ ] 变更范围与 Issue/需求一致，没有夹带无关改动
- [ ] 新增逻辑有对应测试，或说明了无法自动化的原因
- [ ] 未提交真实密钥、令牌、患者数据或本机绝对路径
- [ ] 涉及权限、租户、AI 写工具时，已覆盖越权场景
- [ ] 文档与配置示例同步更新
- [ ] CI 通过
