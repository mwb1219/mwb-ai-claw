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
- [ ] Independent models: each Agent can configure `model` / `baseUrl` / `apiKey` / `provider` / `temperature` / `maxTokens`; `model` / `baseUrl` / `apiKey` all support `${VAR:default}` placeholders referencing `.env`, e.g. `"baseUrl": "${CODER_BASE_URL:${DEFAULT_BASE_URL:https://api.deepseek.com}}"` (empty inherits default)

## 3. orchestrations.json (Orchestration Registry)

`orchestrations.json` defines the available multi-agent orchestrations (one entry = one collaboration mode). Each entry has `id` / `type` / `description` / `keywords` / `agents` / `config`.

| Field | Required | Description |
| --- | --- | --- |
| `id` | yes | Orchestration id (globally unique), referenced by explicit selection / `invoke_*` tools |
| `type` | yes | Orchestration plugin type: `routing` / `conversational` / `delegate` (must be a registered type) |
| `description` | yes | Capability description (for display and LLM selector semantic judgment) |
| `keywords` | no | Intent keywords (for rule-based selector matching, e.g. "选型", "方案对比") |
| `agents` | no | Referenced agentId list (validated at startup, optional) |
| `config` | per type | Orchestration parameters (loose JSON, interpreted by the corresponding plugin itself) |

> `config` is the core of the plugin mechanism: the registry and the definition model don't know the concrete orchestration structure; each plugin interprets its own `config`.

### 3.1 Complete Configuration Example

The built-in default template (in the `start` / `example-web` module `orchestrations.json`) can be copied directly to the run directory to override:

```json
{
  "orchestrations": [
    {
      "id": "routing",
      "type": "routing",
      "description": "Single-specialist independent handling: suitable for single-domain problems (writing code, researching, Q&A, fixing, etc.). The router selects the most suitable Agent by intent. Used as the default fallback orchestration; multi-agent collaboration is initiated autonomously by the main Agent via the invoke_* tools, with no pre-message intent routing",
      "agents": ["coder", "researcher", "architect", "reviewer"]
    },
    {
      "id": "team-discussion",
      "type": "conversational",
      "description": "Multi-party expert discussion: architect / coder / reviewer discuss the same problem over multiple rounds (first round parallel opinions, discussion rounds respond to each other), then a decision moderator converges to a clear conclusion. Suitable for technology selection, solution comparison and trade-off decisions",
      "keywords": ["选型", "方案对比", "对比", "权衡", "决策", "哪个更好", "如何选择", "优缺点", "讨论", "评估", "怎么选"],
      "agents": ["architect", "coder", "reviewer", "moderator"],
      "config": {
        "conversation": {
          "rounds": 2,
          "moderator": "moderator",
          "participants": ["architect", "coder", "reviewer"],
          "minConsensus": 2,
          "convergence": "moderator",
          "visibleHistory": 1,
          "thinking": false
        }
      }
    },
    {
      "id": "todo-delegate",
      "type": "delegate",
      "description": "The main Agent thinks, plans and breaks the task into a Todo list, delegates execution to sub-agents; sub-agents can recursively delegate again, summarizing layer by layer. Suitable for complex, multi-step, cross-domain tasks",
      "keywords": ["规划并实现", "复杂任务", "任务拆解", "分步骤", "分步完成", "多步骤", "拆解并执行", "团队协作", "分工完成", "整体规划"],
      "agents": ["architect", "coder", "researcher", "reviewer"],
      "config": {
        "delegate": {
          "plannerAgentId": "architect",
          "maxTodos": 8,
          "maxDepth": 2,
          "parallel": true,
          "concurrency": 4,
          "onFailure": "abort",
          "retries": 1,
          "thinking": false,
          "resultPass": "text",
          "approvalGate": "none",
          "approvalTimeoutMs": 0,
          "topK": 3,
          "replanRounds": 0
        }
      }
    }
  ]
}
```

### 3.2 routing type (single-specialist routing)

A single specialist handles the task independently; the router selects one specialist by intent (explicit agentId takes priority). No `config` parameters; `agents` simply lists the specialists eligible for routing. Suitable for single-domain problems and acts as the default fallback orchestration.

### 3.3 conversational type (discussion)

Multiple specialists discuss the same problem over multiple rounds and converge to a conclusion. `config.conversation` parameters:

