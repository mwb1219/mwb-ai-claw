---
title: Extensibility Design
parent: Design Overview (EN)
nav_order: 8
---

# Extensibility Design

> For readers who want to understand the principles and extend the framework: "why" and "how" this project is designed to be extensible.
> From both the user-usage and the design perspectives, this doc explains how new capabilities grow without touching the kernel.

## 1. Design Intent (Why)

The project is an "extensible AI Agent framework" where extensibility is a first-class concern, not a later patch. The design intent can be summarized in five points:

1. **Small stable kernel, capabilities plugged in**: the main pipeline (ReAct loop, session management, memory distillation) stays stable; model, tool, memory, retrieval and orchestration are all abstracted behind Gateway / SPI interfaces. Business capabilities are plugged in as "plugins / configs" instead of modifying the kernel.
2. **Usable by default, enhanced on demand**: every extension point ships with a ready-to-use default implementation (OpenAI-compatible LLM, file-backed memory, local-file RAG index, etc., with no hard third-party dependencies). Replace or enhance only when you need more advanced capability.
3. **Replacement and enhancement go hand in hand**: `@ConditionalOnMissingBean` lets you **replace** a default implementation entirely (swap vector store, model provider, or memory storage); the **wrapping/enhancement** path is also preserved (decorators, rerankers for post-processing). Neither path touches the main pipeline.
4. **The domain layer is framework-free**: the domain layer only defines interfaces, models and domain services (SPI); all implementations live in the infrastructure layer. Spring only handles assembly and never intrudes into domain logic (dependency inversion).
5. **Config-driven overrides, no repackaging**: placing same-named files (`agents.json` / `orchestrations.json` / `skills/` / `mcp-server.json` / `.env`) in the run directory overrides the built-in defaults; restart to take effect.

## 2. User Perspective: Zero-Code Extension (How)

For users, most extensions **require no Java code**:

| Extension method | What you can do | Where to put it | Reference |
| --- | --- | --- | --- |
| Config override | Add / adjust specialist Agents, orchestration, model providers | run-dir `agents.json` / `orchestrations.json` | [Agents & Orchestrations Configuration](../guide/agents-config.md) |
| Skills | drop a `SKILL.md` directory to give the Agent a new capability | `user.dir/skills` or `agent.skills-dir` | [Skills System](../guide/skills.md) |
| MCP tools | integrate the external tool ecosystem via the standard protocol | run-dir `mcp-server.json` | [MCP Tools Integration](../guide/mcp.md) |
| Independent models | per-Agent `model` / `baseUrl` / `apiKey` / `temperature` | `agents.json` + `.env` | [Configuration](../guide/configuration.md) |
| Feature toggles | enable RAG, skills, storage backend, etc. on demand | `application.yml` (e.g. `agent.rag.enabled`) | [Configuration](../guide/configuration.md) |

Without understanding SPI or bean assembly, users can already "give the Agent new specialists, new skills, new tools, and new capabilities".

## 3. Design Perspective: SPI Extension Point Overview

The interfaces below are defined in the domain layer, with default implementations in the infrastructure layer, all registered via `@ConditionalOnMissingBean` (declaring a bean of the same interface skips the default automatically).

### 3.1 Model & Reasoning

| Extension point | Interface | Default implementation | How to override |
| --- | --- | --- | --- |
| LLM calls | `LlmGateway` | `LlmGatewayImpl` (OpenAI-compatible + multi-provider) | `@Bean` override / `ClawRuntime.register()` |
| Text embedding | `EmbeddingGateway` | `OpenAiEmbeddingGateway` | same as above |
| Intent routing | `AgentRouter` | `CompositeAgentRouter` (rules first → LLM fallback) | implement the interface and assemble |
| Context assembly | `ContextAssembler` | `DefaultContextAssembler` | implement the interface and assemble |

### 3.2 Tools

| Extension point | Interface | Default implementation | How to override |
| --- | --- | --- | --- |
| Tool execution gateway | `ToolGateway` | `ToolGatewayImpl` | `@Bean` override |
| Single tool | `ToolExecutor` | built-in tools | add a `@Component`; auto-collected |
| Permission / approval | `ToolPermissionChecker` / `ToolApproval` | config-driven implementations | implement the interface |
| Dynamic registration | `DynamicToolRegistry` | - | register new tools |

### 3.3 Memory

| Extension point | Interface | Default implementation | How to override |
| --- | --- | --- | --- |
| Memory read/write | `MemoryGateway` / `LongTermMemoryGateway` / `LayeredMemoryGateway` | file / JDBC dual implementations | conditional assembly / `@Bean` override |
| Page storage | `MemoryPageStore` | file / JDBC | conditional assembly |
| Eviction policy | `PageEvictionPolicy` | token / importance | implement the interface (`agent.memory.eviction-policy`) |
| Retrieval | `MemoryRetriever` | keyword / vector / hybrid | implement the interface (`agent.memory.retriever`) |
| Fact synthesis | `MemorySynthesizer` | LLM-based (small-model optional) | implement the interface |
| Synthesis task queue | `SynthesisTaskQueue` | `LockSynthesisTaskQueue` (Phase 1, distributed lock) / `LocalSynthesisTaskQueue` (local fallback) | Implement interface (`agent.memory.synthesis-queue-type`, see [Layered Memory Model](memory-model.md) §5) |
| Synthesis cache | `SynthesisCache` | `LocalSynthesisCache` / `RedisSynthesisCache` (switched by storage form) | Conditional assembly (`agent.memory.synthesis-cache-type`) |

