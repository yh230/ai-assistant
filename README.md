# AI 助手（聊天小伙伴）

基于 **Java 17 + Spring Boot 3.5.16 + Spring Cloud（Nacos）+ Spring AI 1.0 + Vue 3 + MySQL + Redis** 的微服务智能助手。

**四大能力**

- **智能聊天**：SSE 流式输出（逐 token 实时推送）、Tool Calling（对话中直接查真实天气）、多会话管理、Markdown 渲染；
- **天气查询**：实时天气 + 未来预报（高德开放平台数据）；
- **聊天人设管理**：人设角色存数据库，页面自由增删改（DB 驱动，首次启动自动播种默认角色）；
- **RBAC 员工权限**：Spring Security 登录鉴权（JWT + Redis 撤销）+ 角色/菜单/按钮权限（含个人中心、踢下线）。

**另有业务模块**

- **医院人员与出勤**：多医院 / 多科室，按【医院-科室-角色】三级数据隔离，出勤多维统计；
- **门诊挂号**：排班号源、预约取号、退号就诊、多维度统计（含公开的患者预约入口）；
- **AI 医院助手（患者端）**：患者独立账号（注册/登录，绑定医院）、AI 导诊、AI 智能挂号（工具调用 + 二次确认）、知识库问答（RAG，管理员上传 md/txt）、AI 问诊记录与知识库管理。

**患者端入口**：`http://localhost:5173/patient/login`（注册/登录）→ `http://localhost:5173/patient/chat`（导诊 / 知识库问答 / 闲聊）。

## 一、架构概览

前端（Vue 3，`:5173`）→ 网关（gateway `:8080`，统一鉴权/路由）→ 五个业务服务（auth / system / hospital / registration / chat），配合 Nacos（注册中心 + 配置中心）、四个数据库（system_db / hospital_db / registration_db / chat_db）与 Redis（登录态 / JWT 撤销）。

**后端 `ai-assistant-backend/`（父工程 + 9 模块）**

| 模块 | 说明 |
|------|------|
| `common/` | 公共模块：`Result`/`PageResult`、常量、异常、数据权限（`DataScope`/`DataScopeResolver`）、辅助工具 |
| `common-security/` | 安全公共组件：JWT、内部调用校验、Spring Security 配置 |
| `api/` | 服务间 OpenFeign 接口与 DTO（`AuthClient`/`UserClient`/`OrgClient`，含内部令牌拦截器） |
| `gateway/` | 网关：统一入口、路由转发、鉴权、黑名单与内部接口阻断 |
| `services/auth-service/` | 登录鉴权（JWT 签发、登出黑名单、踢下线） |
| `services/system-service/` | RBAC 用户中心：用户 / 角色 / 菜单 / 按钮权限 |
| `services/hospital-service/` | 医院 / 科室 / 员工 / 出勤（后端自动数据权限过滤） |
| `services/registration-service/` | 门诊挂号：排班 / 号源 / 预约取号 / 统计 |
| `services/ai-chat-service/` | 聊天 / 天气 / 人设 / 患者导诊与知识库 / 管理端（Spring AI + JPA，RAG 向量） |

| 数据库 | 归属服务 |
|--------|----------|
| `system_db` | system-service（RBAC 五表 + 患者账号表 `patient_user`） |
| `hospital_db` | hospital-service |
| `registration_db` | registration-service |
| `chat_db` | ai-chat-service（表由 JPA 首次启动自动创建） |

**前端 `ai-assistant-frontend/`**

| 目录 | 说明 |
|------|------|
| `src/api/` | axios 实例（拦截器统一处理鉴权与错误）+ 各模块接口封装 |
| `src/router/` `src/store/` | 路由表与守卫、登录态（轻量单例，无 Pinia） |
| `src/layout/` `src/views/` | 主布局（侧边栏按权限渲染，含「AI 助手」分组）+ 21 个业务页面（含患者端 2 页与管理端知识库/问诊记录页） |
| `src/components/` `src/composables/` | 通用组件（菜单树 / 轻提示）与管理页 CRUD 骨架 `useCrudPage` |
| `src/utils/` | 时间格式化（`format.js`）、权限常量与判定（`perm.js`）、轻提示（`message.js`） |
| `src/assets/` | 全局样式（含管理页公共样式段） |

**技术栈**

| 层级 | 技术 |
|------|------|
| 后端 | Java 17 / Spring Boot 3.5.16 / Spring Cloud（Nacos 注册与配置）/ Spring AI 1.0 |
| 鉴权与缓存 | Spring Security 6.5.11 + JWT（jjwt）+ Redis 撤销（登出黑名单/踢下线） |
| ORM | MyBatis-Plus（RBAC / 医院 / 挂号）+ Spring Data JPA（chat 模块） |
| 服务间调用 | OpenFeign（api 模块） |
| 数据库 | MySQL 8 |
| 前端 | Vue 3 + Vite 5 + vue-router 4 + axios（无 UI 组件库，手写 scoped CSS） |

## 二、环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | 本机路径 `C:\kaifa\jdk17` |
| MySQL | 8 | 需先建好四个库（见快速开始第 1 步） |
| Redis | - | 默认 `localhost:6379` |
| Node.js | 18+ | 前端构建与开发 |
| Nacos | 3.x | 本机路径 `C:\kaifa\nacos`（服务端口 8848，控制台 8849） |

## 三、快速开始（按顺序）

### 1. 初始化数据库

