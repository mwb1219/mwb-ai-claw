---
title: Multi-Agent Orchestration
parent: Design Overview (EN)
nav_order: 3
---

# Multi-Agent Orchestration

> For readers who want to understand the principles: beyond a single agent, how multiple specialist agents are organized to collaborate on tasks.

## 1. Orchestration Abstraction (SPI)

Multi-agent orchestration is implemented through the pluggable `AgentOrchestrator` SPI, decoupling orchestration modes from the main pipeline. To add a new orchestration mode you don't need to touch the main pipeline; just:

1. Implement the `AgentOrchestrator` interface and register it as a Spring Bean;
2. Add one orchestration definition to `orchestrations.json` (with `type` pointing to the new mode).

### 1.1 AgentOrchestrator Interface

Defined in the domain layer SPI package (`com.mwb.ai.claw.domain.collaboration.spi.AgentOrchestrator`):

| Method | Description |
| --- | --- |
| `String type()` | Orchestration type identifier (globally unique, matches `OrchestrationDefinition.type`) |
| `void validate(OrchestrationDefinition)` | Startup config validation (empty default), throws on invalid config (fail-fast) |
| `CollaborationResult orchestrate(OrchestrationContext)` | Executes one collaborative orchestration, returning the final reply / leading agent / trace |

The orchestration input is carried by `OrchestrationContext` (scope / message / sessionId / explicitAgentId / definition / agentGateway / executionUnit / callback / streamCallback). Orchestrators resolve agents through `AgentGateway` and run sessions & ReAct, locking, and artifact persistence through `ExecutionUnit`; `config` is loose JSON interpreted by each plugin into typed objects (e.g. `ConversationDefinition` / `DelegateDefinition`).

### 1.2 OrchestratorRegistry

Auto-collects all `AgentOrchestrator` Beans at startup and indexes them by `type()`:

- `resolve(definition)`: looks up the plugin by `type` and runs `validate`, throwing on unregistered types or invalid config;
- Duplicate `type` registration fails fast at startup (`编排类型重复注册: xxx`).

### 1.3 Orchestration Selection

| Priority | Path | Description |
| --- | --- | --- |
| 1 | Explicit | `ChatCmd.orchestrationId` / collaboration tool `invoke_*` references an orchestration id |
| 2 | Default | `agent.orchestration` config (references an id in orchestrations.json, default `routing`) |

> Multi-agent orchestrations (conversational / delegate) are usually not triggered by pre-message intent routing; the main Agent initiates them autonomously through the global `invoke_*` tools in the ReAct loop.

## 2. Three Built-in Orchestrations

| Type | Description | Use cases |
| --- | --- | --- |
| `routing` | A single specialist handles it independently (intent routing selects the agent) | Default fallback |
| `conversational` | Multi-party specialists discuss over multiple rounds + convergence (consensus / moderation / best-of) | Technology selection, solution comparison |
| `delegate` | The main agent plans a Todo → delegates to sub-agents for parallel / recursive execution | Complex multi-step tasks |

### 2.1 routing (specialist routing)

Moved from the original single-agent pipeline, behavior unchanged:

```text
explicit agentId? ──yes──> use that Agent directly
     │no
   intent routing (AgentRouter) hit? ──yes──> use the routed Agent
     │no
   fall back to the default Agent
```

Flow: session-granularity locking by sessionId (serialize "get session → append message → ReAct → save → memory distillation" per session) → append user message (multimodal parts supported) → run ReAct → merge trace steps → persist the session → layered-memory distillation (failure only warns, never blocks the response).

### 2.2 conversational (discussion)

Multiple specialist Agents discuss the same task over multiple rounds, then converge to a final conclusion. Participants run in temporary sessions (context isolated, not persisted).

```text
First round (parallel): each participant independently gives their professional opinion (confidence 0-1)
    │
Discussion rounds (serial, r = 2..rounds): participants see the other experts' last visibleHistory rounds and respond (agree / question / supplement)
    │   (with convergence=consensus, converge early when support >= minConsensus)
    ▼
Convergence: consensus / moderator / best produces the final conclusion
```

