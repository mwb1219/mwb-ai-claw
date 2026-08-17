# 多 Agent 协作模式扩展技术方案：流水线（Pipeline）与对话式（Conversational）

> 迭代目标：在现有「专家路由（Routing）」基础上，新增两种 Agent 协作模式 —— 流水线编排与对话式协作
> 文档编号：feature-agent-collaboration-pipeline-conversational
> 关联文档：`feature-multi-agent-routing技术方案.md`（路由模式基线）

## 1. 背景与目标

### 1.1 背景

项目已实现**专家路由模式**（`--agent.mode=routing`）：每轮消息由 Router（规则/LLM）分发给单个专家 Agent 独立完成 ReAct 推理。该模式适用于「单 Agent 即可独立解决」的任务，但对以下场景力不从心：

- **跨专业协作**：如「设计 → 编码 → 审查 → 发布」需要多个角色按顺序接力，任何单一 Agent 都难以高质量完成全链路；
- **观点交锋与收敛**：如「技术选型论证」「方案评审」需要多方从不同视角发言、质疑并收敛出结论，单个 Agent 自问自答缺少真实的视角冲突。

### 1.2 目标

引入**协作编排抽象层**，支持三种协作模式：

| 模式 | 交互形态 | 典型场景 |
|------|----------|----------|
| `routing`（已有） | 每轮路由到单一 Agent | 单专家独立任务 |
| `pipeline`（新增） | 按阶段顺序接力，前阶段输出为后阶段输入 | 设计→编码→审查 流水线 |
| `conversational`（新增） | 多参与者多轮讨论，收敛为最终结论 | 方案评审、技术选型辩论 |

要求：

- 通过 `--agent.mode=pipeline|conversational` 切换，沿用现有 `{mode}-agents.json` 配置机制；
- 对话入口（REST / SSE / WebSocket / Shell）零改造；
- `routing` 模式行为完全不变（向后兼容）；
- 复用已有基础设施：`ReActLoopService`、`AgentGateway`、`ProgressCallback` / `LlmStreamCallback`、分层记忆共享检索（`shared-retrieve`）。

### 1.3 非目标（本期不做）

- Agent 动态注册 / 热更新（沿用懒加载缓存）；
- 跨 Agent 实时上下文同步（对话式协作的讨论板本期为内存态，会话结束即丢弃）；
- 流水线阶段并行执行（本期严格串行，阶段间存在依赖）；
- 对话式协作超 2 轮以上的大规模辩论（本期轮次上限可配，建议 2-4 轮）。

## 2. 现状分析

### 2.1 现有调用链（routing 模式）

```
adapter.AgentController / AgentWebSocketHandler / AgentShell
        │
        ▼
app.ChatCmdExe.execute(cmd, callback, streamCallback)
        │  1. resolveAgent(cmd)：显式 agentId → agentRouter.route(message) → 默认
        │  2. getOrCreateSession(sessionId, agent)
        │  3. session.addUserMessage(cmd.message)
        │  4. reActLoopService.run / streamRun(session, agent, ...)
        │  5. memoryGateway.saveSession(session)
        │  6. layeredMemoryGateway.afterSession(session, agent)
        ▼
ChatResponseDTO(sessionId, agentId, reply, traceSteps)
```

### 2.2 可复用基础设施（本次设计的立足点）

| 组件 | 现状 | 复用方式 |
|------|------|----------|
| `AgentGateway.getAgent(agentId)` / `listAgents()` | 按 id 取 Agent / 列出全部 | 流水线按 stage.agentId 取 Agent；对话式取 participants |
| `ReActLoopService.run/streamRun(session, agent, cb, streamCb)` | 单 Agent 完整推理循环 | 每个阶段 / 每位参与者的执行单元 |
| `AgentConfigLoader` | 按 `agent.mode` 加载 `{mode}-agents.json` | `pipeline-agents.json` / `conversational-agents.json` 直接复用 |
| `AgentsFile` | 顶层 `mode` + `agents` | 增加可选 `pipeline` / `conversation` 编排定义字段 |
| `ProgressCallback` | trace step 进度推送 | 扩展阶段级进度事件 |
| `LlmStreamCallback` | token 级流式回调 | 协作模式首期仅同步（阶段间需传递产物，不适合整链路 token 直推） |
| 分层记忆 `shared-retrieve` | 跨会话检索注入 | 参与者 / 阶段共享同一会话记忆背景 |
| `ChatResponseDTO.traceSteps` | 执行轨迹列表 | 扩展为「协作轨迹」展示各阶段 / 各参与者 |

