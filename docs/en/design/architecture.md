---
title: Overall Architecture
parent: Design Overview (EN)
nav_order: 1
---

# Overall Architecture

> For readers who want to understand the principles: how this project is layered, how modules are organized, and how the runtime is assembled.
> For functional details involving code, see the individual design docs (core-loop / collaboration / memory-model, etc.).

## 0. Position: This Is a Java Agent Harness

> mwb-ai-claw is a **Java Agent Harness** — it assembles the model + execution loop + tool safety + memory + sessions + config-driven agent definitions + observability into an out-of-the-box, deployable agent.
> If LangChain4j / Spring AI supply the **parts** for building a harness (`ChatClient`-level building blocks), this framework is the **already-assembled harness**.
> That role is reflected across the subsystems: the ReAct loop ([core-loop](core-loop.md)), tool sandbox & approval gates ([security](security.md)), layered memory ([memory-model](memory-model.md)), config-driven agents/orchestration ([agents config](../guide/agents-config.md)), and multi-tenant / multi-instance storage ([storage-multitenancy](storage-multitenancy.md) / [horizontal-scaling](horizontal-scaling.md)) together form the runtime scaffolding around the model.

## 1. Layered Model (COLA / DDD)

```
┌─────────────────────────────────────────────┐
│  adapter (adapter layer)                     │
│  AgentController(REST/SSE) / WebSocket /     │
│  AgentShell — protocol conversion,           │
│  forwarded to the app layer                  │
└──────────────────────┬──────────────────────┘
                       ▼
┌─────────────────────────────────────────────┐
│  app (application layer)                     │
│  AgentServiceImpl / ChatCmdExe               │
│  (orchestration selection + dispatch)        │
│  ClawRuntime (embedded entry, no web         │
│  container)                                  │
└──────────────────────┬──────────────────────┘
                       ▼
┌─────────────────────────────────────────────┐
│  domain (domain layer)                       │
│  Aggregates: Session / Agent / Message       │
│  Domain service: ReActLoopService            │
│  Gateway interfaces: Llm / Tool /            │
│  Memory / Agent                              │
│  collaboration: orchestration SPI +          │
│  ExecutionUnit                               │
│  + AgentRouter (intent routing SPI)         │
│  context: ContextAssembler (context          │
│  assembly SPI)                               │
│  Callbacks: ProgressCallback /               │
│  LlmStreamCallback                           │
└──────────────────────┬──────────────────────┘
                       ▼
┌─────────────────────────────────────────────┐
│  infrastructure (infrastructure layer)       │
│  LlmGatewayImpl / ToolGatewayImpl / MCP      │
│  Memory implementations / orchestration      │
│  implementations (routing/conversational/    │
│  delegate) + AgentRouter routing impls (3)  │
│  DefaultContextAssembler / agent config      │
│  loading                                     │
└─────────────────────────────────────────────┘
```

**Dependency direction**: `adapter / app / infrastructure` → `client + domain`; `domain` depends on no lower layer.

> **Layering convention**: SPI interfaces live in the domain layer (`AgentOrchestrator` / `AgentRouter` / `ContextAssembler`); implementations live in the infrastructure layer (`RoutingOrchestrator` and other orchestration impls, `RuleBasedAgentRouter` and other routing impls, `DefaultContextAssembler`). Adding a new routing strategy only requires implementing `AgentRouter` and registering it as a Spring Bean.

## 2. Module Structure (Maven multi-module)

| Module | Responsibility |
| --- | --- |
| `mwb-ai-claw-client` | Client SDK: `AgentServiceI` interface, DTOs (ChatCmd / SessionDTO / SingleResponse) |
| `mwb-ai-claw-domain` | Domain layer: aggregates, domain services, Gateway interfaces, value objects |
| `mwb-ai-claw-infrastructure` | Infrastructure: LLM / tools / MCP / memory / orchestration / config-loading implementations |
| `mwb-ai-claw-adapter` | Adapter layer: REST / SSE / WebSocket / Shell terminal |
| `mwb-ai-claw-app` | Application layer: use-case executors (ChatCmdExe, etc.), `ClawRuntime` embedded entry |
| `mwb-ai-claw-spring-boot-starter` | Starter auto-configuration entry (`ClawAutoConfiguration`) |
| `start` | Server example: Web / Shell dual-mode executable application |
| `example-embed` / `example-web` | Embedded / standalone Web examples |

## 3. Runtime Assembly Flow

1. The startup class imports `ClawAutoConfiguration` (starter), whose `@ComponentScan` scans the
   `infrastructure`, `app`, and `adapter` packages;
2. `ClawCoreAutoConfiguration` in `infrastructure` conditionally assembles the infrastructure beans
   (e.g., MySQL storage + Redis Stack retrieval are assembled when `agent.storage.type=db`);
3. `AgentConfiguration` loads `agents.json` / `orchestrations.json`, registering the agent registry and the orchestration registry;
4. Application-layer executors (ChatCmdExe, etc.) select an orchestration per request and drive the ReAct loop.

## 4. Core Extension Points (dependency inversion)

| Extension point | Interface | Default implementation | Override method |
| --- | --- | --- | --- |
| LLM calls | `LlmGateway` | `LlmGatewayImpl` (OpenAI-compatible + multiple providers) | `@Bean` override / `ClawRuntime.register()` |
| Tools | `ToolGateway` / `ToolExecutor` | `ToolGatewayImpl` + built-in tools | New `@Component` tools are auto-collected |
| Memory | `MemoryGateway` / `LongTermMemoryGateway` | File / JDBC dual implementations | Conditional assembly switch |
| Agent config | `AgentGateway` | `AgentGatewayImpl` | agents.json configuration |
| Orchestration | `AgentOrchestrator` (SPI) | routing / conversational / delegate | Register a `type` plugin |
| Intent routing | `AgentRouter` (SPI) | `CompositeAgentRouter` (= rule + LLM) | New `@Component` routing impl |
| Context assembly | `ContextAssembler` (SPI) | `DefaultContextAssembler` (layered memory context assembly) | `@Bean` override |

## 5. Dual Mode and Embedded

- **Web mode** (`spring.profiles.active=web`): full server, REST / SSE / WebSocket;
- **Shell mode** (`shell`): JLine interactive terminal, installable as a global `mwb-ai-claw` command with one click;
- **Embedded** (`ClawRuntime`): no web container, reuses the same bean assembly, for integration by other JVM applications.

> All three entry points reuse exactly the same domain / infrastructure core; only the adapter layer differs.

---

See also: README.md | [ReAct Reasoning Loop](core-loop.md) | [Multi-Agent Orchestration](collaboration.md)
