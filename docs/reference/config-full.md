---
title: 全部配置项速查
parent: 速查参考
nav_order: 3
---

# 全部配置项速查

> 配置来源三级加载（优先级从高到低）：**命令行参数 / 系统属性 > `.env` 环境变量 > 内置 `application.yml` 默认值**。
> `.env` 变量经 `${VAR:default}` 占位符注入；配置文件（agents.json / orchestrations.json / mcp-server.json）另按「运行目录 → 安装目录 config → classpath」三级加载。

## 1. 环境变量（.env）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DEFAULT_API_KEY` | （空） | 默认模型密钥，必填 |
| `DEFAULT_MODEL` | `deepseek-chat` | 默认模型 |
| `DEFAULT_BASE_URL` | `https://api.deepseek.com` | 默认 Base URL |
| `CODER_MODEL` / `CODER_BASE_URL` / `CODER_API_KEY` | 继承默认 | coder 专家独立模型 |
| `RESEARCHER_MODEL` / `RESEARCHER_BASE_URL` / `RESEARCHER_API_KEY` | 继承默认 | researcher 专家独立模型 |
| `ARCHITECT_MODEL` / `ARCHITECT_BASE_URL` / `ARCHITECT_API_KEY` | 继承默认 | architect 专家独立模型 |
| `REVIEWER_MODEL` / `REVIEWER_BASE_URL` / `REVIEWER_API_KEY` | 继承默认 | reviewer 专家独立模型 |
| `MODERATOR_MODEL` / `MODERATOR_BASE_URL` / `MODERATOR_API_KEY` | 继承默认 | moderator 专家独立模型 |
| `EMBEDDING_MODEL` / `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` | 继承默认 | 向量检索 embedding（DeepSeek 主模型不支持 embeddings，需单独配置） |
| `RAG_EMBEDDING_MODEL` / `RAG_EMBEDDING_BASE_URL` / `RAG_EMBEDDING_API_KEY` | （空） | **独立 RAG 知识库** Embedding（`agent.rag.embedding.*`；与记忆 Embedding 相互独立，可复用同一模型服务，未配置时 RAG 写入/检索报错） |
| `SYNTHESIS_MODEL` / `SYNTHESIS_BASE_URL` / `SYNTHESIS_API_KEY` | 继承默认 | 小模型提炼（成本优化） |
| `STORAGE_TYPE` | `file` | 存储形态：`file`（本地文件，零依赖）\| `db`（**MySQL 存储 + Redis Stack 召回**） |
| `DB_URL` | `jdbc:h2:mem:clawdb;MODE=MySQL;...` | MySQL 连接（db 模式） |
| `DB_USERNAME` | `sa` | MySQL 用户 |
| `DB_PASSWORD` | （空） | MySQL 密码 |
| `DB_DRIVER` | `org.h2.Driver` | 驱动（生产：`com.mysql.cj.jdbc.Driver`） |
| `SQL_INIT_MODE` | `embedded` | SQL 初始化：`embedded`（仅嵌入式库）/ `never`（关闭） |
| `RAG_PROVIDER` | `auto` | RAG 索引：`auto`（跟随存储：file→local、db→redis）\| `redis`（显式） |
| `REDIS_INDEX_PREFIX` | `claw` | Redis 召回索引 key 前缀（多环境隔离） |
| `LOCK_TYPE` | `local` | 会话锁：`local`（JVM 内锁）\| `redis`（分布式锁） |
| `REDIS_URI` | `redis://localhost:6379` | Redis 连接串（召回索引 + 分布式锁兜底） |
| `SYNTHESIS_QUEUE_TYPE` | `auto` | 提炼任务队列：`auto`（跟随 `STORAGE_TYPE`：file→local、db→redis）\| `local`（进程内单线程）\| `redis`（分布式锁，多实例推荐） |
| `SYNTHESIS_LOCK_TTL_SECONDS` | `600` | 合成锁 TTL（秒，仅 queue-type=redis 生效；LLM 长上下文时可放大） |
| `SYNTHESIS_LOCK_WATCHDOG_INTERVAL` | `200` | 合成锁 watchdog 续期间隔（秒，默认 1/3 TTL） |
| `RUN_USAGE_STORE` | `local` | 运行用量存储：`local`（JSONL）\| `db`（表） |
| `TRACE_ENABLED` / `TRACE_STORE` | `true` / `local` | 步骤级 trace 开关 / 存储：`local` \| `db` |

