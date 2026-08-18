# Agent 配置与编排分离 · 插件化编排 · 意图驱动选择技术方案

> 迭代目标：彻底重构多 Agent 协作机制 —— Agent 配置与编排解耦、编排插件化、按用户意图动态选择编排
> 文档编号：feature-config-orchestration-separation
> 关联文档：`feature-multi-agent-routing技术方案(SUBMIT).md`（路由基线）、`feature-agent-collaboration-pipeline-conversational技术方案(Deprecated).md`（协作模式设计基线）

> **实施状态（2026-08-18）：已实施 ✅**
> - 已完成：配置与编排分离（agents.json / orchestrations.json）、编排插件化（AgentOrchestrator SPI + OrchestratorRegistry）、意图驱动选择（RuleBasedOrchestrationSelector + 显式指定 + 默认兜底）、Routing / Pipeline / Conversational 内置编排、旧配置彻底废弃
> - 已验证：意图选择命中 pipeline / conversational、默认回退 routing、显式指定编排、流水线 analyze→implement→review 全链路产出、对话式 team-discussion 首轮并行观点 → 讨论轮串行回应 → moderator 收敛全链路产出

## 1. 背景与目标

### 1.1 背景

当前多 Agent 机制存在三重耦合：

- **Agent 定义与编排模式耦合**：Agent 存放在 `{mode}-agents.json`，文件名绑定模式，同一批专家无法跨模式复用；
- **编排选择与启动参数耦合**：`agent.mode` 启动参数静态决定协作方式，用户请求到达时无法按意图选择合适的编排；
- **编排逻辑与主链路耦合**：路由/协作逻辑硬编码在 `ChatCmdExe`，新增协作方式需改主链路。

### 1.2 目标

1. **配置与编排分离**：Agent 注册表（`agents.json`）独立于编排注册表（`orchestrations.json`），一套 Agent 可被任意编排复用；
2. **编排插件化**：编排引擎为可插拔 SPI（`AgentOrchestrator`），内置 `routing` / `pipeline` / `conversational` 实现，新增编排零主链路改动；
3. **意图驱动选择编排**：运行时根据**用户问题意图**自动选择合适的编排（而非启动参数静态指定）：
   - 用户显式指定编排 → 优先
   - 否则由「编排选择器」根据消息意图匹配编排（关键词 / 语义描述）
   - 未匹配 → 默认 `routing`；
4. **全新配置体系**：不兼容旧 `{mode}-agents.json` 与 `agent.mode`，配置与参数完全重构。

### 1.3 非目标（本期不做）

- 兼容旧 `{mode}-agents.json` / `agent.mode` 配置（已明确放弃，配置全新定义）；
- 编排热加载 / 编排之间嵌套组合（单层编排）；
- 编排定义 DSL（JSON 即可）。

## 2. 现状分析

### 2.1 现状痛点

| 关注点 | 现状 | 问题 |
|--------|------|------|
| Agent 定义 | `{mode}-agents.json`（文件名含模式） | 跨模式无法复用，配置重复 |
| 编排选择 | `agent.mode` 启动参数静态绑定 | 无法按意图动态选择 |
| 编排逻辑 | `ChatCmdExe.resolveAgent + ReAct` 硬编码 | 新增协作方式侵入主链路 |
| 意图路由 | `AgentRouter` 路由到 **Agent** | 路由目标应是「编排」，而非单个 Agent |

### 2.2 可复用资产

| 资产 | 现状 | 复用方式 |
|------|------|----------|
| `AgentConfig`（AgentProperties 内部类） | Agent 定义模型 | 注册表条目模型不变 |
| `AgentGateway.getAgent/listAgents` | 按 id 取 Agent | 编排器取 Agent 入口 |
| `AgentRouter`（Rule/Llm 两实现） | 消息 → Agent 的意图识别 | **泛化**为「消息 → 编排」的选择器（逻辑复用） |
| `ReActLoopService.run/streamRun` | 单 Agent 推理循环 | 所有编排器公共执行单元 |
| `AgentConfigLoader` 的 `${VAR}` 解析 | 占位符解析 | 新加载器复用 |

## 3. 总体方案

### 3.1 核心思路

