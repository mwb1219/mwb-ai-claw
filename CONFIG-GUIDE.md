# mwb-ai-claw 配置指南

本文件说明安装包中各配置文件的用途与配置方法。所有配置均可在**不改代码、不重新打包**的情况下调整。

## 1. 配置文件总览

```
安装包 /
├── .env.example            密钥与模型配置模板（复制为 .env 填写）
├── skills/                 内置技能模板（SKILL.md，可增删自定义技能）
└── config/
    ├── agents.json         专家 Agent 定义（身份、模型、可用工具）
    ├── orchestrations.json 协作编排注册表（路由 / 讨论 / 委托）
    └── mcp-server.json.example  MCP Server 配置模板（复制为 mcp-server.json 生效）
```

> **RAG 知识库（`agent.rag.*`）无独立配置文件**：默认关闭，开关与参数在 `application.yml`（或运行时 `--agent.rag.*` 覆盖），Embedding 密钥在 `.env`，详见[第 6 节](#6-rag-知识库agentrag)。

安装（install.sh / install.ps1）后，安装目录 `~/.mwb-ai-claw/` 布局：

```
~/.mwb-ai-claw/
├── lib/start.jar           运行产物
├── config/                 配置模板（直接修改即可覆盖内置默认）
├── skills/                 技能模板（增删技能目录即自定义技能集）
├── .env.example            密钥模板副本（参考/重置用）
├── .env                    全局密钥配置（安装时已从模板创建，填入密钥即可）
└── bin/mwb-ai-claw         启动器
```

## 2. 加载优先级（如何生效）

配置按**三级加载**，高优先级命中即生效：

```
运行目录（user.dir）→ 安装目录 ~/.mwb-ai-claw（config/ 子目录 或 根目录 .env）→ classpath 内置默认
```

- 安装后**直接修改 `~/.mwb-ai-claw/config/` 下的文件**即可覆盖内置默认，重启生效；
- **密钥写入 `~/.mwb-ai-claw/.env`**（已在安装时创建，直接编辑即可），同样会被加载；
- 也可把配置文件复制到**运行目录**（执行命令时所在目录）做单次运行覆盖，优先级最高；
- `mcp-server.json.example` 需**复制为 `mcp-server.json`** 才会被加载。

> 提示：配置加载器只读目标文件，不向上级目录搜索；高优先级命中即不再读取低优先级来源。

## 3. 密钥与模型（.env）

安装后直接编辑 `~/.mwb-ai-claw/.env`（已从模板创建）；手动运行（源码/`java -jar`）时复制 `.env.example` 为运行目录下 `.env` 并填写：

| 变量 | 说明 |
| --- | --- |
| `DEFAULT_MODEL` / `DEFAULT_BASE_URL` / `DEFAULT_API_KEY` | 默认模型（OpenAI 兼容接口） |
| `CODER_MODEL` / `CODER_BASE_URL` / `CODER_API_KEY` 等 | 各专家 Agent 独立模型，留空继承默认 |
| `EMBEDDING_MODEL` / `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` | 向量检索专用模型（DeepSeek 不支持 embeddings，建议配 OpenAI 兼容的 text-embedding-3-small） |
| `RAG_EMBEDDING_MODEL` / `RAG_EMBEDDING_BASE_URL` / `RAG_EMBEDDING_API_KEY` | RAG 知识库专用 Embedding（`agent.rag.enabled=true` 时必填；与记忆向量检索相互独立，可复用同一模型服务） |
| `SYNTHESIS_MODEL` / `SYNTHESIS_BASE_URL` / `SYNTHESIS_API_KEY` | 摘要/事实提炼的小模型（成本优化） |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `DB_DRIVER` | 数据库连接（`agent.storage.type=db` 时生效；默认嵌入式 H2，生产配 MySQL 连接串） |
| `SQL_INIT_MODE` | SQL 初始化模式：`embedded`（默认，仅 H2）| `never`（关闭）|
| `RAG_PROVIDER` | `auto` | RAG 索引实现：`auto`（跟随 `STORAGE_TYPE`：file→local、db→redis）\| `redis`（显式声明） |
| `REDIS_INDEX_PREFIX` | `claw` | Redis 召回索引 key 前缀（多环境/多租户共享 Redis 时隔离命名空间） |
| `LOCK_TYPE` | `local` | 会话并发锁渠道：`local`（JVM 内锁，单实例）\| `redis`（SET NX 分布式锁，多实例） |
| `REDIS_URI` | `redis://localhost:6379` | Redis 连接串（召回索引 + 分布式锁；`agent.redis` 连接复用 `spring.data.redis.*`，未配置时以本值兜底） |
| `RUN_USAGE_STORE` | `local` | 运行用量摘要存储：`local`（JSONL 文件）\| `db`（落 `claw_run_usage` 表，生产推荐） |
| `TRACE_ENABLED` / `TRACE_STORE` | `true` / `local` | 步骤级 trace 开关 / 存储：`local`（本地 JSON）\| `db`（落 `claw_trace` 表，生产推荐） |

`.env` 支持 `${VAR}` 占位符引用，例如 `agents.json` 中的 `"model": "${CODER_MODEL:${DEFAULT_MODEL:deepseek-chat}}"`、`"baseUrl": "${CODER_BASE_URL:${DEFAULT_BASE_URL:https://api.deepseek.com}}"`。

> **存储后端 `agent.storage.type`**：`file`（本地文件，默认，零依赖）| `db`（**MySQL 存储 + Redis Stack 召回**：会话 / 长期记忆 / 记忆页 / 知识库文本落 MySQL，关键词与向量召回走 Redis）。
> 设置位置：`application.yml` 的 `agent.storage.type`，环境变量 `STORAGE_TYPE`（写入 `.env`），或命令行覆盖 `mwb-ai-claw --agent.storage.type=db`（优先级递增）。
> 注意：`db` 时数据源为启动即连（见上表 DB_* 变量），需先确保数据库可达；`db` 形态的召回依赖 Redis Stack（RediSearch），需保证 Redis 可达（见 [横向扩展](docs/design/horizontal-scaling.md)）。
> 会话并发锁默认使用本地 JVM 实现（`LocalSessionLockManager`，单实例部署，零依赖）；多实例部署时可切换为 Redis 分布式锁（见 8.1 会话锁）。

## 4. 专家 Agent（config/agents.json）

`agents` 数组每个元素定义一个专家 Agent：

| 字段 | 说明 |
| --- | --- |
| `agentId` | 唯一标识（路由 / 编排引用） |
| `name` / `description` | 展示名与职责描述（路由依据） |
| `keywords` | 意图匹配关键词，路由按 description+keywords 打分 |
| `systemPrompt` | 系统提示词 |
| `maxSteps` / `maxTokens` | ReAct 最大步数 / 回复 token 上限 |
| `model` / `baseUrl` / `apiKey` | 该 Agent 的模型与 API 地址/密钥（支持 `${VAR}` 占位符） |
| `tools` | **可选**：可用工具列表 |

### 工具绑定规则

- **不配置 `tools`（缺省）**：绑定**全部已注册工具**（内置 + 全局/MCP）；
- **配置了 `tools`**：**强制仅绑定**声明的工具（`use_skill` / MCP / `invoke_*` 等全局工具需显式加入）。

内置工具名：`echo`、`http`、`file`、`shell`、`shell_status`、`read_memory`、`write_memory`；
全局工具：`use_skill`、MCP 工具、`invoke_delegate` / `invoke_discussion`（协作编排）。

## 5. 协作编排（config/orchestrations.json）

`orchestrations` 数组定义可用编排，`id` 被 `application.yml` 的 `agent.orchestration` 引用：

| 类型 | 用途 |
| --- | --- |
| `routing` | 单专家独立处理，按意图路由（默认兜底） |
| `conversational` | 多方专家对话式讨论（如方案对比、技术选型），`config.conversation` 控制轮数/主持/收敛 |
| `delegate` | 主 Agent 拆解 Todo 委托子 Agent 执行，`config.delegate` 控制深度/并行/失败策略 |

可增删编排，或调整 `keywords` 改变自动触发条件。

## 6. RAG 知识库（agent.rag.*）

RAG 提供与 Agent 记忆**完全独立**的知识库能力：后台上传文档 → 解析 / 切分 / 向量化 → 建索引 → 按需检索并注入上下文。默认关闭；开启后零依赖可用（`provider=auto` + `STORAGE_TYPE=file` → 本地文件索引）；需 PDF / Word 解析时引入 optional 依赖即自动启用；生产推荐 `STORAGE_TYPE=db`（文本落 MySQL，召回走 Redis Stack）。

### 6.1 启用与开关

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `agent.rag.enabled` | `false` | 总开关；开启后装配 RAG Bean 与 `/rag` 接口，关闭后行为与未接入前一致 |
| `agent.rag.provider` | `auto` | 索引实现：`auto`（跟随 `agent.storage.type`：file→`local` 本地文件索引，db→`redis` 走 Redis Stack 召回）\| `redis`（显式声明，与 auto+db 等价） |
| `agent.rag.local.dir` | `${user.dir}/.agent/rag` | 索引存储目录（与 `.agent/memory` 完全隔离） |
| `agent.rag.redis.index-prefix` | 继承 `agent.redis.index-prefix` | Redis 召回索引 key 前缀（多环境/多租户共享 Redis 时隔离命名空间） |
| `agent.rag.access.enabled` | `false` | 是否启用知识库 API 层访问控制（关闭时全部放行、保持全局共享检索语义） |
| `agent.rag.capacity.max-documents-per-knowledge-base` | `0` | 单个知识库最大文档数（0=不限制） |
| `agent.rag.capacity.max-chunks-per-document` | `0` | 单个文档最大分块数（0=不限制） |
| `agent.rag.capacity.max-document-chars` | `0` | 单个文档解析后文本最大字符数（0=不限制） |

开启方式（任选其一）：
- 编辑 `application.yml` 添加 `agent.rag.enabled: true`；
- 运行时覆盖：`mwb-ai-claw --agent.rag.enabled=true`。

> RAG 依赖 Embedding 接口可用，开启前请在 `.env` 配置 `RAG_EMBEDDING_*`（见第 3 节）；未配置时写入 / 检索会报错。

PDF / Word 解析：`MultiFormatRagDocumentParser` 按内容类型 / 扩展名分发，引入
`org.apache.pdfbox:pdfbox`（PDF）与 `org.apache.poi:poi-ooxml`（Word .docx）后自动启用；未引入时退化为纯文本解析并给出明确提示。

Redis Stack（RediSearch）前置：`agent.rag.provider=redis`（或 `auto` + `STORAGE_TYPE=db`）时，需保证 Redis Stack 可达（docker compose 一键起 `redis/redis-stack-server`）；召回索引（全文倒排 + 向量 KNN）在首次写入时自动 `FT.CREATE`，Redis 丢失可从 MySQL 文本重新向量化重建。

### 6.2 写入与检索参数

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `agent.rag.ingestion.chunk-size` | `500` | 单块文本长度上限（字符） |
| `agent.rag.ingestion.chunk-overlap` | `50` | 相邻分块重叠（字符） |
| `agent.rag.ingestion.embedding-batch-size` | `32` | 批量向量化单批条数 |
| `agent.rag.embedding.max-batch-size` | `16` | 单次 HTTP 请求最大文本条数（模型侧批量上限，如阿里云 MaaS 为 20）；Gateway 内部分批保证不超限 |
| `agent.rag.retrieval.top-k` | `5` | 默认召回条数 |
| `agent.rag.retrieval.min-score` | `0.2` | 默认最低相似度阈值 |
| `agent.rag.retrieval.require-knowledge-base-id` | `false` | 是否强制要求请求显式指定知识库 |
| `agent.rag.context.max-chars` | `8000` | 单次注入 system prompt 的知识内容字符上限 |

### 6.3 REST 接口（/rag）

| 接口 | 说明 |
| --- | --- |
| `POST /rag/knowledge-bases/{kb}/documents` | 摄入文档（JSON 内容） |
| `POST /rag/knowledge-bases/{kb}/documents/upload` | 上传文件（multipart，无大小 / 字符限制；`.pdf` / `.docx` 自动走 PDF / Word 解析） |
| `POST /rag/knowledge-bases/{kb}/documents/{id}/reindex` | 重建文档索引 |
| `DELETE /rag/knowledge-bases/{kb}/documents/{id}` | 删除文档（含索引） |
| `GET /rag/knowledge-bases/{kb}/documents` | 文档列表 |
| `POST /rag/search` | 检索调试（返回带知识库 / 文档 / 分块引用的结果） |

> 知识库**全局共享**（不按 AgentScope 隔离），通过 `knowledgeBaseId` 组织；对话时可通过 SSE 参数注入本次会话使用的知识库。
> 需要租户 / 角色可见性时：`agent.rag.access.enabled=true` 并注册 `RagAccessPolicy` Bean，接口层按操作类型（READ / WRITE / DELETE）鉴权。

## 7. MCP Server（config/mcp-server.json）

复制 `mcp-server.json.example` 为 `mcp-server.json` 并按需增删：

```json
{
  "mcpServers": {
    "tavily-mcp": {
      "command": "npx",
      "args": ["-y", "tavily-mcp@0.1.2"],
      "env": { "TAVILY_API_KEY": "你的密钥" }
    }
  }
}
```

MCP 工具注册为全局工具，对所有未显式绑定工具的 Agent 可见。

## 8. 运行时覆盖（无需改文件）

启动命令追加 Spring 参数即可临时覆盖：

```bash
mwb-ai-claw --agent.orchestration=todo-delegate     # 切换编排
mwb-ai-claw --agent.model=deepseek-chat             # 切换默认模型
mwb-ai-claw --agent.security.shell-approval-mode=auto  # 工具审批自动放行
```

### 8.1 会话锁（agent.collaboration.lock.*）

会话级锁保证同一会话（scope + sessionId）的「读 → 追加 → 推理 → 保存」全程串行，不同会话 / 不同用户完全并行。

| 配置 | 说明 | 默认 |
| --- | --- | --- |
| `agent.collaboration.lock.type` | `local`（JVM 内锁，单实例）| `redis`（SET NX 分布式锁，多实例共享） | `local` |
| `agent.collaboration.lock.redis-uri` | Redis 连接串（type=redis 时生效，可带密码 `redis://:pass@host:port`） | `redis://localhost:6379` |
| `agent.collaboration.lock.key-prefix` | 锁 key 前缀（多租户/多环境共享 Redis 时隔离命名空间） | `claw:lock:` |
| `agent.collaboration.lock.lease-ms` | 锁自动过期毫秒数（持有者崩溃后自动释放，防死锁） | `30000` |
| `agent.collaboration.lock.timeout-ms` | 获取锁等待超时毫秒数，超时抛「获取会话锁超时」 | `30000` |
| `agent.collaboration.lock.retry-interval-ms` | 获取锁轮询间隔毫秒数 | `100` |

> 切换 Redis 分布式锁需自行引入 `spring-boot-starter-data-redis` 依赖（框架将其设为 optional），并保证 Redis 可达；未引入该依赖或未设置 type=redis 时回退本地实现，完全向后兼容。

```bash
# 多实例部署示例：切换到 Redis 分布式锁
mwb-ai-claw --agent.collaboration.lock.type=redis \
            --agent.collaboration.lock.redis-uri=redis://:password@redis.internal:6379/0
```

## 9. 数据与运行目录

- 会话 / 记忆数据落在**运行目录** `.agent/` 下（按项目隔离）；
- RAG 知识库索引与文档落 `.agent/rag/`（`agent.rag.local.dir` 可改，与记忆完全隔离）；
- 运行用量记录写入 `.agent/runs/YYYY-MM-DD.jsonl`（`agent.observability.run-usage-store` 切 `db` 时落 `claw_run_usage` 表）
- **步骤级 trace** 写入 `.agent/traces/`（`agent.observability.trace.dir` 可改）；切到 `store=db` 时落 MySQL
  （`claw_trace` 表，与会话/记忆/RAG 共用数据源）；`agent.observability.trace.enabled=false` 关闭；
- 退出清理：删除运行目录 `.agent/` 即可重置全部会话记忆。

## 9.1 步骤级 trace 存储（agent.observability.trace.*）

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `agent.observability.trace.enabled` | `true` | 步骤级 trace 记录与查询开关；`false` 时不装配 TraceStore（`/trace` 接口返回失败） |
| `agent.observability.trace.store` | `local` | trace 后端：`local`（本地 JSON 文件，零依赖）\| `db`（JDBC 落 `claw_trace` 表，多实例共享，**生产推荐**） |
| `agent.observability.trace.dir` | `{memory-dir}/traces` | 本地 trace 目录（仅 `store=local` 生效） |

## 9.2 运行用量存储（agent.observability.run-usage-store）

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `agent.observability.run-usage-log` | `true` | 运行用量记录开关 |
| `agent.observability.run-usage-store` | `local` | 运行用量后端：`local`（JSONL 逐行追加）\| `db`（JDBC 落 `claw_run_usage` 表，多实例共享，**生产推荐**） |
| `agent.observability.run-usage-dir` | `{memory-dir}/runs` | 本地运行记录目录（仅 `store=local` 生效） |

```bash
# 生产多实例：运行用量落库（需先建表，见 start/src/main/resources/schema.sql 或 example-web/db/mysql/framework-schema.sql）
mwb-ai-claw --agent.observability.run-usage-store=db --spring.datasource.url=jdbc:mysql://localhost:3306/clawdb --spring.datasource.username=root
```

## 10. 技能（skills/）

技能是可复用工作流 / 领域知识包，格式为 `skills/<name>/SKILL.md`（YAML frontmatter `name` / `description` + Markdown 指令正文）。**加载顺序（三级，任一外部目录非空即完全接管技能集）**：

1. **运行目录**：`${user.dir}/skills`（或 `agent.skills-dir` 指定）
2. **安装目录**：`$MWB_AI_CLAW_HOME/skills/`（install 时随包复制，安装模式下直接在此增删技能目录，重启生效）
3. **classpath 内置**：jar 内 `skills/` 模板（10 个内置技能）兜底

新增技能 = 放入上述任一技能目录，重启应用后日志输出「已加载技能 [n]」，对话中 LLM 会自动调用 `use_skill` 按需加载。