## 2. Spring 基础（application.yml）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `spring.profiles.active` | `web` | `web`（REST/SSE/WS）/ `shell`（终端 REPL） |
| `spring.datasource.*` | H2 内存 | db 模式数据源（经 DB_* 变量覆盖） |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | `-1` | 文件上传大小上限（-1=不限制；RAG 知识库文档上传默认不限制，可按需收紧为如 `10MB`） |

## 3. Agent 核心（agent.*）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `agent.agent-id` | `default` | Agent 标识 |
| `agent.name` | `mwb-ai-claw` | 显示名称 |
| `agent.provider` | `openai` | Provider 类型：`openai` / `anthropic` / `gemini` / `ollama`（空则兼容推断） |
| `agent.system-prompt` | 内置 | 系统提示词 |
| `agent.orchestration` | `routing` | 默认编排 id（引用 orchestrations.json） |
| `agent.model` / `agent.base-url` / `agent.api-key` | env 引用 | 默认模型配置 |
| `agent.temperature` | `0.7` | 采样温度 |
| `agent.max-tokens` | `8192` | 单次最大 tokens |
| `agent.max-steps` | `8` | ReAct 初始步数预算 |
| `agent.max-steps-extension` | `2.0` | 步数扩展系数（硬上限 = max-steps × 系数） |
| `agent.memory-dir` | `${user.dir}/.agent` | 记忆/运行数据目录 |
| `agent.skills-enabled` | `true` | 技能总开关 |
| `agent.skills-dir` | `${user.dir}/skills` | 技能根目录 |
| `agent.tools` | 全部注册工具 | 强制仅绑定声明的工具列表 |
| `agent.storage.type` | `file` | 存储形态（见 `STORAGE_TYPE`）：`file` 全本地；`db` 走 MySQL 存储 + Redis Stack 召回 |

## 4. 分层记忆（agent.memory.*）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用分层记忆 |
| `context-window-tokens` | `200000` | 模型上下文窗口 |
| `context-budget-ratio` | `0.6` | 记忆区占窗口比例 |
| `prompt-budget-ratio` | `0.25` | System 区占记忆预算比例 |
| `tool-budget-ratio` | `0.25` | Tools 区占记忆预算比例 |
| `hot-window-size` | `20` | Hot 工作记忆最大条数 |
| `summary-block-size` | `10` | 多少条消息合成一个摘要块 |
| `max-summary-depth` | `3` | 摘要页最大压缩层级 |
| `importance-threshold` | `0.6` | 事实写入重要度阈值 |
| `top-k` | `5` | 检索召回条数 |
| `eviction-policy` | `importance` | 换页策略：`token` / `importance` |
| `synthesis-async` | `true` | 提炼异步执行 |
| `retriever` | `hybrid` | 检索器：`keyword` / `vector` / `hybrid` |
| `vector-enabled` | `true` | 是否启用向量索引 |
| `embedding-model` / `-base-url` / `-api-key` | 继承默认 | Embedding 配置 |
| `archive-enabled` | `true` | 会话结束归档原文 |
| `archive-keep-recent` | `0` | 每次会话保留最近 N 条原文不归档不标记（0=使用 `hot-window-size` 兜底；会话进行中热窗始终保留未归档） |
| `archive-idle-timeout` | `30m` | 会话闲置多久后收敛剩余热窗（如 `30m`；`null`/0 表示不启用空闲收敛） |
| `archive-min-tokens` | `0` | 块 token 数低于该值时只保留摘要不归档全文（0=不限制，始终归档） |
| `shared-retrieve` | `true` | 多 Agent 共享检索 |
| `synthesizer-model` / `-base-url` / `-api-key` | 继承默认 | 提炼专用小模型 |
| `synthesis-cache-size` | `50` | 提炼缓存容量（≤0 关闭） |
| `synthesis-cache-type` | `auto` | 提炼缓存实现：`auto`（跟随 `storage.type`：file→local、db→redis）\| `local`（JVM LRU）\| `redis`（分布式，多实例推荐） |
| `synthesis-cache-ttl-seconds` | `3600` | 提炼缓存 Redis TTL（秒，仅 type=redis 生效） |
| `synthesis-cache-redis-uri` | （空=复用） | 提炼缓存 Redis 连接串（留空复用 `spring.data.redis` 或会话锁 `redis-uri`） |
| `synthesis-cache-redis-key-prefix` | `claw:syn:` | 提炼缓存 Redis key 前缀（多租户/多环境隔离） |
| `synthesis-queue-type` | `auto` | 提炼任务队列：`auto`（跟随 cache-type 推断）\| `local`（进程内单线程）\| `redis`（分布式锁，多实例推荐）\| `lockfree`（CAS，Phase 2）\| `rocketmq`（生产级 MQ，Phase 3） |
| `synthesis-lock-ttl-seconds` | `600` | 合成锁 TTL（秒，仅 queue-type=redis 生效；LLM 长上下文时可放大） |
| `synthesis-lock-watchdog-interval-seconds` | `200` | 合成锁 watchdog 续期间隔（秒，默认 1/3 TTL） |
| `synthesis-drop-old-pending` | `true` | 是否「保留最新提交、丢弃旧等待」去重（同会话同类型多次提交） |
| `synthesis-claim-max-retries` | `3` | Phase 2 CAS claim 最大重试次数（仅 queue-type=lockfree 生效） |