```
                    用户消息 ChatCmd（可显式携带 orchestrationId）
                                        │
                                        ▼
┌──────────────────────────────────────────────────────────┐
│  OrchestrationSelector（编排选择器 SPI）                   │
│  select(message) → orchestrationId                       │
│  · 显式指定优先  > 意图匹配（关键词/语义） > 默认 routing   │
└──────────────────────────────────────────────────────────┘
                                        │ orchestrationId
                                        ▼
┌──────────────────────────────────────────────────────────┐
│  编排层（插件化）                                          │
│  OrchestratorRegistry（type → AgentOrchestrator）         │
│  ├─ RoutingOrchestrator / PipelineOrchestrator / ...     │
└──────────────────────────────────────────────────────────┘
                                        │
                                        ▼
              ExecutionUnit（公共执行单元：ReActLoopService + 会话 + 记忆）

配置层（数据，与逻辑解耦）：
  agents.json          Agent 注册表（跨编排复用）
  orchestrations.json  编排注册表（type + 意图元数据 + 编排参数）
```

- **配置层**：`agents.json` 回答「有哪些 Agent」；`orchestrations.json` 回答「有哪些编排、各自什么类型/参数、什么意图匹配元数据」；
- **编排层**：`AgentOrchestrator` 插件 SPI，`OrchestratorRegistry` 启动期收集注册；
- **意图层**：`OrchestrationSelector` 在每次请求到达时，根据消息内容选择编排 —— 这是本方案的核心新增（替代旧的静态模式绑定）。

### 3.2 意图 → 编排的选择策略

| 策略 | 说明 | 本期 |
|------|------|------|
| 显式指定 | `ChatCmd.orchestrationId` 直接指定编排 id | ✅ 优先级最高 |
| 规则选择 | 消息命中编排 `keywords`（复用 AgentRouter 关键词匹配思路） | ✅ 默认实现 |
| LLM 选择 | 用 LLM 判断意图返回编排 id（基于编排 `description`） | 预留实现，二期 |

> 与现有 `AgentRouter` 的区别：`AgentRouter` 目标是「选 Agent」，本方案的选择器目标是「选编排」。选择编排之后，编排内部（如 routing 插件）再决定用哪个 Agent。两层决策职责分离。

## 4. 详细设计

### 4.1 配置模型（全新，无兼容）

#### 4.1.1 Agent 注册表（`agents.json`）

```json
{
  "agents": [
    {
      "agentId": "coder",
      "name": "编码专家",
      "description": "擅长编写代码、调试 bug、代码审查与技术实现",
      "systemPrompt": "你是资深软件工程师，擅长编码、调试与问题排查。",
      "tools": ["file", "shell", "http", "read_memory", "write_memory"],
      "maxSteps": 10,
      "model": "${CODER_MODEL:${DEFAULT_MODEL:deepseek-chat}}",
      "apiKey": "${CODER_API_KEY:${DEFAULT_API_KEY:}}"
    },
    { "agentId": "researcher", "...": "..." }
  ]
}
```

- 复用现有 `AgentConfig` 模型，不新增字段；
- 加载优先级：运行目录 `agents.json` > classpath 内置模板；
- **不再有** `{mode}-agents.json` 及任何按模式分文件的 Agent 配置。

#### 4.1.2 编排注册表（`orchestrations.json`）

```json
{
  "orchestrations": [
    {
      "id": "routing",
      "type": "routing",
      "description": "单专家独立处理：适用于单一领域问题（写代码、查资料、答疑等）",
      "keywords": ["代码", "搜索", "查询", "答疑", "修复", "写", "实现"],
      "config": { "fallbackAgentId": "default" }
    },
    {
      "id": "code-review-pipeline",
      "type": "pipeline",
      "description": "完整开发流水线：需求拆解 → 编码实现 → 代码审查",
      "keywords": ["开发", "设计并实现", "完整实现", "从零", "搭建"],
      "config": {
        "stages": [
          { "stageId": "analyze",   "agentId": "architect", "promptTemplate": "…{input}", "pass": "text", "onFailure": "abort" },
          { "stageId": "implement", "agentId": "coder",     "promptTemplate": "…{input}", "pass": "text", "onFailure": "abort" },
          { "stageId": "review",    "agentId": "reviewer",  "promptTemplate": "…{input}", "pass": "text", "onFailure": "continue" }
        ]
      }
    },
    {
      "id": "tech-debate",
      "type": "conversational",
      "description": "多方观点交锋与收敛：适用于技术选型、方案评审、利弊论证",
      "keywords": ["选型", "对比", "评审", "论证", "权衡", "讨论"],
      "config": {
        "conversation": {
          "rounds": 2,
          "moderator": "moderator",
          "participants": ["architect", "coder", "dba"],
          "minConsensus": 2,
          "convergence": "moderator",
          "visibleHistory": 1,
          "thinking": false
        }
      }
    }
  ]
}
```

