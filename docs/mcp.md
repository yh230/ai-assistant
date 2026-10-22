# 排班查询 MCP Server

对外只读 MCP Server，让第三方 AI 客户端按医院查询未来一段时间的医生排班与号源余量。

## 能力边界

- 只读：不提供预约、退号等写操作。
- 按院隔离：API Key 绑定医院 ID 白名单，不能查询其他医院。
- 不返回患者信息、号位明细或内部 `registration_id`。
- v1 不提供 OAuth 2.1、用户级授权和 Key 管理后台。

## 传输与认证

| 项目 | 值 |
|------|----|
| 协议 | MCP / JSON-RPC 2.0 |
| 传输 | SSE |
| 建流 | `GET /mcp/sse` |
| 消息 | `POST /mcp/message` |
| 入口 | 网关 `http://<host>:8080/mcp/sse` |
| 认证 | `X-API-Key: <原始 Key>`，建流与每条消息都需携带 |

服务端使用 Spring AI 1.0.9 的 WebMVC MCP Server（SSE），客户端语言无关；Python、TypeScript、Java 等 MCP 客户端均可接入。

## 工具

| 工具 | 参数 | 说明 |
|------|------|------|
| `list_hospitals` | 无 | 返回当前 Key 可访问的医院 |
| `query_doctor_schedules` | `hospital_id`、`date_from`、`date_to`、`department_id`、`doctor_name`、`period` | 查询排班与余量 |

`query_doctor_schedules` 规则：

- 单院 Key 可省略 `hospital_id`；多院 Key 必须显式传。
- 日期格式 `yyyy-MM-dd`，默认今天到 `+6` 天，窗口最多 14 天。
- `period`：1=上午，2=下午。
- 返回 `items`、`truncated`、`dateFrom`、`dateTo`；单次最多 100 条。
- 余量按 `number_source.status=1` 实时统计，不使用排班冗余字段。

## Key 配置

在 Nacos `mcp-shared.yaml` 中配置：

```yaml
mcp:
  auth:
    keys:
      - consumer: partner-a
        key-sha256: "<64 位 SHA-256 hex>"
        hospital-ids: [1]
        rate-limit-per-minute: 60
        enabled: true
  cache:
    ttl-seconds: 45
```

生成 Key 与哈希：

```bash
python -c "import secrets,hashlib; k='mcp_'+secrets.token_hex(32); print('KEY =',k); print('HASH=',hashlib.sha256(k.encode()).hexdigest())"
```

- 明文 Key 只交给调用方，不写入仓库或 Nacos。
- `key-sha256` 必须是 64 位十六进制；`hospital-ids` 不能为空或包含 null。
- Key 轮换后重启 `mcp-server`，或确认 Nacos 刷新已生效。

## 错误与限制

- 工具业务错误通过 MCP `CallToolResult.isError=true` 返回，错误文本在 `content[0].text`。
- 缺失/无效 Key → 401；频率超限 → 429。
- 网关按 IP 限流，mcp-server 再按 Key 限流。
- 对外必须使用 HTTPS；`9205` 仅内网，不直接暴露公网。

## 验证

使用 MCP Inspector：

```bash
npx @modelcontextprotocol/inspector
# Transport: SSE
# URL: http://localhost:8080/mcp/sse
# Header: X-API-Key: <原始 Key>
```

端到端测试：

```bash
cd ai-assistant-e2e
pytest -m mcp -v
```

需要完整栈、Nacos Key 与 `MCP_E2E_API_KEY`。

## 相关实现

- `ai-assistant-backend/services/mcp-server`
- 内部查询：`GET /inner/schedules/query`（`X-Internal-Token`）