## 5. RAG 检索增强（agent.rag.*）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | RAG 总开关（关闭后 RAG Bean 与 `/rag` 接口不装配，行为与未接入前一致） |
| `provider` | `auto` | 索引实现：`auto`（跟随 `agent.storage.type`：file→`local`、db→`redis`）\| `redis`（显式，与 auto+db 等价） |
| `local.dir` | `${user.dir}/.agent/rag` | 索引存储目录（与 `.agent/memory` 完全隔离） |
| `redis.index-prefix` | 继承 `agent.redis.index-prefix` | Redis 召回索引 key 前缀（多环境隔离） |
| `access.enabled` | `false` | 是否启用知识库 API 层访问控制（关闭时全部放行，保持全局共享检索语义） |
| `capacity.max-documents-per-knowledge-base` | `0` | 单个知识库最大文档数（0=不限制） |
| `capacity.max-chunks-per-document` | `0` | 单个文档最大分块数（0=不限制） |
| `capacity.max-document-chars` | `0` | 单个文档解析后文本最大字符数（0=不限制） |
| `ingestion.chunk-size` | `500` | 单块文本长度上限（字符） |
| `ingestion.chunk-overlap` | `50` | 相邻分块重叠（字符） |
| `ingestion.embedding-batch-size` | `32` | 批量向量化单批条数（吞吐分组；单次 HTTP 上限见 embedding.max-batch-size） |
| `retrieval.top-k` | `5` | 默认召回条数 |
| `retrieval.min-score` | `0.2` | 默认最低相似度阈值 |
| `retrieval.require-knowledge-base-id` | `false` | 是否强制要求请求显式指定知识库 |
| `embedding.model` / `-base-url` / `-api-key` | env 引用 | RAG 专用 Embedding（OpenAI 兼容 `/embeddings`，经 `RAG_EMBEDDING_*` 注入） |
| `embedding.max-batch-size` | `16` | 单次 HTTP 请求最大文本条数（模型侧批量上限，如阿里云 MaaS 为 20；Gateway 内部分批保证不超限） |
| `context.max-chars` | `8000` | 单次注入 system prompt 的知识内容字符上限 |

## 6. 工具安全（agent.security.*）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 沙箱总开关 |
| `workspace-dir` | （空=不限制） | 文件操作根目录 |
| `shell-whitelist` | 75 个命令 | 允许的 Shell 命令（ls/cat/git/python3/node/npm…） |
| `shell-blacklist` | 危险模式 | 命中即拒绝（优先级高于白名单） |
| `shell-approval-mode` | `ask` | `auto` / `ask` / `read-only` |
| `shell-approval-patterns` | 59 条规则 | 高风险命令，ask 下请求确认 |
| `tool-timeout-seconds` | `30` | 工具超时（超时转后台） |
| `max-output-length` | `10000` | 工具输出截断 |
| `http-allowed-hosts` | （空=全部允许） | HTTP 请求 host 白名单（防 SSRF） |
| `prompt-injection-guard` | `true` | 提示词注入防护 |

## 7. 鉴权（agent.auth.*）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 是否启用 API Key 鉴权 |
| `header` | `X-API-Key` | 请求头名 |
| `api-keys` | （空） | tenantId → userId → apiKey 静态映射 |
| `default-user` | `default` | 未配置权限时的兜底用户 |
| `tool-permissions` | （空=全部允许） | 工具级静态授权 |

