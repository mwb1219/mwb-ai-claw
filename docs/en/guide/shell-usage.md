---
title: Shell Mode Usage
parent: User Guide (EN)
nav_order: 4
---

# Shell Mode Usage

> For terminal users: the complete usage of the `mwb-ai-claw` interactive terminal (REPL).
> For a command quick reference, see [reference/shell-commands.md](../reference/shell-commands.md).

## 1. Entering & Exiting

- [ ] Enter: `mwb-ai-claw` (or `java -jar start-*.jar --spring.profiles.active=shell`)
- [ ] Exit: `/exit` / `/quit` / `Ctrl+D`

## 2. Chat Mode

- [ ] Free-text chat; switch between stream / sync (`/mode`)
- [ ] Multimodal image input: `![description](path or URL)` / `@local image`
- [ ] Structured output: `/json <message>`
- [ ] Plan mode: `/plan` (propose a plan first, execute after confirmation)

## 3. Session Management

- [ ] `/session` family: new / list / switch / rename / export / delete
- [ ] `/fork` fork a session, `/clear` reset, `/compact` compress context

## 4. Tools & Execution

- [ ] `!command`: execute Shell locally and hand the result to the Agent for analysis
- [ ] `/mcp` family: view / connect / disconnect MCP Servers
- [ ] `/agent` family: view / attach background agent tasks
- [ ] `/pending` `/approve` `/reject`: approval for high-risk commands

## 5. Memory & Observability

- [ ] `/memory`: hierarchical memory overview (stats / facts / summaries / archive / search)
- [ ] `/metrics`: metrics overview (LLM / tools / ReAct / API / memory)
- [ ] `/runs [date]`: query run usage records
- [ ] `/cost`: token usage statistics

## 6. Startup Arguments (headless / automation)

- [ ] `--prompt "question"` / `-p`: single-turn non-interactive
- [ ] `--resume <sessionId>` / `--mode stream|sync` / `--bg "task"`
- [ ] `--agent.*=...`: override any config

---

See also: [Quick Start](quick-start.md) ｜ [Command Quick Reference](../reference/shell-commands.md) ｜ [Multimodal & Templates](web-usage.md)