### 2.3 需要解决的核心问题

1. **编排抽象**：三种模式如何统一到一个入口，避免 `ChatCmdExe` 膨胀；
2. **阶段 / 参与者之间的数据传递**：流水线的产物传递、对话式的讨论板；
3. **会话历史语义**：阶段 / 参与者的消息如何与主会话隔离，避免人设割裂污染历史；
4. **流式与进度**：协作过程如何向前端 / 终端呈现；
5. **收敛与终止**：对话式何时结束、如何收敛为单一答案。

## 3. 总体方案

### 3.1 核心思路：协作编排抽象层

在 `app` 层与 `ReActLoopService` 之间引入**协作编排接口**，把「选模式 → 编排多 Agent → 收敛结果」从 `ChatCmdExe` 中抽离：

```
ChatCmdExe.execute(cmd, cb, streamCb)
        │  委托
        ▼
┌────────────────────────────────────────────┐
│ CollaborationExecutor（模式分发器，新增）    │
│ 按 agentProperties.mode 选择 Orchestrator   │
└───────────┬──────────────┬───────────────┘
            │              │
   ┌────────▼─────┐  ┌─────▼───────────────┐  ┌───────────────────────┐
   │ RoutingOrc-  │  │ PipelineOrchestrator │  │ ConversationalOrch-  │
   │ hestrator    │  │ （流水线，新增）       │  │ estrator（对话式，新增） │
   │（现有逻辑包装）│  │ stages[] 顺序接力      │  │ participants 多轮讨论  │
   └──────────────┘  └─────────────────────┘  └───────────────────────┘
            │              │                        │
            ▼              ▼                        ▼
            └──────── ReActLoopService（复用单 Agent 执行单元）────┘
```

- **`CollaborationExecutor`**（app 层）：门面，按 `agent.mode` 分发到对应 Orchestrator；
- **`AgentCollaborationOrchestrator`**（domain 层）：协作编排接口，三种模式各自实现；
- **`CollaborationResult`**（domain 层）：协作结果值对象（最终回复 + 协作轨迹 + 主导 agentId + 模式信息）。

### 3.2 模式选型对比

| 维度 | routing | pipeline | conversational |
|------|---------|----------|----------------|
| 参与者数量 | 1（每轮） | N（阶段数） | N（参与者数） |
| 执行顺序 | 单 Agent | 严格串行（阶段依赖） | 首轮并行、讨论轮串行 |
| 数据传递 | 无 | 阶段产物（文本/文件） | 讨论板（各轮发言） |
| 终止条件 | 单 Agent 完成 | 全部阶段完成 | 达轮次上限 / 共识达成 |
| 复杂度 | 低 | 中 | 高 |

## 4. 详细设计

### 4.1 协作编排统一抽象（domain 层）

```java
package com.mwb.ai.claw.domain.collaboration;

/**
 * 协作编排接口：不同协作模式（routing / pipeline / conversational）的统一入口。
 */
public interface AgentCollaborationOrchestrator {

    /**
     * 执行一次协作任务。
     *
     * @param cmd            用户请求（message / sessionId / agentId）
     * @param callback       进度回调（协作阶段级事件）
     * @param streamCallback LLM 流式回调（流水线/对话式首期不使用，传入 null）
     */
    CollaborationResult orchestrate(ChatCmd cmd,
                                    ProgressCallback callback,
                                    LlmStreamCallback streamCallback);
}
```

```java
package com.mwb.ai.claw.domain.collaboration;

/** 协作结果：最终回复 + 协作轨迹 + 元信息 */
public class CollaborationResult {
    private String reply;                 // 最终回复（最后一个阶段 / 收敛后的答案）
    private String agentId;               // 主导 Agent（routing=路由结果；pipeline=末阶段；conversational=moderator）
    private String mode;                  // routing | pipeline | conversational
    private List<String> traceSteps;      // 协作轨迹（兼容现有 traceSteps 语义，前端零改造）
    private List<StageTrace> stageTraces; // 结构化阶段/参与者轨迹（供面板与调试）
}
```

> 说明：`CollaborationResult.traceSteps` 保持与现有 `ChatResponseDTO.traceSteps` 一致的字符串格式，`ChatResponseDTO` 无需新增字段即可完整呈现协作过程。