## 7. LLM 韧性（agent.llm.*）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `connect-timeout-ms` | `5000` | 连接超时 |
| `read-timeout-ms` | `120000` | 读超时 |
| `retry.max-attempts` | `3` | 重试次数 |
| `retry.initial-backoff-ms` | `500` | 重试初始退避 |
| `retry.max-backoff-ms` | `10000` | 最大退避 |
| `fallback-model` / `-base-url` / `-api-key` | （空） | 备用模型降级 |
| `run-budget-tokens` | `0` | 单次运行 token 预算（0=不限制） |
| `max-single-message-tokens` | `12000` | 单条消息最大 token 数（超出截断告警） |

## 8. 可观测性（agent.observability.*）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `run-usage-store` | `local` | 运行用量摘要：`local`（JSONL）\| `db`（落 `claw_run_usage` 表，多实例共享，生产推荐） |
| `run-usage-log` | `true` | 是否记录运行用量 JSONL |
| `run-usage-dir` | `{memory-dir}/runs` | 运行记录目录（仅 `store=local`） |
| `trace.enabled` | `true` | 步骤级 trace 开关 |
| `trace.store` | `local` | trace 存储：`local`（本地 JSON）\| `db`（落 `claw_trace` 表，生产推荐） |
| `trace.dir` | `{memory-dir}/traces` | trace 目录（仅 `store=local`） |
| `metrics-exporter` | `none` | `none` / `actuator` / `prometheus`（实际暴露依赖引入的依赖） |

## 9. Redis 召回索引与会话锁（agent.redis.* / agent.collaboration.*）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `agent.redis.index-prefix` | `claw` | 召回索引 key 前缀（`{prefix}:memory:idx` / `{prefix}:rag:idx`，多环境隔离；连接复用 `spring.data.redis.*`，未配置时兜底 `collaboration.lock.redis-uri`） |
| `agent.collaboration.lock.type` | `local` | 会话并发锁：`local`（JVM 内锁，单实例）\| `redis`（SET NX 分布式锁，多实例共享） |
| `agent.collaboration.lock.redis-uri` | `redis://localhost:6379` | Redis 连接串（type=redis 时生效，可带密码 `redis://:pass@host:port`） |
| `agent.collaboration.lock.key-prefix` | `claw:lock:` | 锁 key 前缀（多租户/多环境共享 Redis 时隔离命名空间） |

> `agent.storage.type=db`（召回）与 `agent.collaboration.lock.type=redis`（锁）共用同一 Redis 连接：
> 优先复用业务方 `spring.data.redis.*` 自动装配的 `RedisConnectionFactory`，未配置时以
> `agent.collaboration.lock.redis-uri` 兜底创建；redis 依赖在框架中为 optional，需业务方显式引入
> `spring-boot-starter-data-redis`（`@ConditionalOnClass` 门控，未引入时 db 召回退化为空结果、锁回退本地）。

## 10. 外部 JSON 配置

| 文件 | 说明 |
| --- | --- |
| `agents.json` | Agent 注册表（agentId/name/description/keywords/systemPrompt/tools/maxSteps/maxTokens/model/baseUrl/apiKey/temperature/provider…） |
| `orchestrations.json` | 编排注册表（id/type/description/keywords/agents/config） |
| `mcp-server.json` | MCP Server 配置（stdio：command+args；streamable_http：type+url） |

均支持运行目录覆盖 + `${VAR:default}` 占位符，详见 [配置详解](../guide/configuration.md) 与 [Agent 与编排配置](../guide/agents-config.md)。

## 11. example-web 示例工程（example.*）

以下为 `example-web`（含前端 `example-web-frontend`）特有的接入方配置，框架本体不依赖：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `example.tenant-id` | `default` | example-web 固定单租户 id（用户维度隔离空间标识，写入 `AgentScope.tenantId`） |
| `example.cors.allowed-origins` | `http://localhost:5173,http://localhost:5174` | 跨域允许来源（逗号分隔；生产请显式配置为实际前端域名，经 `EXAMPLE_CORS_ALLOWED_ORIGINS` 覆盖） |
| `AUTH_ENABLED` | `true` | example-web 是否开启鉴权（示例默认开启） |
| `BOOTSTRAP_API_KEY` | `sk-admin-bootstrap` | 引导管理员 Key（`agent.auth.api-keys.admin.admin`） |

> `.env` 加载：示例工程支持在**模块目录**（如 `example-web/.env`）放置 `.env`，
> 从仓库根启动时也会命中模块内 `.env`（查找顺序：模块目录 → 运行目录 → 安装目录 → classpath）。

---

相关：[配置详解](../guide/configuration.md) ｜ 源码模板：`start/src/main/resources/application.yml`、`.env.example`
