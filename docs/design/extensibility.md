---
title: 扩展能力设计
parent: 设计概要
nav_order: 8
---

# 扩展能力设计

> 面向想理解原理并打算扩展框架的读者：这个项目"为什么"以及"如何"设计成可扩展的。
> 本文从「用户使用」与「设计」两个视角，说明新的能力如何在不改动内核的前提下生长出来。

## 1. 设计初衷（Why）

项目定位是「可扩展的 AI Agent 框架」，扩展性是一等公民，而非事后打补丁。设计初衷可归纳为五点：

1. **内核小而稳，能力外挂**：ReAct 循环、会话管理、记忆提炼等主链路保持稳定；模型、工具、记忆、检索、编排全部抽象为 Gateway / SPI 接口，业务能力以"插件 / 配置"形式外挂，而不是改内核。
2. **默认可用、按需增强**：每个扩展点都提供开箱即用的默认实现（OpenAI 兼容 LLM、文件存储记忆、本地文件 RAG 索引等，零第三方硬依赖）；需要更高级能力时才替换或增强。
3. **替换与增强并举**：`@ConditionalOnMissingBean` 让业务方可**整体替换**默认实现（换向量库、换模型供应商、换记忆存储）；同时保留**包装增强**路径（装饰器、Reranker 二次加工）。两条路径都不改主链路。
4. **领域层不依赖框架**：domain 层只定义接口、模型与领域服务（SPI），实现全部落在 infrastructure 层；Spring 仅负责装配，不侵入领域逻辑（依赖倒置）。
5. **配置化覆盖，无需重新打包**：`agents.json` / `orchestrations.json` / `skills/` / `mcp-server.json` / `.env` 在运行目录放同名文件即可覆盖内置默认，改完重启即生效。

## 2. 用户视角：零代码扩展（How）

面向使用者，绝大多数扩展**不需要写 Java 代码**：

| 扩展方式 | 能做什么 | 放在哪里 | 参考 |
| --- | --- | --- | --- |
| 配置覆盖 | 新增 / 调整专家 Agent、协作编排、模型供应商 | 运行目录 `agents.json` / `orchestrations.json` | [Agent 注册表与编排配置](../guide/agents-config.md) |
| 技能（Skill） | 放一个 `SKILL.md` 目录即可让 Agent 获得新能力 | `user.dir/skills` 或 `agent.skills-dir` | [技能系统](../guide/skills.md) |
| MCP 工具 | 通过标准协议接入外部工具生态 | 运行目录 `mcp-server.json` | [MCP 工具接入](../guide/mcp.md) |
| 独立模型 | 每个 Agent 独立 `model` / `baseUrl` / `apiKey` / `temperature` | `agents.json` + `.env` | [配置详解](../guide/configuration.md) |
| 开关启用 | 按需开启 RAG、技能、存储后端等能力 | `application.yml`（`agent.rag.enabled` 等） | [配置详解](../guide/configuration.md) |

用户无需理解 SPI 与 Bean 装配，即可完成「让 Agent 认识新专家、获得新技能、接入新工具、启用新能力」这类扩展。

## 3. 设计视角：SPI 扩展点总览

以下接口定义在 domain 层，默认实现位于 infrastructure 层，均以 `@ConditionalOnMissingBean` 注册（用户声明同接口 Bean 即自动跳过默认实现）。

### 3.1 模型与推理

| 扩展点 | 接口 | 默认实现 | 覆盖方式 |
| --- | --- | --- | --- |
| LLM 调用 | `LlmGateway` | `LlmGatewayImpl`（OpenAI 兼容 + 多 Provider） | `@Bean` 覆盖 / `ClawRuntime.register()` |
| 文本向量化 | `EmbeddingGateway` | `OpenAiEmbeddingGateway` | 同上 |
| 意图路由 | `AgentRouter` | `CompositeAgentRouter`（规则优先 → LLM 兜底） | 实现接口并装配 |
| 上下文组装 | `ContextAssembler` | `DefaultContextAssembler` | 实现接口并装配 |

### 3.2 工具

| 扩展点 | 接口 | 默认实现 | 覆盖方式 |
| --- | --- | --- | --- |
| 工具执行网关 | `ToolGateway` | `ToolGatewayImpl` | `@Bean` 覆盖 |
| 单个工具 | `ToolExecutor` | 内置工具 | 新增 `@Component` 自动收集 |
| 权限 / 审批 | `ToolPermissionChecker` / `ToolApproval` | 配置驱动实现 | 实现接口 |
| 动态注册 | `DynamicToolRegistry` | - | 注册新工具 |

### 3.3 记忆