### 4.2 流水线模式（Pipeline）

#### 4.2.1 配置模型（`pipeline-agents.json`）

沿用 `AgentsFile` 结构，新增可选 `pipeline` 编排定义：

```json
{
  "mode": "pipeline",
  "pipeline": {
    "workdir": "pipeline-artifacts",          // 可选：阶段文件产物目录（相对 workspace-dir）
    "stages": [
      {
        "stageId": "analyze",
        "agentId": "architect",
        "promptTemplate": "你是系统架构师。请分析以下需求，输出需求拆解与实现要点，不要写代码：\n\n{input}",
        "pass": "text",                       // text | file：产物传递方式
        "onFailure": "abort"                  // abort | continue：阶段失败策略
      },
      {
        "stageId": "implement",
        "agentId": "coder",
        "promptTemplate": "基于以下需求分析进行编码实现，直接输出完整代码：\n\n{input}",
        "pass": "text",
        "onFailure": "abort"
      },
      {
        "stageId": "review",
        "agentId": "reviewer",
        "promptTemplate": "审查以下实现，指出问题与改进建议：\n\n{input}",
        "pass": "text",
        "onFailure": "continue"
      }
    ]
  },
  "agents": [ "architect / coder / reviewer 的 Agent 定义（复用 AgentConfig）" ]
}
```

字段说明：

| 字段 | 必填 | 说明 |
|------|------|------|
| `stageId` | 是 | 阶段标识（唯一） |
| `agentId` | 是 | 本阶段负责的 Agent（须在 `agents` 中存在） |
| `promptTemplate` | 是 | 阶段提示词模板，`{input}` 为上一阶段产物占位符，支持 `{artifacts}`（文件产物目录） |
| `pass` | 否 | 产物传递方式：`text`（默认，最终回复文本）/ `file`（将回复写入工作目录文件，路径作为下一阶段 `{input}`） |
| `onFailure` | 否 | 阶段失败策略：`abort`（默认，终止整条流水线）/ `continue`（跳过并继续） |

#### 4.2.2 执行引擎（infrastructure 层 `PipelineOrchestrator`）

```
PipelineOrchestrator.orchestrate(cmd, cb, streamCb)
  ├─ 1. input = cmd.message
  ├─ 2. 校验：stage.agentId 在 agentGateway.listAgents() 中存在
  ├─ 3. 遍历 stages：
  │      a. prompt = renderTemplate(stage.promptTemplate, {input: input, artifacts: workdir})
  │      b. stageSession = new Session()  ← 阶段独立会话，不写主会话历史
  │      c. stageSession.addUserMessage(prompt)
  │      d. result = reActLoopService.run(stageSession, agent)   ← 复用单 Agent ReAct
  │      e. 记录 StageTrace(stageId, agentId, reply 摘要)
  │      f. traceSteps.add("[Stage:analyze] 架构师: <回复前 80 字符>…")
  │      g. 失败处理：reply 为空 / 异常 → 按 onFailure 决定 abort | continue
  │      h. input = stageOutput(stage, result)  ← text: result.reply；file: 写入 workdir/<stageId>.md 返回路径
  ├─ 4. reply = 最后成功阶段的回复；agentId = 末阶段 agentId
  ├─ 5. 产物归档：可选调用 write_memory 将阶段结论写入 FACT（跨流水线复用）
  └─ 6. 组装 CollaborationResult
```

关键设计点：

- **阶段独立会话**：每个阶段用全新 `Session`，避免不同人设 Agent 的回复混入同一会话历史导致上下文污染；主会话（用户可见）仅保留最终结果；
- **产物传递**：`text` 模式直接传最终回复；`file` 模式把阶段回复落盘到 `workdir`（`FileTool` 同款安全校验），下一阶段通过 `{input}` 拿到文件路径，可结合 `file` 工具读取；
- **模板渲染**：仅支持 `{input}` 与 `{artifacts}` 两个占位符（简单字符串替换，避免引入模板引擎）；
- **流式**：首期流水线为同步执行（阶段间需完整产物），`streamCallback` 传入 null；通过 `ProgressCallback` 逐阶段推送进度。

### 4.3 对话式模式（Conversational）

#### 4.3.1 配置模型（`conversational-agents.json`）

