---
title: 配置详解
parent: 使用指南
nav_order: 3
---

# 配置详解

> 面向使用者：全面理解配置体系——`.env` 环境变量、`application.yml`、配置文件三级加载。
> 完整配置项速查见 [reference/config-full.md](../reference/config-full.md)。

## 1. 配置体系总览

- [ ] 三层配置来源与优先级（命令行 > `.env` > 系统环境变量 > yml 默认值）
- [ ] 配置文件三级加载：运行目录 → 安装目录 → classpath 内置
- [ ] `.env` 加载：**模块目录 → 运行目录 → 安装目录 → classpath**（如 `example-web/.env` 从仓库根启动也能命中）
- [ ] 安装目录定位：`mwb.ai.claw.home` / `MWB_AI_CLAW_HOME` / 默认 `~/.mwb-ai-claw`

## 2. `.env` 环境变量

- [ ] 复制模板：`cp .env.example .env`
- [ ] 常用变量：`DEFAULT_API_KEY` / `DEFAULT_MODEL` / `DEFAULT_BASE_URL`
- [ ] 存储变量：`STORAGE_TYPE`（`file` 本地 | `db` = **MySQL 存储 + Redis Stack 召回**）、`DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `DB_DRIVER` / `SQL_INIT_MODE`
- [ ] Redis / 会话锁变量：`REDIS_INDEX_PREFIX`（召回索引前缀，默认 `claw`）、`LOCK_TYPE`（`local` | `redis`）、`REDIS_URI`（默认 `redis://localhost:6379`）
- [ ] RAG 变量：`RAG_PROVIDER`（默认 `auto`：file→local、db→redis）、**`RAG_EMBEDDING_MODEL` / `RAG_EMBEDDING_BASE_URL` / `RAG_EMBEDDING_API_KEY`**（独立 RAG 知识库向量化，OpenAI 兼容 `/embeddings`）
- [ ] 可观测性变量：`RUN_USAGE_STORE`（`local` | `db`）、`TRACE_ENABLED` / `TRACE_STORE`（`local` | `db`）
- [ ] Agent 级变量：`CODER_MODEL` / `CODER_BASE_URL` / `CODER_API_KEY` 等（供 `agents.json` 引用）

## 3. `application.yml` 核心段

- [ ] `agent.*`：模型、步数、工具绑定、技能、记忆、安全、存储、鉴权
- [ ] 工具绑定策略：缺省=全部，显式 `tools` = 强制仅绑定声明
- [ ] 记忆参数：`agent.memory.*`（分层记忆预算/换页/检索）
- [ ] **RAG 参数**：`agent.rag.*`（`enabled` 总开关 / `provider`=`auto` 跟随存储类型 / 分块 / 召回 / Embedding `max-batch-size` / 上下文注入上限）
- [ ] Redis 召回与会话锁：`agent.redis.index-prefix`（索引前缀，继承默认 `claw`）、`agent.collaboration.lock.*`（会话锁 `local` | `redis`）
- [ ] 安全参数：`agent.security.*`（沙箱/审批/超时）
- [ ] 文件上传：`spring.servlet.multipart.max-file-size` / `max-request-size`（默认 `-1` 不限制，RAG 知识库文档上传用）
- [ ] 跨域（example-web）：`example.cors.allowed-origins`（默认 `http://localhost:5173,http://localhost:5174`）

## 4. 运行期可覆盖的配置

- [ ] 命令行：`--agent.orchestration=team-discussion`
- [ ] 系统属性：`-Dagent.storage.type=db`

## 5. 常见配置场景

- [ ] 切换存储后端 file → db
- [ ] 开启鉴权（多租户隔离）
- [ ] 自定义 Agent / 编排 / 技能 / MCP

---

相关：[快速开始](quick-start.md) ｜ [配置项速查](../reference/config-full.md) ｜ [Agent 与编排](agents-config.md)