编排定义数据模型：

```java
@Data
public class OrchestrationDefinition {
    private String id;                  // 编排 id（选择器返回、显式指定引用）
    private String type;                // 编排插件类型：routing | pipeline | conversational
    private String description;         // 能力描述（供 LLM 选择器语义判断）
    private List<String> keywords;      // 意图关键词（供规则选择器匹配）
    private Map<String, Object> config; // 编排参数（插件自行解释，宽松 JSON）
    private List<String> agents;        // 可选：引用的 agentId（启动校验）
}
```

- 新增 `description` / `keywords` 为**意图匹配元数据**，`config` 仍为宽松 Map（插件化核心：注册中心与定义模型不感知具体编排结构）。

### 4.2 编排选择 SPI（domain 层）

```java
package com.mwb.ai.claw.domain.collaboration;

/**
 * 编排选择器 SPI：根据用户消息意图选择编排。
 * 规则实现（关键词匹配）为默认，LLM 实现为预留扩展点。
 */
public interface OrchestrationSelector {

    /**
     * 意图 → 编排 id。
     *
     * @param message     用户消息
     * @param definitions 全部编排定义
     * @return 匹配的编排 id；无法判断返回 null（调用方回退默认 routing）
     */
    String select(String message, List<OrchestrationDefinition> definitions);
}
```

```java
/** 规则选择器：按编排 keywords 命中次数匹配用户意图（命中数最多者胜出，全部未命中返回 null） */
@Component
public class RuleBasedOrchestrationSelector implements OrchestrationSelector {
    @Override
    public String select(String message, List<OrchestrationDefinition> definitions) {
        // 遍历 definitions，按 keywords 命中次数排序，返回命中数最多的编排 id
        // 全部未命中 → null
    }
}

/**
 * LLM 意图选择器（agent.orchestration-selector=llm，已实施 ✅）：
 * 基于各编排 description 做语义匹配——llm 模式下 LLM 语义选择优先（温度 0 + 关闭思考保证确定性，
 * 返回 id / JSON，校验存在于候选），未命中 / 调用失败时回退规则选择器（关键词兜底）；
 * rule 模式（默认）仅规则选择，保持原行为。以 @Primary 标记，作为 ChatCmdExe 注入首选。
 */
@Component
@Primary
public class LlmOrchestrationSelector implements OrchestrationSelector { ... }
```

> 与 `AgentRouter` 的实现思路一致（规则优先、LLM 扩展），选择器可配置（`agent.orchestration-selector: rule|llm`，默认 `rule`；`llm` 模式内置「LLM 优先 + 规则兜底」）。

### 4.3 编排插件 SPI（domain 层）

```java
public interface AgentOrchestrator {

    /** 编排类型标识（routing / pipeline / conversational），全局唯一 */
    String type();

    /** 编排配置校验（默认空实现），启动期执行 */
    default void validate(OrchestrationDefinition definition) {}

    /** 执行一次协作编排 */
    CollaborationResult orchestrate(OrchestrationContext context);
}
```

```java
public class OrchestrationContext {
    private ChatCmd cmd;                    // 用户请求（含显式 orchestrationId）
    private OrchestrationDefinition definition; // 当前编排定义
    private AgentGateway agentGateway;      // Agent 注册表
    private ExecutionUnit executionUnit;    // 公共执行单元
    private ProgressCallback callback;      // 进度回调（可空）
    private LayeredMemoryGateway memory;    // 分层记忆
}

public class CollaborationResult {
    private String reply;
    private String agentId;                 // 主导 Agent
    private String orchestrationId;         // 实际使用的编排 id
    private List<String> traceSteps;        // 兼容现有 traceSteps 格式
}
```

