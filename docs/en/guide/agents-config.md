---
title: Agents & Orchestrations Configuration
parent: User Guide (EN)
nav_order: 7
---

# Agents & Orchestrations Configuration

> For extenders: configure expert Agents (`agents.json`) and collaborative orchestration (`orchestrations.json`).
> Putting a same-named file in the run directory overrides the built-in defaults, no repackaging needed.

## 1. Loading Mechanism

- [ ] Run directory (user.dir) same-named file takes effect if present → install directory `~/.mwb-ai-claw/config/` same-named file → classpath built-in defaults in the jar
- [ ] `${VAR:default}` placeholders reference `.env` variables

## 2. agents.json (Agent Registry)

- [ ] Fields: `agentId` / `name` / `description` / `keywords` / `systemPrompt` / `tools` / `maxSteps` / `maxTokens` / `model` / `baseUrl` / `apiKey` / `temperature` / `provider`
- [ ] Tool binding: default = all registered; explicit `tools` = force bind only the declared ones
- [ ] Independent models: each Agent can configure `model` / `baseUrl` / `apiKey` / `provider` / `temperature` / `maxTokens`

## 3. orchestrations.json (Orchestration Registry)

- [ ] Fields: `id` / `type` / `description` / `keywords` / `agents` / `config`
- [ ] Built-in types: `routing` / `conversational` / `delegate`
- [ ] Orchestration selection: explicit > default (`agent.orchestration`, default routing)

## 4. Collaboration Tools (initiated autonomously by multiple Agents)

- [ ] `invoke_discussion` → team-discussion orchestration (multi-party discussion and convergence)
- [ ] `invoke_delegate` → todo-delegate orchestration (Todo breakdown and delegation)
- [ ] Globally registered (global=true), no need to declare in config

## 5. Validation & Troubleshooting

- [ ] Startup validation: orchestration ids unique, types registered, referenced agentIds exist
- [ ] Common errors and solutions

---

See also: [Configuration](configuration.md) ｜ [Multi-Agent Collaboration Design](../design/collaboration.md) ｜ [Skills System](skills.md)