```json
{
  "mode": "conversational",
  "conversation": {
    "rounds": 3,                          // 讨论最大轮数（1 为首轮观点，之后为讨论轮）
    "moderator": "moderator",             // 收敛 Agent：汇总各方观点产出最终结论
    "participants": ["architect", "coder", "reviewer"],
    "minConsensus": 2,                    // 共识阈值：某观点被 >= 该数量的参与者支持即提前收敛
    "convergence": "moderator",           // 收敛策略：consensus | moderator | best
    "visibleHistory": 1                   // 讨论轮中每位参与者可见的前几轮完整发言（0=仅摘要）
  },
  "agents": [ "architect / coder / reviewer / moderator 的 Agent 定义" ]
}
```

字段说明：

| 字段 | 必填 | 说明 |
|------|------|------|
| `rounds` | 是 | 讨论最大轮数（1-4 建议；首轮产观点，后续轮产讨论） |
| `moderator` | 否 | 收敛 Agent 的 agentId；`convergence=moderator` 时必填 |
| `participants` | 是 | 参与讨论的 Agent id 列表（>= 2） |
| `minConsensus` | 否 | 共识提前终止阈值（默认 2；仅 `convergence=consensus` 生效） |
| `convergence` | 否 | 收敛策略：`consensus`（观点多数一致）/ `moderator`（默认，仲裁汇总）/ `best`（按参与者声明置信度最高） |
| `visibleHistory` | 否 | 讨论轮可见历史轮数（默认 1，控制上下文占用） |

#### 4.3.2 执行引擎（infrastructure 层 `ConversationalOrchestrator`）

```
ConversationalOrchestrator.orchestrate(cmd, cb, streamCb)
  ├─ 1. board = new DiscussionBoard()        ← 内存讨论板：round → participant → 发言
  ├─ 2. 首轮（并行）：CompletableFuture 并发收集各 participant 首轮观点
  │      · prompt = "任务：{input}\n请给出你的专业观点与理由。"
  │      · 每个 participant 独立 Session + ReAct
  │      · board.record(1, participantId, reply)
  ├─ 3. 讨论轮 r = 2..rounds（串行，需看到他人发言）：
  │      for participant in participants:
  │        · 可见发言 = board.visibleHistory(r, participant, visibleHistory)  ← 其他参与者发言
  │        · prompt = "任务：{input}\n以下是其他专家的观点：\n{history}\n请回应：同意/质疑/补充，并给出你的结论（置信度 0-1）。"
  │        · 独立 Session + ReAct
  │        · board.record(r, participantId, reply)
  │      · 提前终止检查：convergence=consensus 且某观点支持数 >= minConsensus → break
  ├─ 4. 收敛：
  │      · consensus：取支持数最多的观点文本
  │      · moderator：moderator 参与者（或专用 moderator Agent）读取全部发言，汇总为最终结论
  │      · best：取置信度最高的参与者发言
  ├─ 5. traceSteps 记录每轮每个参与者的发言摘要
  └─ 6. 组装 CollaborationResult
```

关键设计点：

- **首轮并行、讨论轮串行**：首轮观点彼此独立可并行（`CompletableFuture` + 线程池，限制并发数=participants 数）；讨论轮必须串行（需读取其他参与者的最新发言）；
- **讨论板上下文**：每轮注入的讨论历史按 `visibleHistory` 截断，控制 token 成本；发言过长时注入前截断（复用分层记忆的截断思路）；
- **收敛策略**：`consensus` 适合立场分明的评审；`moderator` 适合需要汇总整合的选题；`best` 适合各参与者给出量化评分的场景；
- **置信度解析**：要求参与者发言末尾携带 `置信度: 0.8` 格式，供 `best` 收敛使用（解析失败按 0 处理，回退 moderator）。

### 4.4 配置加载扩展（infrastructure 层）

- `AgentsFile` 增加两个可选字段：

```java
@Data
public class AgentsFile {
    private String mode;
    private List<AgentProperties.AgentConfig> agents;
    private PipelineDefinition pipeline;       // 新增：流水线编排定义
    private ConversationDefinition conversation; // 新增：对话式编排定义
}
```

- `AgentConfigLoader` **无需改造**：`{mode}-agents.json` 的查找与 `${VAR}` 占位符解析逻辑对 pipeline / conversational 完全复用；
- 校验职责放在各 Orchestrator 构造/启动时：阶段/参与者引用的 `agentId` 必须存在于 `agents` 列表，缺失则启动告警。