| Parameter | Default | Description |
| --- | --- | --- |
| `participants` | - (required) | Agent id list participating in the discussion (at least 2) |
| `rounds` | `2` | Number of rounds (1 = first-round opinions only, 2+ = discussion rounds responding to each other; recommended 1-4) |
| `moderator` | - | Converging Agent id (required when `convergence=moderator`, references the agents.json registry) |
| `convergence` | `moderator` | Convergence strategy: `consensus` (majority agreement) / `moderator` (arbitration summary) / `best` (highest confidence) |
| `minConsensus` | `2` | Consensus threshold: a view supported by >= this many participants converges early (only for `consensus`) |
| `visibleHistory` | `1` | Rounds of visible history in discussion rounds (controls context usage) |
| `thinking` | no override | Thinking mode switch (null = keep the Agent's default config) |

### 3.4 delegate type (task breakdown & delegation)

The main Agent plans and breaks the task into a Todo list, delegates to sub-agents for parallel/recursive execution, then summarizes layer by layer. `config.delegate` parameters:

| Parameter | Default | Description |
| --- | --- | --- |
| `plannerAgentId` | `architect` | Root planner Agent id (child planners = the parent todo's agentId) |
| `maxTodos` | `8` | Max todos per layer (truncated with a warning when exceeded) |
| `maxDepth` | `2` | Recursive delegation depth (1 = only the main Agent breaks down one layer; 2 = sub-agents may break down one more layer) |
| `parallel` | `true` | Whether independent todos run in parallel |
| `concurrency` | `4` | Degree of parallelism (parallel Wave worker threads) |
| `onFailure` | `abort` | Todo failure policy: `abort` (abort the whole orchestration) / `skip` (mark as failed and continue, noted in the summary) |
| `retries` | `1` | Retry count for failed todos (empty replies also trigger retries) |
| `thinking` | no override | Thinking mode switch for plan / summarize phases |
| `resultPass` | `text` | Summary result passing: `text` (inlined into the summarize prompt) / `file` (written to disk, path passed) |
| `workdir` | `orchestration-artifacts` | Artifact output directory (used when `resultPass=file`) |
| `approvalGate` | `none` | Human approval gate: `none` (no pause) / `root` (pause only after the root plan) / `all` (pause after each layer's plan) |
| `approvalTimeoutMs` | `0` | Approval wait timeout (ms, 0 = wait indefinitely; on timeout the layer degrades to direct execution) |
| `topK` | `3` | Top-k relevant sub-results injected in the summarize phase (all injected when sub-results <= topK) |
| `replanRounds` | `0` | Dynamic planning (Plan-Do-Reflect) re-plan rounds (0 = disabled; >0 adjusts remaining todos after each Wave using obtained results) |

## 4. Collaboration Tools (initiated autonomously by multiple Agents)

Multi-agent collaboration is usually not triggered by pre-message intent routing; the main Agent initiates it autonomously through global tools within the ReAct loop:

- [ ] `invoke_discussion` → team-discussion orchestration (multi-party discussion and convergence)
- [ ] `invoke_delegate` → todo-delegate orchestration (Todo breakdown and delegation)
- [ ] Globally registered (global=true), no need to declare in config
- [ ] Nested orchestration composition: a delegate todo can specify `orchestrationId` to invoke another orchestration (e.g. conversational), with cycle detection

## 5. Validation & Troubleshooting

- [ ] Startup validation (fail-fast): unique orchestration `id`s, registered `type`s, existing referenced `agentId`s, valid plugin-level config (e.g. conversational `participants >= 2`, delegate `maxTodos >= 1`)
- [ ] Common errors and solutions:
  - `编排 id 重复: xxx` → check whether `id`s are unique in orchestrations.json
  - `编排 'xxx' 引用了未注册的类型 'yyy'` → the type isn't implemented / registered as a Spring Bean
  - `编排 'xxx' 引用了不存在的 Agent: zzz` → the agentId is missing from agents.json
  - `对话式编排 'xxx' 的 participants 至少需要 2 个参与者` → check `conversation.participants`
  - `委托编排 'xxx' 的 onFailure 不合法` → allowed values are `abort` / `skip`

---

See also: [Configuration](configuration.md) ｜ [Multi-Agent Collaboration Design](../design/collaboration.md) ｜ [Skills System](skills.md)
