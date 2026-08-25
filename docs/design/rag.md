---
title: RAG 检索增强
parent: 设计概要
nav_order: 5
---

# RAG 检索增强（知识库）

> 面向想理解原理的读者：一套与 Agent 记忆**完全独立**的「知识写入 → 索引 → 检索 → 上下文注入」能力，
> 为问题提供后台维护的业务知识与引用依据。

## 1. 定位与边界

RAG 与 Agent 记忆是两类不同能力，相互独立、互不复用：

| 对比项 | Agent 记忆 | RAG 知识库 |
| --- | --- | --- |
| 数据来源 | 对话过程中产生的事实、摘要、归档 | 后台上传的业务文档 |
| 生命周期 | 跟随用户 / 会话 / Agent 演进 | 由管理员统一维护 |
| 隔离方式 | 按 `AgentScope` 隔离 | 全局共享，通过 `knowledgeBaseId` 组织 |
| 写入方式 | 对话后自动提炼 | 显式摄入、更新、删除、重建索引 |
| 检索目标 | 找回历史上下文 | 为问题提供业务知识和引用依据 |
| 数据模型 | `MemoryPage` | `RagDocument` / `RagChunk` |

关键约束：

- 不复用 / 不改造 `MemoryRetriever`、`MemoryPageStore` 与现有记忆检索器；
- 知识库全局共享，不接收 `AgentScope`；`knowledgeBaseId` 只表示业务知识集合；
- 两套检索结果仅在上下文组装阶段并列展示，不在检索层融合。

## 2. 能力链路

写入端：

```text
RagIngestionService
  |-- RagDocumentParser   解析（文本 / Markdown / PDF / Word）
  |-- RagChunker          切分（标题 / 空行 / 长度 / overlap）
  |-- RagEmbeddingGateway 批量向量化
  |-- RagIndexStore       写入索引（MySQL 文本权威存储 + Redis Stack 召回索引，双写）
  `-- RagDocumentStore    记录文档状态
```

检索端：

```text
RagRetrievalService
  |-- RagEmbeddingGateway 生成查询向量
  |-- RagIndexStore.search 向量召回（Redis KNN，score=1-余弦距离；关键词全文走 FT.SEARCH）
  |-- RagReranker         可选重排
  `-- List<RagSearchResult>（携带知识库 / 文档 / 分块引用）
```

Agent 集成（可选）：`RagContextProvider` 将检索结果以独立的「知识库参考」区注入上下文；
RAG 失败默认降级为空知识上下文，不阻断 Agent 主流程。

## 3. 关键设计决策

- **SPI 化扩展**：解析、切分、Embedding、文档存储、向量索引、重排均为独立 SPI，
  默认 Bean 以 `@ConditionalOnMissingBean` 注册，业务方可整体替换（如换 Milvus / PGVector / ES）。
- **多格式解析**：默认 `MultiFormatRagDocumentParser` 按内容类型 / 扩展名分发——文本 / Markdown 内置，
  PDF（PDFBox）与 Word（POI）为 optional 依赖，引入即启用、未引入则退化为纯文本解析并给出明确提示。
- **默认本地实现（=file）**：文本 / Markdown 解析 + 本地文件向量索引 + 余弦相似度检索，零依赖开箱即用；
  存储目录 `${user.dir}/.agent/rag`，与 `.agent/memory` 完全隔离。
- **Redis Stack 召回（内置推荐形态，=db）**：`RedisRagIndexStore` 组合「写 MySQL（`rag_index_entries` 文本 + 元数据，
  无向量列）→ 写 Redis Stack 索引」「删双侧」「搜 Redis KNN / 全文 + 回 MySQL 补 metadata」；召回依赖 Redis Stack
  （RediSearch，`FT.CREATE` / `FT.SEARCH`），Redis 丢失可从 MySQL 文本重新向量化重建（reindex）。
- **API 层访问控制（可选）**：`RagAccessPolicy` SPI 在 `agent.rag.access.enabled=true` 时于 REST 接口层
  按租户 / 用户做知识库可见性授权；关闭时全部放行，**不改变全局共享检索语义**。
- **容量与配额（可选）**：`agent.rag.capacity.*` 限制单知识库最大文档数、单文档最大分块数、
  单文档解析后最大字符数，0 表示不限制。
- **索引一致性**：索引元数据记录 `modelId` 与向量维度，避免 Embedding 模型切换导致维度不一致。
- **Embedding 批量约束**：模型对单次请求有批量上限（如阿里云 MaaS 为 20），
  Gateway 按 `max-batch-size` 内部分批，外层 `embedding-batch-size` 只控制吞吐分组。
- **写入幂等与原子性**：内容 `checksum` 幂等跳过；删除旧索引 → 写入新索引，失败不暴露半成品。

## 4. 包结构（按能力拆分）

```text
domain.rag            infrastructure.rag
├── model              ├── write
├── config             ├── embed
├── write              ├── store
├── embed              ├── retrieve
├── store              └── context
├── retrieve
└── context
```

领域层只放模型与 SPI，基础设施层放默认实现，新增能力 / 实现按职责落位对应子包即可。

## 5. 配置与启用

`agent.rag.enabled=true` 开启（默认关闭）；索引实现由 `agent.rag.provider` 选择：
`auto`（跟随 `agent.storage.type`：file→`local` 零依赖本地文件索引，db→`redis` 走 Redis Stack 召回，默认）
| `redis`（显式声明，与 auto + db 等价）。
依赖 OpenAI 兼容 `/embeddings` 接口，需在 `.env` 配置 `RAG_EMBEDDING_MODEL/BASE_URL/API_KEY`。

- PDF / Word 解析：引入 optional 依赖 `org.apache.pdfbox:pdfbox`、`org.apache.poi:poi-ooxml` 即自动启用；
- Redis 召回：`provider=redis`（或 `auto` + `STORAGE_TYPE=db`）时需保证 Redis Stack 可达（docker compose 起
  `redis/redis-stack-server`），索引首次写入自动 `FT.CREATE`；
- 访问控制：`agent.rag.access.enabled=true` 并注册 `RagAccessPolicy` Bean 后按租户 / 角色授权；
- 容量配额：`agent.rag.capacity.*`（见 [配置项速查](../reference/config-full.md)）。

完整配置见 [配置项速查](../reference/config-full.md) 与 [配置详解](../guide/configuration.md)。

## 6. 示例：example-web

- 后端：`example-web` 开启 RAG，并通过 [ExampleRagConfiguration](../reference/config-full.md) 演示 SPI 扩展——
  自定义 `ExampleRagChunker`（分块元数据打扩展标记）、`ExampleRagReranker`（二次排序 + 日志）。
- 前端：`example-web-frontend` 提供 **RAG 管理页**（知识库维护、文件上传、重建 / 删除、检索调试）
  与**对话页知识库选择**（本次对话注入哪些知识库，随 SSE 参数透传）。
- REST 接口：见 [REST API 速查](../reference/rest-api.md) 的 `/rag` 段。

---

相关：[记忆模型](memory-model.md) ｜ [配置详解](../guide/configuration.md) ｜ [REST 速查](../reference/rest-api.md)
