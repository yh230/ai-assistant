# AI 医院助手（AI Hospital Assistant）

> 基于 **Spring Cloud 微服务 + Spring AI** 的医疗场景 AI 助手平台：聊天 / 导诊 / 智能挂号 / 知识库问答，同时内置完整的医院业务与 RBAC 权限体系。
>
> A microservice-based AI hospital assistant built with Spring Cloud, Spring AI and Vue 3 — chat, triage, smart registration and RAG Q&A.

![Java](https://img.shields.io/badge/Java-17-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F.svg)
![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-Nacos-1677FF.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-6DB33F.svg)
![Vue](https://img.shields.io/badge/Vue-3-42B883.svg)
![MySQL](https://img.shields.io/badge/MySQL-8-4479A1.svg)
![E2E](https://img.shields.io/badge/E2E-pytest-0A9EDC.svg)

## 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [端口一览](#端口一览)
- [默认账号](#默认账号)
- [生产部署安全前置](#生产部署安全前置)
- [自动化测试](#自动化测试)
- [构建](#构建)
- [常见问题](#常见问题)
- [贡献](#贡献)
- [许可](#许可)

## 项目简介

一个**学习 / 展示用途**的完整案例：把「Spring Cloud 微服务实践」与「大模型应用落地（Tool Calling / RAG / SSE 流式）」放在同一套可运行的医院业务系统中。员工端与管理端面向医院员工，患者端面向患者账号。

> AI 输出仅辅助参考，**不提供诊断与处方**；产品层强制附带免责文案，并内置输入/输出双向风控。

- 员工端：`http://localhost:5173` （聊天 / 人设 / 天气 / 医院业务 / 系统管理）
- 患者端：`http://localhost:5173/patient/login` （注册登录 → AI 导诊 / 知识库问答 / 闲聊）

## 功能特性

**AI 能力**

- 智能聊天：SSE 逐 token 流式输出、多会话管理、Markdown 渲染；Tool Calling 对话中直接查真实天气
- AI 导诊：基于候诊描述的科室推荐（真实科室数据，不编造）；可衔接号源查询
- AI 智能挂号：工具调用 + **二次确认状态机**（pending_action 表 + SSE `confirm_required` 事件），确认/取消/超时全链路
- 轻量 RAG 知识库：管理员上传 md/txt（≤2MB）自动切片与向量化（本地 ONNX 嵌入模型），问答按医院隔离检索
- 引用来源与急症红线：RAG 回答附参考来源；急症症状自动引导急诊
- 安全底座：输入风控（敏感词直接拦截、不调模型）、输出风控、SSE 加固、限流（网关令牌桶）
- 双身份体系：员工（STAFF）与患者（PATIENT）JWT 身份隔离，患者账号绑定医院、数据按医院边界隔离

**业务模块**

- 医院人员与出勤：多医院 / 多科室、按【医院-科室-角色】三级数据隔离、出勤多维统计
- 门诊挂号：排班号源、预约取号、退号就诊、多维度统计（含公开的患者预约入口）
- RBAC 员工权限：Spring Security + JWT（双令牌续期）+ 角色/菜单/按钮权限、个人中心、踢下线
- 安全加固：登录失败锁定（默认 5 次锁 15 分钟）、公开接口限流、关键操作审计（`sys_oper_log`）
- 可观测性：全链路追踪（Zipkin）+ Prometheus 指标 + traceId 日志
- Kafka 异步解耦：站内信通知 / 审计落库 / 知识库异步向量化（幂等消费 + 死信队列）

## 系统架构

```text
┌──────────────┐        ┌───────────────────────────────┐
│ 前端 Vue 3    │  HTTP  │ 网关 gateway :8080            │
│ dev :5173    │ ─────▶ │ 统一鉴权 · 身份隔离 · 限流      │
└──────────────┘        └───────────────┬───────────────┘
                                        │ 路由转发（Nacos 服务发现）
      ┌──────────────┬──────────────┬───┴──────────┬──────────────────┐
      ▼              ▼              ▼              ▼                  ▼
 auth-service  system-service  hospital-service  registration-service  ai-chat-service
   :9200          :9201            :9202              :9203              :9204
 登录/令牌       用户/RBAC       医院/出勤/科室     排班/号源/挂号    聊天/AI 导诊/RAG
      └──────────────┴──────────────┴──────────────┴──────────────────┘
                                        │
       ┌───────────┬────────────────────┴────────────┬──────────────┐
       ▼           ▼                                 ▼              ▼
   MySQL 8     Redis（≥6.2）                     Nacos 3.x      DeepSeek API
 （四库分治）  登录态/权限快照/限流/令牌白名单    注册+配置中心   （可选，未配置自动本地降级）
```

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 17 · Spring Boot 3.5.16 · Spring Cloud（Nacos 注册与配置）· Spring AI 1.0 |
| 鉴权与缓存 | Spring Security 6 + JWT（jjwt 0.12）+ Redis 撤销（登出黑名单 / 踢下线 / refresh 轮换） |
| ORM | MyBatis-Plus 3.5（RBAC / 医院 / 挂号）· Spring Data JPA（chat 模块） |
| 服务间调用 | OpenFeign（`api` 模块，统一内部令牌） |
| 数据 | MySQL 8（四库分治）· Redis（≥ 6.2，刷新令牌消费用 GETDEL） |
| 前端 | Vue 3 + Vite 5 + Element Plus 2.14（按需自动引入）+ 青绿主题（#0D9488）+ vue-router 4 + axios |
| 测试 | JUnit（后端单测）· Python + pytest + httpx（API 级 E2E）|

## 项目结构

```text
aidaima/
├── ai-assistant-backend/     # 后端：父工程 + 9 模块
│   ├── gateway/              # 网关：统一入口、鉴权、路由、限流
│   ├── common/               # 公共：Result/PageResult、异常、数据权限
│   ├── common-security/      # 安全公共组件：JWT、内部调用校验、Security 配置
│   ├── api/                  # 服务间 Feign 接口与 DTO
│   └── services/             # auth / system / hospital / registration / ai-chat
├── ai-assistant-frontend/    # 前端：Vue 3 + Vite + Element Plus（员工端 + 患者端）
├── ai-assistant-e2e/         # API 级 E2E 自动化测试（Python + pytest + httpx）
├── scripts/                  # 一键脚本：Nacos / 服务 / E2E（含共享配置样例）
```

## 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | 自行安装并配置 `JAVA_HOME` |
| MySQL | 8 | 需先建好四个库（见快速开始第 1 步） |
| Redis | ≥ 6.2 | 默认 `localhost:6379`（刷新令牌消费用 GETDEL） |
| Node.js | 18+ | 前端构建与开发 |
| Nacos | 3.x | 服务端口 8848，控制台 8849（安装路径自定，见快速开始第 2 步） |

## 快速开始

### 1. 初始化数据库

一条命令创建全部四库与全部表（可重复执行）：

```bash
mysql -uroot -p < sql/init.sql
```

`sql/init.sql` 含 `system_db` / `hospital_db` / `registration_db` / `chat_db` 四库的建库与建表；角色/菜单/演示数据由各服务首次启动时自动播种。

> 存量库升级：结构变更已并入 `sql/init.sql`（对已存在表为幂等）。

### 2. 启动 Nacos

运行 `scripts/start-nacos.bat`，控制台 `http://localhost:8849`（首次登录按提示初始化 admin 口令）。

### 3. 创建共享配置

Nacos 控制台「配置管理 → 新建」：dataId `common-shared.yaml`、group `DEFAULT_GROUP`，内容粘贴 `scripts/nacos-common-shared.yaml` 全文（含 JWT 密钥、内部调用令牌、Redis 地址，AI 配置段 `ai.*`：prompt / 免责文案 / 风控词 / RAG 参数 / 限流阈值，以及 `spring.ai.model.embedding: none`）。

### 4. 配置密钥（环境变量）

```bat
setx DB_PASSWORD 你的MySQL密码
setx DEEPSEEK_API_KEY sk-你的大模型Key
setx AMAP_KEY 你的高德Key
```

设置后重开终端生效；开发机也可直接改各服务 yml 占位符默认值，但不要提交真实密钥。

### 5. 启动后端

运行 `scripts/start-services.bat`（六个窗口：五业务服务 + 网关），各窗口日志出现 `Started` 后进入下一步。

> **ai-chat-service 首次启动**：知识库向量嵌入使用本地 ONNX 模型（`spring-ai-transformers`，all-MiniLM-L6-v2），首次启动会从 GitHub 下载并加载，可能明显变慢（缓存于 `%TEMP%\spring-ai-onnx-generative\`；网络受限时确保代理可用，或改用离线预置：把模型 `.onnx` 放到任意路径，在 Nacos `ai.rag.embedding-model-path` 填完整路径，tokenizer 需与模型同目录且命名为「模型名去 `.onnx` + `-tokenizer.json`」）。未配置 `DEEPSEEK_API_KEY` 时对话自动降级本地回复引擎，不影响启动。
>
> 知识库向量文件目录 `data/vector/`（Nacos `ai.rag.vector-path`，相对服务启动目录，按医院分文件 `hospital_{id}.json`）为运行产物，不随仓库分发，请勿手工编辑。

### 6. 启动前端

```bash
cd ai-assistant-frontend
npm install
npm run dev
```

访问 `http://localhost:5173`（员工端，代理已指向网关 8080）；患者端入口 `http://localhost:5173/patient/login`。

## 端口一览

| 组件 | 端口 |
|------|------|
| 前端（Vite dev） | 5173 |
| 网关 gateway | 8080 |
| auth-service | 9200 |
| system-service | 9201 |
| hospital-service | 9202 |
| registration-service | 9203 |
| ai-chat-service | 9204 |
| Nacos | 8848（控制台 8849） |

## 默认账号

- `admin / admin123` —— 首次启动由 system-service 播种，**请尽快修改密码**
- 登录后可在「用户管理」创建员工并分配医院 / 科室 / 角色

## 生产部署安全前置

- **服务端口仅内网**：9200-9204 为 auth/system/hospital/registration/ai-chat 直连端口，生产只暴露网关（8080），并将 9200-9204 限制在内网或容器网络，禁止公网访问（`/inner/**` 仅凭内部令牌保护）。
- **强制覆盖默认密钥**：生产 Nacos 必须覆盖 JWT 密钥、内部调用令牌（`security.internal.token`）、数据库密码、CORS 白名单（`security.cors.allowed-origins`，禁止 `*`）与短信配置；保留默认值时 `prod` profile 启动即失败。
- **权限变更后清快照**：权限/角色播种或调整后，需清理 Redis `auth:user:*` 权限快照（或要求相关账号重新登录），否则旧快照最长存活一个缓存周期。

## 自动化测试

`ai-assistant-e2e/` 提供 API 级 E2E 回归套件（Python + pytest + httpx，**全黑盒**只经网关调用），覆盖身份越权、AI 导诊、挂号二次确认、RAG 跨院隔离、限流/风控、会话隔离等 20+ 验收项：

```bat
scripts\run-e2e.bat                            :: 全量（前置自检 + 执行 + JUnit 报告）
scripts\run-e2e.bat -m "not ai and not slow"   :: 离线子集（不依赖大模型）
```

结果支持推送到**企业微信 / 飞书 / 邮箱**（`.env` 配置通道，跑完自动推送）。详见 [ai-assistant-e2e/README.md](ai-assistant-e2e/README.md)。

## 构建

```bat
cd ai-assistant-backend
mvn -DskipTests clean package
```

需 JDK 17 与 Maven 3.9+，并确保 `JAVA_HOME` 指向 JDK 17；依赖可配置阿里云镜像加速（Maven `conf/settings.xml`）。

## 常见问题

| 问题 | 处理 |
|------|------|
| 服务注册失败（启动日志报 Nacos 连接错误） | 先 `scripts/start-nacos.bat`，确认控制台 8849 可访问、共享配置已创建 |
| 端口占用 | `netstat -ano | findstr <端口>` 查 PID 后结束进程（8080 / 9200-9204 / 8848 / 8849 / 5173） |
| GitHub 相关下载失败（如嵌入模型） | 确认本机可访问外网（如需代理请自行配置），或改用离线预置模型（见快速开始第 5 步） |
| 修改权限/角色后不生效 | 检查 Redis 权限快照与 auth-service 日志，必要时清缓存重新登录 |
| 知识库上传 >2MB 提示「系统繁忙」 | 将 `spring.servlet.multipart.max-file-size`（默认 1MB）调至 ≥3MB（Nacos 或服务 yml），使请求到达业务层并返回「超过 2MB」提示 |

## 贡献

欢迎 issue 与 PR。开发约定：

1. 后端遵循现有模块边界（域独立包根），公共能力放 `common` / `common-security` / `api`
2. 主分支受保护思路：功能开发开分支，PR 描述关联验收方式；提交信息使用 `feat/fix/docs/chore(scope):` 前缀
3. 涉及 AI 输出与患者数据的功能，须遵守「免责文案 + 医院边界隔离 + 风控拦截」三项底线

## 许可

本项目当前未附许可证文件，默认仅供学习与交流使用；如需开源分发，请先补充 `LICENSE`（例如 MIT）。

---

**致谢**：DeepSeek（大模型）· 高德开放平台（天气数据）· Alibaba Nacos · Spring AI（本地嵌入模型 all-MiniLM-L6-v2）