| 扩展点 | 接口 | 默认实现 | 覆盖方式 |
| --- | --- | --- | --- |
| 记忆读写 | `MemoryGateway` / `LongTermMemoryGateway` / `LayeredMemoryGateway` | 文件 / JDBC 双实现 | 条件装配切换 / `@Bean` 覆盖 |
| 页面存储 | `MemoryPageStore` | 文件 / JDBC | 条件装配 |
| 换页策略 | `PageEvictionPolicy` | token / importance | 实现接口（`agent.memory.eviction-policy`） |
| 检索召回 | `MemoryRetriever` | 关键词 / 向量 / 混合 | 实现接口（`agent.memory.retriever`） |
| 事实提炼 | `MemorySynthesizer` | LLM 提炼（可配小模型） | 实现接口 |

### 3.4 多 Agent 编排

| 扩展点 | 接口 | 默认实现 | 覆盖方式 |
| --- | --- | --- | --- |
| 编排插件 | `AgentOrchestrator` | routing / conversational / delegate | 实现接口 + 注册 Bean + `orchestrations.json` 定义 |
| 执行单元 | `ExecutionUnit` | `ExecutionUnitImpl` | 实现接口 |

详见 [多 Agent 编排](collaboration.md)。

### 3.5 RAG（知识库）

全链路均为独立 SPI，默认 Bean 以 `@ConditionalOnMissingBean` 注册：

- 写入端：`RagDocumentParser`（解析）/ `RagChunker`（切分）/ `RagEmbeddingGateway`（向量化）/ `RagIndexStore`（向量索引）/ `RagDocumentStore`（文档状态）
- 检索端：`RagRetrievalService` / `RagReranker`（可选重排）
- Agent 集成：`RagContextProvider`（注入上下文）

可**整体替换**（如换 Milvus / PGVector / ES）或**增强**（装饰器 / Reranker 二次加工），见 [RAG 检索增强](rag.md)。

### 3.6 技能与租户

| 扩展点 | 接口 | 说明 |
| --- | --- | --- |
| 技能 | `SkillGateway` | 技能发现与按需加载，受 `agent.skills-enabled` 控制 |
| 租户 | `TenantGateway` / `AgentScopeResolver` | 多租户与请求级 scope 解析 |

## 4. 替换与增强：两种扩展模式

### 模式一：替换（`@ConditionalOnMissingBean`）

默认实现以 `@Bean + @ConditionalOnMissingBean` 注册，用户声明**同接口** Bean 后，默认实现自动跳过。例如替换 RAG 向量索引：

```java
@Configuration
public class MilvusRagConfig {
    @Bean
    @ConditionalOnMissingBean(RagIndexStore.class)
    public RagIndexStore milvusIndexStore() {
        return new MilvusRagIndexStore();
    }
}
```

### 模式二：增强（包装 / 装饰器）

不想推翻默认实现，只在前后追加逻辑时，包装一层默认实现即可。例如 `example-web` 的扩展演示：

- `ExampleRagChunker`：包装 `TextRagChunker`，在分块结果上追加扩展元数据（替换型增强）；
- `ExampleRagReranker`：在召回后做二次排序并记录日志（增强型扩展，不改检索链路）。

> Reranker 这类"增强型"扩展点的存在本身，就是"主链路保持稳定、能力在边缘生长"的体现。

## 5. 装配机制：条件装配与嵌入式注册

- **条件装配**：按配置启用 / 切换能力，典型如 `agent.rag.enabled`（RAG 默认关闭）、`agent.skills-enabled`、`agent.storage.type=file|db`（存储后端）、`agent.memory.enabled` / `agent.memory.retriever`。
- **Starter 自动装配**：`ClawAutoConfiguration` 通过 `@ComponentScan` 收集 `infrastructure` / `app` / `adapter` 包；`ClawCoreAutoConfiguration` 统一注册各默认 Bean（均带 `@ConditionalOnMissingBean`）。
- **嵌入式注册**：`ClawRuntime.Builder.register(Class)` 在启动前将用户组件注册进 Spring 上下文，随后再装配框架默认 Bean——默认实现上的 `@ConditionalOnMissingBean` 会自动跳过同名 Bean，实现"嵌入式用户零配置替换组件"。

## 6. 扩展流程（How to extend）

1. **对号入座**：判断要扩展的是哪类能力（换模型 / 加工具 / 改记忆 / 新增编排 / 接 RAG / 加技能 / 接 MCP）；
2. **选模式**：能配置解决的用配置（第 2 节），否则按 SPI 替换或增强（第 4 节）；
3. **实现与验证**：声明 `@Bean` / `@Component` 或放置 `SKILL.md` / `mcp-server.json`，重启后观察日志（如「已加载编排」「已加载技能 [n]」），并跑通一条用例。

可直接参考 `example-web` 的 `ExampleRagConfiguration` / `ExampleRagChunker` / `ExampleRagReranker`（见 [RAG 检索增强](rag.md)）作为 SPI 替换与增强的最小示例。

---

相关：[总体架构](architecture.md) ｜ [多 Agent 编排](collaboration.md) ｜ [RAG 检索增强](rag.md) ｜ [分层记忆模型](memory-model.md) ｜ [配置详解](../guide/configuration.md)