```java
/** 公共执行单元：ReActLoopService + 会话 + 记忆的编排原语 */
public interface ExecutionUnit {
    String runAgent(String prompt, Agent agent, ProgressCallback callback);   // 单 Agent ReAct
    String memoryContext(Session session, Agent agent);                        // 共享记忆背景
    Path writeArtifact(String workdir, String stageId, String content);        // 产物落盘
}
```

### 4.4 编排执行入口改造（app 层）

```java
@Component
public class ChatCmdExe {

    @Resource private OrchestrationConfigLoader orchestrationLoader;
    @Resource private OrchestrationSelector selector;          // 意图选择器
    @Resource private OrchestratorRegistry orchestratorRegistry;
    @Resource private ExecutionUnit executionUnit;

    public SingleResponse<ChatResponseDTO> execute(ChatCmd cmd, ProgressCallback cb,
                                                   LlmStreamCallback streamCb) {
        // 1. 参数校验（沿用）
        // 2. 选择编排：
        //    a. cmd.orchestrationId 显式指定 → 用之
        //    b. selector.select(cmd.message, definitions) 意图匹配
        //    c. 均未命中 → "routing"（默认）
        String orchestrationId = resolveOrchestrationId(cmd);
        OrchestrationDefinition def = orchestrationLoader.get(orchestrationId);
        // 3. 装配 OrchestrationContext
        // 4. result = orchestratorRegistry.resolve(def).orchestrate(ctx)
        // 5. 组装 ChatResponseDTO（sessionId / agentId / reply / traceSteps）
        //    traceSteps 前置一行 [Orchestration] 编排选择: <id>，便于前端呈现意图决策
    }
}
```

- `ChatCmd` 新增可选字段 `orchestrationId`（显式指定，优先级最高）；
- `ChatResponseDTO` 增加 `orchestrationId` 字段（回显实际使用的编排，前端可展示）。

### 4.5 内置编排实现

| 插件 | type | 职责 | 说明 |
|------|------|------|------|
| `RoutingOrchestrator` | `routing` | 单 Agent 独立完成 | 迁入现有 ChatCmdExe 的 resolveAgent + ReAct 逻辑，会话/记忆/流式行为保留 |
| `PipelineOrchestrator` | `pipeline` | 阶段串行接力 | 设计见协作方案 §4.2（阶段独立 Session、text/file 产物、abort/continue 失败策略） |
| `ConversationalOrchestrator` | `conversational` | 多方讨论收敛 | 设计见协作方案 §4.3（首轮并行、讨论轮串行、consensus/moderator/best 收敛） |

### 4.6 配置加载（infrastructure 层）

| 组件 | 职责 |
|------|------|
| `AgentRegistryLoader`（新增，演进自 AgentConfigLoader） | 加载 `agents.json`，`${VAR}` 解析复用；`AgentGatewayImpl` 数据源改为该注册表 |
| `OrchestrationConfigLoader`（新增） | 加载 `orchestrations.json`，解析占位符，按 id 索引；启动期对每个定义执行对应插件的 `validate()` |
| `AgentProperties` | **移除** `mode` 字段；新增 `orchestration`（默认编排 id，兜底，默认 `routing`）与 `orchestrationSelector`（`rule`，默认） |
| `OrchestratorRegistry`（新增） | Spring 收集 `List<AgentOrchestrator>`，按 `type()` 建 Map，重复类型启动报错 |

> 旧 `agent.mode`、`{mode}-agents.json`、`routing-agents.json` 全部废弃移除，不提供任何兼容层。

### 4.7 核心数据流

**场景 A：用户请求触发流水线编排**

```
"请设计并实现一个 todo CLI，并做代码审查"
  → ChatCmdExe
  → selector.select(message, definitions)
      RuleBasedOrchestrationSelector 命中 "code-review-pipeline"（实现/审查）
  → def = orchestrations.json[id=code-review-pipeline, type=pipeline]
  → orchestratorRegistry.resolve("pipeline") → PipelineOrchestrator
  → Stage analyze(architect) → implement(coder) → review(reviewer)
  → CollaborationResult(orchestrationId=code-review-pipeline, traceSteps=[Orchestration 选择 + 各阶段])
  → ChatResponseDTO
```