### 4.5 app 层编排改造

`ChatCmdExe` 不再直接 `resolveAgent + ReAct`，改为委托 `CollaborationExecutor`：

```java
@Component
public class ChatCmdExe {
    @Resource private CollaborationExecutor collaborationExecutor;  // 新增

    public SingleResponse<ChatResponseDTO> execute(ChatCmd cmd, ProgressCallback cb,
                                                   LlmStreamCallback streamCb) {
        // 1. 参数校验（沿用）
        // 2. 委托协作编排器（内部按 agent.mode 分发）
        CollaborationResult result = collaborationExecutor.execute(cmd, cb, streamCb);
        // 3. 组装响应（sessionId 由内部管理，agentId/reply/traceSteps 来自 result）
        // 4. 会话持久化 + layeredMemoryGateway.afterSession（routing 行为不变）
    }
}
```

```java
@Component
public class CollaborationExecutor {
    private final AgentCollaborationOrchestrator routingOrch;          // RoutingOrchestrator
    private final AgentCollaborationOrchestrator pipelineOrch;         // PipelineOrchestrator
    private final AgentCollaborationOrchestrator conversationalOrch;   // ConversationalOrchestrator

    public CollaborationResult execute(ChatCmd cmd, ProgressCallback cb, LlmStreamCallback streamCb) {
        switch (agentProperties.getMode()) {
            case "pipeline":        return pipelineOrch.orchestrate(cmd, cb, streamCb);
            case "conversational":  return conversationalOrch.orchestrate(cmd, cb, streamCb);
            default:                return routingOrch.orchestrate(cmd, cb, streamCb);  // routing（向后兼容）
        }
    }
}
```

- **`RoutingOrchestrator`**：把现有 `ChatCmdExe` 的 `resolveAgent → getOrCreateSession → ReAct → 持久化 → afterSession` 原样搬迁，行为零变化；
- 会话创建/持久化逻辑抽为 `CollaborationExecutor` 内公共工具（`SessionSupport`），三种模式共用。

### 4.6 会话与记忆设计

| 维度 | 设计 |
|------|------|
| 主会话 | 用户可见的 `Session`：仅保留用户原始输入 + 最终回复（流水线/对话式）或每轮完整对话（routing） |
| 阶段/参与者会话 | 每次执行新建临时 `Session`（不入库），仅作为 `ReActLoopService` 的执行载体 |
| 分层记忆 | 流水线/对话式执行前调用 `layeredMemoryGateway.readContext` 将记忆背景注入各 Agent prompt（复用共享检索）；`afterSession` 在主会话结束时调用，协作结论可沉淀为 FACT |
| trace | `CollaborationResult.traceSteps`：`[Stage:analyze] …` / `[Round:2] coder: …`，复用现有 `ChatResponseDTO.traceSteps` 渲染 |

### 4.7 核心数据流示例

**流水线**：`"请设计并实现一个 todo CLI，并审查代码"`

```
ChatCmdExe → CollaborationExecutor(mode=pipeline)
  → PipelineOrchestrator
  ├─ Stage analyze   architect ：「需求拆解 + 实现要点」→ 产物文本
  ├─ Stage implement coder     ：基于 analyze 产物编码实现 → 产物文本
  ├─ Stage review    reviewer  ：基于 implement 产物审查 → 最终回复
  → CollaborationResult(reply=审查结论, agentId=reviewer, traceSteps=[Stage:analyze/implement/review])
```

**对话式**：`"MySQL 与 PostgreSQL 如何选型？"`

```
ChatCmdExe → CollaborationExecutor(mode=conversational)
  → ConversationalOrchestrator
  ├─ Round1（并行）: architect / coder / dba 各自给出观点
  ├─ Round2（串行）: 每人看到他人观点，补充/质疑
  ├─ 收敛（moderator）: 汇总为最终选型结论
  → CollaborationResult(reply=收敛结论, agentId=moderator, traceSteps=[Round:1..2 + moderator])
```

## 5. 模块改动清单

