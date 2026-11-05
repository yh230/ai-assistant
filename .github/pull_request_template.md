## 变更说明

<!-- 这个 PR 解决了什么问题？关联 Issue 请使用 #123。 -->

## 主要改动

-

## 验证方式

<!-- 列出实际执行过的命令或手工验证步骤。 -->

- [ ] 后端单测：`cd ai-assistant-backend && ./mvnw test`
- [ ] 前端单测与构建（仓库根目录）：`npm ci && npm test && npm run build`
- [ ] data-platform 单测：`cd ai-assistant-data-platform && python -m unittest discover -s tests/unit`
- [ ] API E2E 确定性用例：`cd ai-assistant-tests/api && pytest -m "not ai and not slow"`（如适用）
- [ ] UI E2E：`cd ai-assistant-tests/ui && pytest`（如适用）
- [ ] 手工验证：

## 截图 / 录屏

<!-- UI 变更请附上前后对比；无 UI 变更可删除本节。 -->

## 检查清单

- [ ] 变更范围与需求一致，未夹带无关改动
- [ ] 新增逻辑有测试，或说明了无法自动化的原因
- [ ] 未提交真实密钥、令牌、患者数据或本机绝对路径
- [ ] 涉及权限/租户/AI 写工具时，已覆盖越权场景
- [ ] 文档与配置示例已同步