### 3.3.1 Distributed lock (infrastructure extension point)

`DistributedLock` is an infrastructure-layer technical extension point (not a domain SPI), wrapping "acquire → renew → release" for reuse by all distributed mutual exclusion (session lock, synthesis lock, etc.):

| Interface | Default impl | Override |
| --- | --- | --- |
| `DistributedLock` | `RedisDistributedLock` (Hash reentrant by default + watchdog renew) | `@Bean` override (e.g. ZK / etcd impl) |

> Paired with `LockOptions` (tryLock / tryLockWithRenew / wait — three acquire strategies) + `LockResult` (with acquire elapsed and failure reason). See [Horizontal Scaling](horizontal-scaling.md) §4.1.

### 3.4 Multi-Agent Orchestration

| Extension point | Interface | Default implementation | How to override |
| --- | --- | --- | --- |
| Orchestration plugin | `AgentOrchestrator` | routing / conversational / delegate | implement + register Bean + define in `orchestrations.json` |
| Execution unit | `ExecutionUnit` | `ExecutionUnitImpl` | implement the interface |

See [Multi-Agent Orchestration](collaboration.md).

### 3.5 RAG (Knowledge Base)

The whole pipeline consists of independent SPIs, all default beans registered with `@ConditionalOnMissingBean`:

- Write path: `RagDocumentParser` (parse) / `RagChunker` (chunk) / `RagEmbeddingGateway` (embed) / `RagIndexStore` (vector index) / `RagDocumentStore` (document state)
- Retrieve path: `RagRetrievalService` / `RagReranker` (optional rerank)
- Agent integration: `RagContextProvider` (inject into context)

Can be **fully replaced** (e.g. Milvus / PGVector / ES) or **enhanced** (decorator / reranker) without changing the pipeline.

### 3.6 Skills & Tenancy

| Extension point | Interface | Description |
| --- | --- | --- |
| Skills | `SkillGateway` | skill discovery and on-demand loading, controlled by `agent.skills-enabled` |
| Tenancy | `TenantGateway` / `AgentScopeResolver` | multi-tenancy and request-scope resolution |

## 4. Replacement vs Enhancement: Two Extension Modes

### Mode 1: Replacement (`@ConditionalOnMissingBean`)

Default implementations are registered with `@Bean + @ConditionalOnMissingBean`; once you declare a **same-interface** bean, the default is skipped automatically. For example, replacing the RAG vector index:

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

### Mode 2: Enhancement (wrapping / decorator)

When you don't want to throw away the default but only add logic around it, wrap one layer. For example, the `example-web` extension demo:

- `ExampleRagChunker`: wraps `TextRagChunker`, appending extension metadata to chunks (replacement-style enhancement);
- `ExampleRagReranker`: re-ranks after retrieval and logs (enhancement-style, does not change the retrieval pipeline).

> The very existence of "enhancement-type" extension points (like rerankers) embodies the idea of "a stable main pipeline with capabilities growing at the edges".

## 5. Assembly Mechanism: Conditional Assembly & Embedded Registration

- **Conditional assembly**: enable or switch capabilities by config, e.g. `agent.rag.enabled` (RAG off by default), `agent.skills-enabled`, `agent.storage.type=file|db` (storage backend), `agent.memory.enabled` / `agent.memory.retriever`.
- **Starter auto-assembly**: `ClawAutoConfiguration` collects the `infrastructure` / `app` / `adapter` packages via `@ComponentScan`; `ClawCoreAutoConfiguration` registers the default beans (all with `@ConditionalOnMissingBean`).
- **Embedded registration**: `ClawRuntime.Builder.register(Class)` registers user components into the Spring context before framework assembly — the framework defaults' `@ConditionalOnMissingBean` automatically skips same-named beans, letting embedded users replace components with zero configuration.

## 6. How to Extend (step by step)

1. **Locate the extension point**: determine which capability you're extending (swap model / add tool / change memory / new orchestration / RAG / skill / MCP);
2. **Choose the mode**: use config when it can be solved by config (Section 2), otherwise replace or enhance the SPI (Section 4);
3. **Implement & verify**: declare `@Bean` / `@Component` or place a `SKILL.md` / `mcp-server.json`, restart and watch the logs (e.g. "已加载编排", "已加载技能 [n]"), then run one end-to-end case.

You can use `example-web`'s `ExampleRagConfiguration` / `ExampleRagChunker` / `ExampleRagReranker` as the minimal reference for SPI replacement and enhancement (see the RAG design doc in the Chinese section).

**Flagship example: example-commerce (combined extension points)**

T1/T2 have landed a fully runnable end-to-end business example. To feel how "multi-extension-point, pluggable" works together in a real business, run [example-commerce](https://github.com/mwb1219/mwb-ai-claw/tree/master/example-commerce) (an e-commerce / marketing assistant): a "list products → view campaigns → generate a promo plan" flow combines custom business tools (`list_products` / `list_orders` / `list_campaigns`, auto-collected via `@Component`), a custom orchestration (`marketing`, registered into `OrchestratorRegistry`), multi-tenancy (`CommerceTenantGateway` resolving API keys into isolated stores), and more. Its README provides the "default impl / SPI / how to override / how to enhance" matrix for each extension point — a blueprint for integrating real businesses.

---

See also: [Architecture](architecture.md) ｜ [Multi-Agent Orchestration](collaboration.md) ｜ [Layered Memory](memory-model.md) ｜ [Configuration](../guide/configuration.md)