| 层 | 文件 | 改动 |
|----|------|------|
| domain/collaboration | `AgentCollaborationOrchestrator.java` | **新增**：协作编排接口 |
| domain/collaboration | `CollaborationResult.java` | **新增**：协作结果值对象 |
| domain/collaboration | `PipelineDefinition.java` / `StageDefinition.java` | **新增**：流水线编排定义 |
| domain/collaboration | `ConversationDefinition.java` | **新增**：对话式编排定义 |
| infrastructure/config | `AgentsFile.java` | 增加 `pipeline` / `conversation` 可选字段 |
| infrastructure/collaboration | `RoutingOrchestrator.java` | **新增**：routing 逻辑搬迁（行为不变） |
| infrastructure/collaboration | `PipelineOrchestrator.java` | **新增**：流水线编排实现 |
| infrastructure/collaboration | `ConversationalOrchestrator.java` | **新增**：对话式编排实现（含 `DiscussionBoard` 内部类） |
| infrastructure/collaboration | `CollaborationExecutor.java` | **新增**：app 层模式分发门面（含 `SessionSupport`） |
| app/executor | `ChatCmdExe.java` | 改造：委托 `CollaborationExecutor`，删除内联路由/ReAct 逻辑 |
| start/resources | `pipeline-agents.json` / `conversational-agents.json` | **新增**：内置默认模板（含 `{mode}-agents.json` 说明注释） |

> 说明：adapter / client 层零改动（`ChatResponseDTO` 已含 `agentId`/`traceSteps`）。

## 6. 配置示例

### 6.1 pipeline-agents.json（完整示例）

```json
{
  "mode": "pipeline",
  "pipeline": {
    "stages": [
      {
        "stageId": "analyze",
        "agentId": "architect",
        "promptTemplate": "你是系统架构师。请分析以下需求，输出需求拆解、模块划分与实现要点，不要写代码：\n\n{input}",
        "pass": "text",
        "onFailure": "abort"
      },
      {
        "stageId": "implement",
        "agentId": "coder",
        "promptTemplate": "基于以下需求分析进行编码实现。直接输出完整可运行的代码：\n\n{input}",
        "pass": "text",
        "onFailure": "abort"
      },
      {
        "stageId": "review",
        "agentId": "reviewer",
        "promptTemplate": "请审查以下代码实现，指出潜在问题、改进建议与风险点：\n\n{input}",
        "pass": "text",
        "onFailure": "continue"
      }
    ]
  },
  "agents": [
    {
      "agentId": "architect",
      "name": "架构师",
      "description": "擅长需求分析、系统架构设计",
      "systemPrompt": "你是资深系统架构师，擅长需求拆解与技术选型。",
      "tools": ["read_memory", "write_memory"],
      "maxSteps": 5,
      "model": "${ARCHITECT_MODEL:${DEFAULT_MODEL:deepseek-chat}}"
    },
    {
      "agentId": "coder",
      "name": "编码专家",
      "description": "擅长编写代码与实现",
      "systemPrompt": "你是资深软件工程师，代码规范清晰。",
      "tools": ["file", "shell", "read_memory", "write_memory"],
      "maxSteps": 10,
      "model": "${CODER_MODEL:${DEFAULT_MODEL:deepseek-chat}}"
    },
    {
      "agentId": "reviewer",
      "name": "审查专家",
      "description": "擅长代码审查与质量评估",
      "systemPrompt": "你是严谨的代码审查专家，关注正确性、安全与可维护性。",
      "tools": ["read_memory", "write_memory"],
      "maxSteps": 5,
      "model": "${REVIEWER_MODEL:${DEFAULT_MODEL:deepseek-chat}}"
    }
  ]
}
```

### 6.2 conversational-agents.json（完整示例）

```json
{
  "mode": "conversational",
  "conversation": {
    "rounds": 2,
    "moderator": "moderator",
    "participants": ["architect", "coder", "dba"],
    "minConsensus": 2,
    "convergence": "moderator",
    "visibleHistory": 1
  },
  "agents": [
    {
      "agentId": "architect",
      "name": "架构师",
      "description": "系统架构与技术选型视角",
      "systemPrompt": "你是系统架构师，从架构演进、可维护性角度发表观点。",
      "tools": ["read_memory"],
      "maxSteps": 3
    },
    {
      "agentId": "coder",
      "name": "编码专家",
      "description": "工程实践与开发效率视角",
      "systemPrompt": "你是资深工程师，从工程实践、开发效率角度发表观点。",
      "tools": ["read_memory"],
      "maxSteps": 3
    },
    {
      "agentId": "dba",
      "name": "数据库专家",
      "description": "数据存储与性能视角",
      "systemPrompt": "你是数据库专家，从性能、一致性、运维角度发表观点。",
      "tools": ["read_memory"],
      "maxSteps": 3
    },
    {
      "agentId": "moderator",
      "name": "决策主持",
      "description": "汇总各方观点并给出最终结论",
      "systemPrompt": "你是技术决策主持，综合各方专家观点，给出明确且可执行的最终结论。",
      "tools": ["read_memory", "write_memory"],
      "maxSteps": 5
    }
  ]
}
```

