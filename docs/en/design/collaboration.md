---
title: Multi-Agent Orchestration
parent: Design Overview (EN)
nav_order: 3
---

# Multi-Agent Orchestration

> For readers who want to understand the principles: beyond a single agent, how multiple specialist agents are organized to collaborate on tasks.

## 1. Orchestration Abstraction (SPI)

- [ ] `AgentOrchestrator` interface: `type` / `validate` / `orchestrate`
- [ ] `OrchestratorRegistry` auto-collects registered orchestration plugins
- [ ] Orchestration selection: explicit specification > default (`agent.orchestration`, default routing)

## 2. Three Built-in Orchestrations

| Type | Description | Use cases |
| --- | --- | --- |
| `routing` | A single specialist handles it independently (intent routing selects the agent) | Default fallback |
| `conversational` | Multi-party specialists discuss over multiple rounds + convergence (consensus / moderation / best-of) | Technology selection, solution comparison |
| `delegate` | The main agent plans a Todo → delegates to sub-agents for parallel / recursive execution | Complex multi-step tasks |

## 3. Collaboration Tools (self-initiated)

- [ ] `invoke_discussion` / `invoke_delegate` are global tools, initiated at the main agent's own discretion within ReAct
- [ ] Nested composition and cycle prevention (A→B→A detection)
- [ ] Approval gate (`approvalGate`), dynamic replanning (`replanRounds`)

## 4. Configuration

- [ ] `orchestrations.json` defines orchestrations (see [guide/agents-config.md](../guide/agents-config.md))

---

See also: [Agent and Orchestration Configuration](../guide/agents-config.md)
