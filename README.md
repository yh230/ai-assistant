# AI 助手（聊天小伙伴）

基于 **Java 17 + Spring Boot 3.4 + Spring AI 1.0 + Vue 3 + MySQL + Redis** 的前后端分离智能助手。

**四大能力**

- **智能聊天**：SSE 流式输出（逐 token 实时推送）、Tool Calling（对话中直接查真实天气）、多会话管理、Markdown 渲染；
- **天气查询**：实时天气 + 未来预报（高德开放平台数据）；
- **聊天人设管理**：人设角色存数据库，页面自由增删改（DB 驱动，首次启动自动播种默认角色）；
- **RBAC 员工权限**：Spring Security 登录鉴权（JWT + Redis 撤销）+ 角色/菜单/按钮权限（含个人中心、踢下线）。

**另有业务模块**

- **医院人员与出勤**：多医院 / 多科室，按【医院-科室-角色】三级数据隔离，出勤多维统计；
- **门诊挂号**：排班号源、预约取号、退号就诊、多维度统计（含公开的患者预约入口）。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 17 / Spring Boot 3.4.5 / Spring AI 1.0 |
| 鉴权与缓存 | Spring Security 6.4.5 + JWT（jjwt）+ Redis 撤销（登出黑名单/踢下线） |
| ORM | MyBatis-Plus（RBAC / 医院 / 挂号）+ Spring Data JPA（chat 模块） |
| 数据库 | MySQL 8 |
| 前端 | Vue 3 + Vite 5 + vue-router 4 + axios（无 UI 组件库，手写 scoped CSS） |

## 快速开始

**前提**：MySQL 8（库名 `ai_assistant`）、Redis、JDK 17、Node 18+。

### 1. 数据库脚本（按顺序执行）

- 脚本位于 `src/main/resources/sql/`，执行顺序：`rbac_schema` → `hospital_schema` → `registration_schema`（均为 `.sql` 文件）；
- chat 相关表由 JPA 自动维护（`ddl-auto: update`），无需手工建表。

### 2. 配置（密钥不入库）

仓库中的 `application.yml` 不含任何真实密钥（均为空占位符）。本机运行任选其一提供：

**方式一（推荐）**：在 `ai-assistant-backend/src/main/resources/` 新建 `application-local.yml`（已 gitignore，profile `local` 默认激活自动加载）：

```yaml
spring:
  datasource:
    password: 你的MySQL密码

ai:
  assistant:
    models:
      openai:
        api-key: sk-你的大模型Key

weather:
  amap-key: 你的高德Key
```

**方式二**：设置环境变量（优先级高于本地文件）：

| 环境变量 | 用途 |
|----------|------|
| `DB_PASSWORD` | MySQL root 密码 |
| `DEEPSEEK_API_KEY` | 大模型 API Key（不配置时自动降级为本地回复引擎） |
| `AMAP_KEY` | 高德开放平台「Web服务」Key（天气数据来源） |
| `JWT_SECRET` | JWT 签名密钥（可选，默认开发值） |

### 3. 启动后端

```bash
cd ai-assistant-backend
mvn spring-boot:run
```

端口 **8088**，启动后访问 `http://localhost:8088/api/health` 验证。

### 4. 启动前端

```bash
cd ai-assistant-frontend
npm install
npm run dev
```

端口 **5173**，已配代理 `/api` → `http://localhost:8088`。

### 5. 默认账号

- `admin / admin123`（首次启动由 `SystemDataSeeder` 播种，请尽快修改密码）；
- 登录后可在「用户管理」创建员工并分配医院 / 科室 / 角色。

## 项目结构

**后端 `ai-assistant-backend/src/main/java/com/aiassistant/`**

| 目录 | 说明 |
|------|------|
| `common/` | 公共模块：`Result`/`PageResult`、常量、异常、数据权限（`DataScope`/`DataScopeResolver`）、`support` 辅助 |
| `config/` `security/` | 聊天模型与全局配置（Spring Security / MyBatis-Plus / CORS）；权限数据源（DbUserDetailsService 实时加载角色/权限） |
| `controller/` `service/` `entity/` `repository/` `tool/` | 聊天、天气、人设（JPA 侧） |
| `system/` | RBAC：登录 / 用户 / 角色 / 菜单（MyBatis-Plus） |
| `hospital/` | 医院 / 科室 / 员工 / 出勤（后端自动数据权限过滤） |
| `registration/` | 门诊挂号：排班 / 号源 / 预约取号 / 统计 |

**前端 `ai-assistant-frontend/src/`**

| 目录 | 说明 |
|------|------|
| `api/` | axios 实例（拦截器统一处理鉴权与错误）+ 各模块接口封装 |
| `router/` `store/` | 路由表与守卫、登录态（轻量单例，无 Pinia） |
| `layout/` `views/` | 主布局（侧边栏按权限渲染）+ 17 个业务页面 |
| `components/` `composables/` | 通用组件（菜单树 / 轻提示）与管理页 CRUD 骨架 `useCrudPage` |
| `utils/` | 时间格式化（`format.js`）、权限常量与判定（`perm.js`）、轻提示（`message.js`） |
| `assets/` | 全局样式（含管理页公共样式段） |