### 6.3 启动方式

```bash
# 流水线模式
java -jar start/target/start-*.jar --agent.mode=pipeline

# 对话式模式
java -jar start/target/start-*.jar --agent.mode=conversational
```

## 7. 实施步骤

1. **domain 层**：新增 `collaboration` 包（`AgentCollaborationOrchestrator` / `CollaborationResult` / `PipelineDefinition` / `StageDefinition` / `ConversationDefinition`）；
2. **infrastructure 层**：`AgentsFile` 增加 `pipeline` / `conversation` 字段；实现 `RoutingOrchestrator`（搬迁现有逻辑，行为零变化）；
3. **app 层**：新增 `CollaborationExecutor`（模式分发 + `SessionSupport`），改造 `ChatCmdExe` 委托；
4. **infrastructure 层**：实现 `PipelineOrchestrator`（阶段串联 + 产物传递 + 失败策略）；
5. **infrastructure 层**：实现 `ConversationalOrchestrator`（并行首轮 + 讨论轮 + 三种收敛策略）；
6. 新增 `pipeline-agents.json` / `conversational-agents.json` 默认模板；
7. 编译验证 + routing 回归（确保原行为不变）；
8. 编写编排单测（见第 8 节）。

## 8. 测试计划

| 用例 | 预期 |
|------|------|
| routing 模式回归 | 与改造前行为完全一致（会话/路由/记忆/流式） |
| pipeline 正常链路 | 阶段按序执行，`{input}` 正确传递，trace 含各阶段 |
| pipeline 阶段失败（abort） | 流水线终止，返回错误信息与已完成阶段轨迹 |
| pipeline 阶段失败（continue） | 跳过失败阶段，用下一阶段继续 |
| pipeline `pass=file` | 阶段回复落盘，下一阶段 `{input}` 为文件路径 |
| pipeline 引用不存在的 agentId | 启动/执行时校验失败并提示 |
| conversational 首轮并行 | 并发收集各参与者观点（线程数受控） |
| conversational 讨论轮 | 讨论板可见发言正确注入，token 受 visibleHistory 约束 |
| conversational 收敛-consensus | 达到 minConsensus 提前终止并输出多数观点 |
| conversational 收敛-moderator | moderator 汇总全部发言输出结论 |
| conversational 收敛-best | 按置信度最高参与者发言输出 |
| 超轮次上限 | 到达 rounds 上限后强制收敛 |
| 入口兼容 | REST / SSE / WebSocket / Shell 无需改动即可使用新模式 |

## 9. 风险与应对

| 风险 | 应对 |
|------|------|
| 流水线阶段上下文隔离导致信息丢失 | `pass=file` 落盘产物 + promptTemplate 显式要求输出结构化信息 |
| 对话式多轮 token 成本高 | `visibleHistory` 截断 + 发言摘要化 + 轮次上限（默认 2-3） |
| 参与者观点雷同（首轮后无新信息） | 讨论轮 prompt 要求「同意/质疑/补充」给出增量信息，避免复读 |
| 收敛结果质量不稳定（moderator 偏科） | 支持 `consensus` / `best` 策略切换；moderator 的 systemPrompt 可独立配置 |
| 编排复杂度上升影响主链路稳定性 | 编排器独立实现 + routing 路径零改动 + 单元测试隔离 |
| 并行首轮偶发限流 | 并发数 = participants 数（通常 <=4），可配置线程池上限 |

## 10. 后续演进（预留）

- 流水线阶段**并行分支**（DAG 编排，非简单串行）；
- 对话式**异步长会话**（讨论板持久化，跨请求续谈）；
- 协作产物沉淀为结构化记忆（阶段结论自动写入 FACT，供后续任务检索复用 —— 分层记忆 Phase 3 已具备 `shared-retrieve` 基础）；
- 编排可视化面板（复用 `/memory` 面板思路，新增协作轨迹查询）；
- 协作模式动态切换（会话级 `mode` 覆盖，而非全局启动参数）。
