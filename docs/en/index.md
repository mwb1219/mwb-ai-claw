---
title: Documentation (English)
---

# mwb-ai-claw Documentation

> English documentation hub. Organized as **User Guide → Design Overview → Quick Reference**.
> New to the project? Start with the [Quick Start](guide/quick-start.md).

> 中文文档：[Chinese](../index.md) — 文档以中文为第一语言，英文版为翻译镜像。

## Quick Start (3 minutes)

- [guide/quick-start.md](guide/quick-start.md) — First chat in minutes (install → configure keys → Shell/Web modes)

---

## 📘 User Guide (guide/)

| Doc | Content | Audience |
| --- | --- | --- |
| [install.md](guide/install.md) | Install & run: source / binary package / install script / dual-mode startup | First-time installers |
| [configuration.md](guide/configuration.md) | Configuration deep dive: `.env`, `application.yml`, three-level loading priority, `STORAGE_TYPE` and other env vars | Anyone tuning config |
| [shell-usage.md](guide/shell-usage.md) | Shell mode: slash commands / launch args / multimodal images / headless | Terminal users |
| [web-usage.md](guide/web-usage.md) | Web mode: startup, REST / WebSocket / SSE APIs, auth, frontend examples | Server deployers |
| [embedding.md](guide/embedding.md) | Embedded integration: `ClawRuntime` (streaming chat, multi-tenant scope) | Java integrators |
| [agents-config.md](guide/agents-config.md) | Agent registry `agents.json` + orchestration registry `orchestrations.json` | Extending agents/orchestrations |
| [skills.md](guide/skills.md) | Skill system: directory layout, `SKILL.md` spec, three-level loading | Adding skills |
| [mcp.md](guide/mcp.md) | MCP tool integration: stdio / streamable_http, `mcp-server.json` | Integrating external tools |

## 🏗️ Design Overview (design/)

| Doc | Content |
| --- | --- |
| [architecture.md](design/architecture.md) | Overall architecture: DDD layers, module dependencies, Spring assembly |
| [core-loop.md](design/core-loop.md) | ReAct reasoning loop: iterative Thought → Action → Observation |
| [collaboration.md](design/collaboration.md) | Multi-agent orchestration: routing / conversational / delegate |
| [memory-model.md](design/memory-model.md) | Layered memory: five-layer model, dynamic paging, retrieval |
| [storage-multitenancy.md](design/storage-multitenancy.md) | Storage & multi-tenancy: file/db backends, AgentScope isolation |
| [security.md](design/security.md) | Security model: tool sandbox, approval, auth, injection defense |
| [observability.md](design/observability.md) | Observability & resilience: metrics, run logs, retry/degradation |

## 📑 Quick Reference (reference/)

| Doc | Content |
| --- | --- |
| [rest-api.md](reference/rest-api.md) | REST API overview (paths / params / responses) |
| [websocket.md](reference/websocket.md) | WebSocket event protocol (requests / event stream) |
| [config-full.md](reference/config-full.md) | Full configuration reference table |
| [shell-commands.md](reference/shell-commands.md) | Shell slash-command quick reference |

---

## Documentation conventions

- Primary language: Chinese; this English mirror is a translation.
- All config keys, commands, and API paths reflect the **current code**. Please open an issue if you spot a discrepancy.
- Contributions welcome. Follow [CONTRIBUTING](https://github.com/mwb1219/mwb-ai-claw/blob/master/CONTRIBUTING.md).