**场景 B：意图模糊，回退默认**

```
"写一段 Python 快排"
  → 未命中任何编排 keywords → orchestrationId = agent.orchestration（默认 "routing"）
  → RoutingOrchestrator → resolveAgent（AgentRouter）→ coder
```

**场景 C：用户显式指定编排**

```
ChatCmd{ message: "...", orchestrationId: "tech-debate" }
  → 直接使用 tech-debate（conversational），跳过意图选择
```

**新增自定义编排「审批流」仅需三步：**

1. 实现 `AgentOrchestrator`（`type()="approval"`）+ `@Component`；
2. `orchestrations.json` 增加 `{ "id": "my-approval", "type": "approval", "description": "...", "keywords": [...] , "config": {...} }`；
3. 用户消息命中关键词即可被意图选择 —— 主链路、Agent 注册表、注册中心零改动。

## 5. 模块改动清单

| 层 | 文件 | 改动 |
|----|------|------|
| domain/collaboration | `OrchestrationSelector.java` | **新增**：编排选择 SPI |
| domain/collaboration | `AgentOrchestrator.java` | **新增**：编排插件 SPI |
| domain/collaboration | `OrchestrationContext.java` / `OrchestrationDefinition.java` / `CollaborationResult.java` | **新增**：上下文 / 定义 / 结果值对象 |
| domain/collaboration | `ExecutionUnit.java` | **新增**：公共执行单元接口 |
| infrastructure/collaboration/strategy | `RuleBasedOrchestrationSelector.java` | **新增**：规则选择器（默认） |
| infrastructure/collaboration | `OrchestratorRegistry.java` | **新增**：插件注册中心 |
| infrastructure/collaboration/strategy | `RoutingOrchestrator.java` / `PipelineOrchestrator.java` / `ConversationalOrchestrator.java` | **新增**：内置编排插件 |
| infrastructure/collaboration | `ExecutionUnitImpl.java` | **新增**：执行单元实现 |
| infrastructure/config | `AgentRegistryLoader.java` | **新增**：加载 `agents.json`（演进自 AgentConfigLoader） |
| infrastructure/config | `OrchestrationConfigLoader.java` | **新增**：加载 `orchestrations.json` + 启动校验 |
| infrastructure/config | `AgentProperties.java` | **移除** `mode`；新增 `orchestration` / `orchestrationSelector` |
| infrastructure/config | `AgentConfigLoader.java` / `AgentsFile.java` | **删除**（旧机制废弃） |
| infrastructure/core | `AgentGatewayImpl.java` | 数据源改为 AgentRegistryLoader |
| app/executor | `ChatCmdExe.java` | 改造：编排选择 + 分发 |
| client/dto | `ChatCmd.java` | 新增 `orchestrationId` 字段 |
| client/dto | `ChatResponseDTO.java` | 新增 `orchestrationId` 字段 |
| start/resources | `agents.json` / `orchestrations.json` | **新增**：默认模板 |
| start/resources | `routing-agents.json` | **删除**（废弃） |

## 6. 配置示例

### 6.1 application.yml

```yaml
agent:
  name: mwb-ai-claw
  system-prompt: "你是 mwb-ai-claw 智能助手..."
  orchestration: routing          # 兜底编排 id（意图未命中时使用）
  orchestration-selector: rule    # 意图选择器：rule（关键词，默认）| llm（预留）
  model: ${DEFAULT_MODEL:deepseek-chat}
  base-url: ${DEFAULT_BASE_URL:https://api.deepseek.com}
  api-key: ${DEFAULT_API_KEY:}
  tools: [echo, http, file, shell, read_memory, write_memory]
  # 分层记忆等其余配置不变
```

### 6.2 启动方式

```bash
# 默认启动：意图选择编排（routing / pipeline / conversational 等）
java -jar start/target/start-*.jar

# 兜底编排可覆盖（意图未命中时的默认值）
java -jar start/target/start-*.jar --agent.orchestration=code-review-pipeline
```