执行 `ai-assistant-backend/sql/` 下的四个建库脚本，顺序：`system_db.sql` → `hospital_db.sql` → `registration_db.sql` → `chat_db.sql`（脚本含建库；chat 相关表由 JPA 首次启动自动创建，无需手工建表）。

> AI 医院助手 V1.0 使用的 `system_db.patient_user`（患者账号表）已含在 `system_db.sql` 中；`registration.patient_id`（患者账号关联列）已含在 `registration_db.sql` 中。**已建库升级**：重跑 `system_db.sql` 补患者表、执行 `registration_db.sql` 末尾注释的 `ALTER TABLE registration ADD COLUMN patient_id ...` 补列（chat 库的 `pending_action`/`knowledge_document`/`chat_session` 新列由 JPA 自动补齐）。

### 2. 启动 Nacos

运行 `scripts/start-nacos.bat`，控制台 `http://localhost:8849`（首次登录按提示初始化 admin 口令）。

### 3. 创建共享配置

Nacos 控制台「配置管理 → 新建」，dataId 填 `common-shared.yaml`、group 填 `DEFAULT_GROUP`，内容粘贴 `scripts/nacos-common-shared.yaml` 文件全文（含 JWT 密钥、内部调用令牌、Redis 地址，以及 **AI 助手 V1.0 的 `ai.*` 配置段**（prompt/免责文案/风控词/rag/限流阈值）与 `spring.ai.model.embedding: none`）。

### 4. 配置本机密钥（环境变量，PowerShell/CMD 通用）

```bat
setx DB_PASSWORD 你的MySQL密码
setx DEEPSEEK_API_KEY sk-你的大模型Key
setx AMAP_KEY 你的高德Key
```

设置后重开终端生效；开发机也可直接改各服务 yml 的占位符默认值，但不要提交真实密钥。

### 5. 启动后端

运行 `scripts/start-services.bat`（六个窗口：五业务服务 + 网关；各窗口日志逐个出现 `Started` 后进入下一步）。

> **ai-chat-service 首次启动说明**：知识库问答的向量嵌入使用本地 ONNX 模型（`spring-ai-transformers`，all-MiniLM-L6-v2），首次启动会**自动从 GitHub 下载模型并加载，可能明显变慢**（下载缓存于系统临时目录 `%TEMP%\spring-ai-onnx-generative\`，网络受限时请确保代理可用或改用下述预置方式）；未配置 `DEEPSEEK_API_KEY` 时 AI 对话自动降级本地回复引擎，不影响启动。离线/加速部署：把 `all-MiniLM-L6-v2` 的 `.onnx` 模型文件放到本机任意路径，在该服务配置（Nacos `ai.rag.embedding-model-path`）填入完整路径即可（tokenizer 需与模型同目录且命名为「模型名去 `.onnx` + `-tokenizer.json`」，如 `all-MiniLM-L6-v2.onnx` + `all-MiniLM-L6-v2-tokenizer.json`）。

### 6. 启动前端

```bash
cd ai-assistant-frontend
npm install
npm run dev
```

访问 `http://localhost:5173`（员工端，默认 admin/admin123；代理已指向网关 8080）；患者端入口 `http://localhost:5173/patient/login`。

## 四、端口一览表

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

## 五、默认账号

- `admin / admin123`（首次启动由 system-service 播种，请尽快修改密码）；
- 登录后可在「用户管理」创建员工并分配医院 / 科室 / 角色。

## 六、构建命令

```bat
cd ai-assistant-backend
set JAVA_HOME=C:\kaifa\jdk17
C:\kaifa\apache-maven-3.9.16\bin\mvn.cmd -DskipTests clean package
```

依赖走阿里云镜像（已配置在本机 Maven `conf/settings.xml`）；本机旧离线命令已作废。

## 七、AI 助手数据目录与验收清单

- **`data/vector/`**：知识库向量文件目录（Nacos `ai.rag.vector-path` 默认 `data/vector`，相对 ai-chat-service 的启动工作目录，如 `ai-assistant-backend/services/ai-chat-service/data/vector/`），按医院分文件 `hospital_{id}.json`；服务启动时全量加载、上传/删除文档即时落盘重建。该目录为**运行产物**（服务启动时自动创建），不随仓库分发，请勿手工编辑。
- **E2E 验收清单**（本地文档，手动执行）：[`docs/superpowers/e2e/2026-10-13-ai-hospital-v1-e2e.md`](docs/superpowers/e2e/2026-10-13-ai-hospital-v1-e2e.md)——覆盖身份与越权、AI 导诊、智能挂号（二次确认）、RAG 知识库、安全底座、会话隔离、安全与并发补测共 28 项。

## 八、常见问题

| 问题 | 处理 |
|------|------|
| 服务注册失败（启动日志报 Nacos 连接错误） | Nacos 未启动：先运行 `scripts/start-nacos.bat`，确认控制台 8849 可访问、共享配置已创建 |
| 端口占用 | `netstat -ano | findstr <端口>` 查到 PID 后结束对应进程（涉及 8080 / 9200-9204 / 8848 / 8849 / 5173） |
| GitHub 相关操作下载失败 | 本机代理走 `127.0.0.1:7897`，确认代理隧道可用 |
| 修改权限/角色后不生效 | 检查 Redis 快照（会话与数据权限缓存）与 auth-service 日志（JWT 校验 / 撤销），必要时清缓存后重新登录 |