- **First round parallel**: participants speak through a fixed thread pool (no streaming output in the first round, to avoid interleaved terminal output);
- **Discussion rounds serial**: only the other experts' history is injected (not their own), truncated by `visibleHistory` to control context usage;
- **Convergence strategies**:
  - `consensus`: counts speeches containing consensus markers (同意/赞同/支持/一致…), takes the one with most support; falls back to moderator when no consensus;
  - `best`: parses "置信度: 0.x" annotations, takes the highest-confidence one; falls back to moderator when none annotated;
  - `moderator` (default): the decision-moderator Agent reads the whole discussion transcript and outputs a clear, actionable final conclusion.

### 2.3 delegate (task breakdown & delegation)

The main Agent (planner) breaks the task into a Todo list and delegates execution to sub-agents; while executing, a sub-agent may plan sub-todos and delegate to the next level (recursive, bounded by `maxDepth` / `maxTodos`). Each layer's planner summarizes its sub-results; the root planner finally outputs the overall conclusion.

```text
Plan: the planner Agent breaks the task into Todo JSON (todoId/title/description/agentId/dependsOn)
    │   non-JSON output retried once, then degrades to direct execution
    ▼
Approval gate (optional): gated layers pause after planning, waiting for approve/reject (timeout approvalTimeoutMs degrades to direct execution)
    ▼
Execute: Kahn topological layering into Waves (independent todos run in parallel, concurrency)
    │   sub-todos: recursive re-planning on non-leaf layers / nested orchestration via orchestrationId / direct execution on leaf layers
    │   (with replanRounds > 0: after each Wave the planner re-plans remaining todos)
    ▼
Summarize: the planner collects all sub-results, passing via resultPass (text inlined into the prompt / file written to disk)
    │   when sub-results > topK, compress to top-k by relevance to the parent task
    ▼
Final reply (root node)
```

Key mechanisms:

- **Topological sorting**: `dependsOn` declares dependencies, Kahn layering produces parallelizable Waves; a dependency cycle falls back to declaration-order serial execution;
- **Recursive delegation**: `maxDepth` bounds recursion depth, `maxTodos` caps todos per layer (truncated with a warning); when the planner considers a task trivial (a single todo assigned to itself) it executes directly to avoid pointless recursion;
- **Dynamic planning** (`replanRounds` > 0): after each Wave, the planner re-plans the remaining todos using obtained results (full replacement or incremental `adjust` with keep/drop/modify);
- **Cycle prevention**: a per-thread nested call chain detects A→B→A circular references and aborts immediately;
- **Memory distillation**: leaf-todo conclusions are distilled into FACT memory keyed by `delegate-todo:{path}` (importance 1.0; failures only warn);
- **Artifact persistence**: plans / results are written per layer path into the isolated `{workdir}/{namespace}/{sessionId}/{timestamp}` directory (tenant artifacts are mutually invisible).

## 3. Collaboration Tools (self-initiated)

- [ ] `invoke_discussion` / `invoke_delegate` are global tools (global=true), no need to declare in config; the main Agent decides autonomously within ReAct based on the task nature
- [ ] Mapping: `invoke_discussion` → `team-discussion` (conversational), `invoke_delegate` → `todo-delegate` (delegate)
- [ ] Nested composition & cycle prevention: a delegate todo can specify `orchestrationId` to invoke another orchestration (e.g. conversational); the nested call chain detects circular references
- [ ] Approval gate (`approvalGate`), dynamic re-planning (`replanRounds`)

## 4. Configuration

- [ ] `orchestrations.json` defines orchestrations: complete config examples and field-by-field explanation in [guide/agents-config.md](../guide/agents-config.md)
- [ ] Loading priority: run directory (user.dir) → user config directory (`$MWB_AI_CLAW_HOME/config/`) → classpath built-in defaults; first hit wins, lower-priority sources are not read
- [ ] Startup validation (fail-fast): unique `id`s, registered `type`s, existing referenced `agentId`s, valid plugin-level config

---

See also: [Agent and Orchestration Configuration](../guide/agents-config.md)