## 7. 实施步骤

1. **domain 层**：新增 `collaboration` 包（`OrchestrationSelector` / `AgentOrchestrator` / `OrchestrationDefinition` / `OrchestrationContext` / `CollaborationResult` / `ExecutionUnit`）；✅
2. **infrastructure/config**：`AgentProperties` 移除 `mode`、新增 `orchestration` / `orchestrationSelector`；新增 `AgentRegistryLoader` / `OrchestrationConfigLoader`；删除 `AgentConfigLoader` / `AgentsFile`；✅
3. **infrastructure/collaboration**：`OrchestratorRegistry` + `ExecutionUnitImpl`；✅（策略实现归位 `strategy/` 子包：`RuleBasedOrchestrationSelector`）
4. **app 层**：`ChatCmdExe` 改造为「编排选择 + 分发」；✅
5. **infrastructure/collaboration/strategy**：`RoutingOrchestrator`（迁入现有逻辑，行为保留）；✅
6. **infrastructure/collaboration/strategy**：`PipelineOrchestrator`（复用协作方案 §4.2）；✅
7. **infrastructure/collaboration/strategy**：`ConversationalOrchestrator`（复用协作方案 §4.3）+ `ConversationDefinition`（类型化解析 conversation 配置）；✅
8. 新增 `agents.json` / `orchestrations.json` 默认模板，删除 `routing-agents.json`；✅
9. 编译验证 + 全链路测试（意图选择 / 显式指定 / 默认回退）。✅

## 8. 测试计划

| 用例 | 预期 |
|------|------|
| 消息命中 pipeline 关键词 | 选择流水线编排，阶段按序执行 | ✅ |
| 消息命中 conversational 关键词 | 选择对话式编排，多参与者讨论收敛 | ✅ |
| 消息未命中任何编排关键词 | 回退 `agent.orchestration`（默认 routing） | ✅ |
| `ChatCmd.orchestrationId` 显式指定 | 跳过意图选择，直接使用指定编排 | ✅ |
| 显式指定 id 不存在 | 报错并列出可用编排 id | ✅ |
| 编排引用不存在的 agentId | 启动校验报错 | ✅ |
| LLM 意图选择（`orchestration-selector=llm`） | 语义匹配命中编排 description 对应编排（如「Kafka 还是 RabbitMQ」→ team-discussion，无需关键词） | ✅ |
| LLM 未命中 / 调用失败 | 回退规则选择器（关键词兜底），再未命中回退默认编排 | ✅ |
| 编排插件类型重复注册 | 启动抛「编排类型重复注册」 | ✅ |
| 新增自定义编排插件 | 仅需新增实现类 + orchestrations.json 条目，主链路零改动 | ✅ |
| routing 行为回归 | 会话 / 记忆 / 流式与改造前一致 | ✅ |
| 旧配置（`--agent.mode` / `{mode}-agents.json`） | 不再生效（已废弃，无兼容层） | ✅ |

## 9. 风险与应对

| 风险 | 应对 |
|------|------|
| 意图选择器关键词覆盖不足，选错编排 | 关键词可配置 + `description` 供 LLM 选择器扩展；兜底 routing 保证可用 |
| `config` 宽松 Map 配置错误延迟暴露 | 插件 `validate()` 启动期校验（agentId 存在性、必填字段） |
| 迁移 ChatCmdExe 引入回归 | RoutingOrchestrator 严格搬移 + routing 行为回归测试 |
| 意图选择增加一次处理开销 | 规则选择零成本；LLM 选择可缓存（二期） |
| 编排类型重复 / 定义冲突 | 注册中心启动期强校验 |
| 过度抽象 | `ExecutionUnit` 仅 3 个原语，接口方法带默认实现 |

## 10. 后续演进（预留）

- LLM 意图选择器（`agent.orchestration-selector=llm`，基于编排 description 语义匹配）；
- 会话级编排记忆：同一会话内记住上次编排选择，减少重复决策；
- 编排轨迹可视化面板（复用 `/memory` 面板思路）；
- 流水线 DAG 化（阶段并行分支）、对话式异步长会话（讨论板持久化）；
- 编排插件市场 / 约定式注册（`orchestrations/` 目录自动扫描）。
