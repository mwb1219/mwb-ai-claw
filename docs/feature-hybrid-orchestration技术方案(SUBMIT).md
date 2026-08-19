# 混合编排实现梳理（编排工具化）

> 状态：已实施（基于 delegate 编排 `d004444` 的协作工具化改造；改动待提交）
> 范围：编排选择机制、协作工具（invoke_*）、嵌套编排、执行单元原语

## 1. 背景与目标

早期版本采用「消息前置意图路由选编排」：用户消息进入系统后，先经 `OrchestrationSelector`（规则 / LLM）判定该用哪种编排（routing / conversational），再进入对应编排器执行。

该方式的痛点：

- **门外猜**：路由在消息进入前猜测意图，无法感知主 Agent 推理过程中形成的最新任务理解，关键词误判、漏判常见；
- **绕过主 Agent 智能**：协作编排的决策权在路由层而非执行推理的 Agent，主 Agent 无法自主判断"这次任务是否需要多 Agent 协作"；
- **策略鸡肋**：规则选择器靠 keywords 匹配、LLM 选择器额外消耗一次推理，收益低且不稳定。

**改进方向（本方案）**：把多 Agent 协作编排（conversational / delegate）封装为 ReAct 工具（`invoke_discussion` / `invoke_delegate`），由主 Agent 在推理过程中自主决定是否调用、调用哪个。删除消息前置意图路由（`OrchestrationSelector` 及两个实现），编排选择简化为两层：**显式指定 > 默认**。

## 2. 总体架构

```
┌──────────────────────────── app 层 ────────────────────────────┐
│ ChatCmdExe（编排分发器）                                          │
│   选择编排 → 装配 OrchestrationContext → 委托编排插件执行           │
└──────────────────────────────┬─────────────────────────────────┘
                               │ resolve(orchestrationId)
┌──────────────────────────────▼─────────────────────────────────┐
│ domain 层：编排 SPI                                             │
│   AgentOrchestrator（type() / validate() / orchestrate()）      │
│   ExecutionUnit（runSession / runAgent / runOrchestration）     │
└──────────────────────────────┬─────────────────────────────────┘
                               │ type 匹配
┌──────────────────────────────▼─────────────────────────────────┐
│ infrastructure 层：编排插件（OrchestratorRegistry 注册）           │
│   RoutingOrchestrator      type=routing       默认兜底           │
│   ConversationalOrchestrator type=conversational 多方讨论        │
│   TodoDelegateOrchestrator type=delegate      Todo 委托          │
└──────────────────────────────┬─────────────────────────────────┘
                               │ 嵌套调起 / 工具调用
┌──────────────────────────────▼─────────────────────────────────┐
│ 协作工具层（global=true，对所有 Agent 可见）                       │
│   invoke_discussion → team-discussion                           │
│   invoke_delegate → todo-delegate                               │
│   AbstractCollaborationTool：参数 message → runOrchestration    │
└─────────────────────────────────────────────────────────────────┘
```

## 3. 编排选择机制

### 3.1 两层选择

`ChatCmdExe.resolveOrchestrationId`（[ChatCmdExe.java](../mwb-ai-claw-app/src/main/java/com/mwb/ai/claw/agent/executor/ChatCmdExe.java)）：

```
显式指定 ChatCmd.orchestrationId  >  默认编排 agent.orchestration（默认 routing）
```

- 显式指定：客户端调用时明确传 `orchestrationId`（如 `todo-delegate`），直接命中；
- 未指定：回退默认编排（application.yml `agent.orchestration: routing`），走单 Agent 独立处理链路。

### 3.2 协作编排由主 Agent 自主发起

协作编排（conversational / delegate）**不再在消息进入前预选**，而是封装为 2 个协作工具注册到工具系统。任何执行 ReAct 主循环的 Agent（默认链路下是被路由到的专家或 default Agent）都能在推理中看到并调用这些工具——是否协作、选哪种协作，由正在推理的 Agent 自主判断。

主 Agent 的运行时语义：**谁执行 ReAct 主循环、谁在推理中调用 invoke_* 工具，谁就是这一轮的主导 Agent**。default Agent 只是最常见的默认人选（路由未命中时的兜底），不是独立的主控角色。

### 3.3 与旧方案对比

| 维度 | 旧：消息前置意图路由 | 新：编排工具化 |
| --- | --- | --- |
| 决策时机 | 消息进入前（门外猜） | ReAct 推理中（Agent 自主决策） |
| 决策主体 | OrchestrationSelector（规则/LLM） | 执行推理的 Agent |
| 覆盖编排 | routing / conversational | 协作编排全部工具化，routing 保留为默认兜底 |
| 新增协作编排 | 需改选择器逻辑 | 新增工具 + 编排插件即可，零侵入 |
| 选择器组件 | OrchestrationSelector / LlmOrchestrationSelector / RuleBasedOrchestrationSelector | 已删除 |

## 4. 编排类型明细

| type | 插件 | 职责 | 适用场景 |
| --- | --- | --- | --- |
| `routing` | [RoutingOrchestrator](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/collaboration/strategy/RoutingOrchestrator.java) | 单专家独立处理：选 Agent → 主会话 ReAct → 持久化 → 分层记忆提炼 | 默认兜底：单一领域问题（写代码、查资料、答疑、修复） |
| `conversational` | [ConversationalOrchestrator](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/collaboration/strategy/ConversationalOrchestrator.java) | 多方多轮讨论：首轮并行观点 → 讨论轮串行回应 → 收敛（consensus / moderator / best） | 技术选型、方案对比、权衡决策 |
| `delegate` | [TodoDelegateOrchestrator](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/collaboration/strategy/TodoDelegateOrchestrator.java) | 规划 Todo → 委托子 Agent 执行（递归再委托、无依赖并行），逐层汇总；支持审批门禁、replanRounds、Todo 级编排嵌套 | 复杂、多步骤、跨领域任务 |

### 4.1 routing（默认兜底）

`resolveAgent`：显式 agentId 优先 → `AgentRouter.route(message)` 规则路由 → 未命中回退默认 Agent。随后 `getOrCreateSession → runSession（ReAct）→ saveSession → afterSession（分层记忆提炼）`。

routing 是**唯一覆盖「单 Agent 独立处理」的默认执行链路**，也是会话与记忆闭环的承载者，故保留为兜底，与已被删除的「意图路由」是两个概念。

### 4.2 conversational

首轮各参与者**并行**产出观点（`CompletableFuture` + 线程池，并行轮不传流式回调避免终端交错），后续讨论轮**串行**互相回应（`visibleHistory` 控制可见历史），最后按收敛策略产出结论：`consensus`（支持数 ≥ minConsensus 提前收敛）/ `moderator`（仲裁汇总）/ `best`（置信度最高）。

### 4.3 delegate

`规划（Plan）→ 委派（Execute，拓扑排序 + 无依赖并行）→ 汇总（Summarize）`。支持：

- **递归委托**：子 Todo 可再规划子 Todo，受 `maxDepth` / `maxTodos` 限制；
- **并行执行**：无依赖 Todo 并行（`concurrency` 控制并发数）；
- **审批门禁**：`approvalGate=root/all`，REST/WebSocket 审批 API 或 Shell `/pending` `/approve` `/reject`，拒绝或超时降级直执行；
- **动态规划**：`replanRounds` 首波执行后结合结果调整剩余 Todo（keep/drop/modify 协议）；
- **产物落盘**：`{workdir}/{sessionId}/{时间戳}` 隔离落盘 plan.json / result.txt；
- **记忆沉淀**：叶子结论沉淀分层记忆 FACT；
- **Todo 级编排嵌套**：见 §6。

## 5. 协作工具化

### 5.1 工具定义

| 工具 | 编排映射 | 能力描述（供 LLM 判断） |
| --- | --- | --- |
| `invoke_discussion` | `team-discussion` | 多方专家多轮讨论 + 决策主持收敛 |
| `invoke_delegate` | `todo-delegate` | 规划 Todo 拆解委派，子 Agent 分步执行 |

### 5.2 实现要点

[AbstractCollaborationTool](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/builtin/AbstractCollaborationTool.java)：

- 参数仅 `message`（交给协作子任务的任务描述），校验非空；
- `spec.setGlobal(true)`：以全局工具注册，**对所有 Agent 可见，无需在 agents.json / application.yml 声明**（`DefaultContextAssembler.buildTools` 自动收集 `isGlobal()` 工具）；
- 执行时委托 `executionUnit.runOrchestration(message, orchestrationId, callback)`，协作结果 reply 作为工具结果回传给主 Agent；
- 异常统一转 `ToolResult.error`，不中断主 Agent 推理；
- `ProgressCallback` 转发：ReAct 内实时推送协作进度（`[Orchestration] 发起协作编排: ...`）。

### 5.3 调用时序

```
用户消息
  → 默认编排 routing → 专家 Agent（如 coder）ReAct
  → 推理中判定"任务需要拆解委派执行"
  → 调用 invoke_delegate(message="...")
  → AbstractCollaborationTool → ExecutionUnit.runOrchestration
      → OrchestratorRegistry.resolve(todo-delegate) → TodoDelegateOrchestrator.orchestrate
      → 规划 Todo 委派子 Agent 分步执行 → 返回最终产出
  → 工具结果回传，主 Agent 继续推理并给出最终回复
```

## 6. 嵌套编排与防环

### 6.1 Todo 级编排嵌套

delegate 编排的 Todo 可配置 `orchestrationId`（[TodoDefinition](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/collaboration/TodoDefinition.java)），该 Todo 执行时**委托给指定编排**（conversational / delegate 自身），结果回传参与本层汇总：

```
runTodo → todo.orchestrationId 非空
  → executionUnit.runOrchestration(subTask, orchestrationId)
  → 嵌套编排内部自建临时会话与轨迹执行
  → 返回 reply 作为该 Todo 产出
```

### 6.2 复用同一入口

协作工具与 delegate 嵌套共用 `ExecutionUnit.runOrchestration`（[ExecutionUnit](../mwb-ai-claw-domain/src/main/java/com/mwb/ai/claw/domain/collaboration/ExecutionUnit.java)），其实现（[ExecutionUnitImpl](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/collaboration/ExecutionUnitImpl.java)）按编排 id 解析定义 → 注册中心解析插件 → 装配嵌套上下文（复用全局 Agent 注册表 / 执行单元，独立消息与会话）→ 执行。

### 6.3 防环

`TodoDelegateOrchestrator` 维护**同线程嵌套调用链栈**（`ThreadLocal<Deque<String>>`）：每次 `orchestrate` 进入时 push 当前编排 id，嵌套进入任一编排时若其 id 已在栈中（A→B→A 循环引用）立即抛业务异常终止；退出时 pop 严格配对。并行 Wave 工作线程各自持有独立链（ThreadLocal 不跨线程传递）。

## 7. 执行单元原语

| 原语 | 语义 | 使用方 |
| --- | --- | --- |
| `getOrCreateSession` / `saveSession` | 主会话获取/创建与持久化 | routing |
| `runSession` | 主会话 ReAct（带进度/流式回调） | routing |
| `runAgent` | 临时会话一次性执行（不入库，上下文隔离） | conversational 参与者 / delegate 叶子 Todo |
| `writeArtifact` / `writeFile` | 产物落盘（规范文件名） | delegate |
| `runOrchestration(message, id[, callback])` | 按编排 id 嵌套调起编排 | 协作工具 / delegate 嵌套 |

## 8. 配置说明

### 8.1 application.yml

```yaml
agent:
  orchestration: routing   # 默认编排 id（未显式指定时的兜底编排）
```

### 8.2 orchestrations.json

3 条编排定义：`routing` / `team-discussion` / `todo-delegate`。每条含 `id / type / description / keywords / agents / config`，其中 `config` 为宽松 JSON，由对应插件自行解释（插件化核心：注册中心与定义模型不感知具体编排结构）。启动时 fail-fast 校验：id 重复 / type 未注册 / 引用 agentId 不存在即报错；配置文件缺失时内置兜底 `routing`。

### 8.3 agents.json

专家 Agent 注册表：`coder`（编码专家）/ `researcher`（信息检索专家）/ `architect`（架构师）/ `reviewer`（审查专家）/ `moderator`（决策主持，仅对话式收敛角色，不参与路由）。default Agent 由 `agent.*` 单 Agent 配置构建，是专家配置的继承基座与路由兜底。

## 9. 关键代码索引

| 职责 | 位置 |
| --- | --- |
| 编排分发（两层选择） | [ChatCmdExe.java](../mwb-ai-claw-app/src/main/java/com/mwb/ai/claw/agent/executor/ChatCmdExe.java) |
| 编排插件 SPI | [AgentOrchestrator.java](../mwb-ai-claw-domain/src/main/java/com/mwb/ai/claw/domain/collaboration/AgentOrchestrator.java) |
| 编排上下文 / 定义 | [OrchestrationContext.java](../mwb-ai-claw-domain/src/main/java/com/mwb/ai/claw/domain/collaboration/OrchestrationContext.java) / [OrchestrationDefinition.java](../mwb-ai-claw-domain/src/main/java/com/mwb/ai/claw/domain/collaboration/OrchestrationDefinition.java) |
| 执行单元 | [ExecutionUnit.java](../mwb-ai-claw-domain/src/main/java/com/mwb/ai/claw/domain/collaboration/ExecutionUnit.java) / [ExecutionUnitImpl.java](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/collaboration/ExecutionUnitImpl.java) |
| 注册中心 | [OrchestratorRegistry.java](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/collaboration/OrchestratorRegistry.java) |
| 编排插件 | [RoutingOrchestrator.java](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/collaboration/strategy/RoutingOrchestrator.java) / ConversationalOrchestrator / TodoDelegateOrchestrator |
| 协作工具 | [AbstractCollaborationTool.java](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/builtin/AbstractCollaborationTool.java) / InvokeDiscussionTool / InvokeDelegateTool |
| 全局工具收集 | [DefaultContextAssembler.java](../mwb-ai-claw-domain/src/main/java/com/mwb/ai/claw/domain/context/DefaultContextAssembler.java) `buildTools` |
| 编排配置加载 | [OrchestrationConfigLoader.java](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/config/OrchestrationConfigLoader.java) |
| 默认编排配置 | [AgentProperties.java](../mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/config/AgentProperties.java) |

## 10. 测试与验证

- [CollaborationToolTest.java](../mwb-ai-claw-infrastructure/src/test/java/com/mwb/ai/claw/infrastructure/tool/builtin/CollaborationToolTest.java)：6 例，覆盖工具名称/编排映射、global 标志、缺 message 报错、委托转发正确性、进度回调转发、执行异常转错误结果；
- 全量测试 26 例通过（含既有 TodoDelegateOrchestratorTest 15 例、RuleBasedAgentRouterTest 5 例）。

## 11. 后续演进建议

- 协作工具描述与 orchestrations.json 的 description/keywords 可随场景持续优化（LLM 依据描述决定何时调用）；
- 若需新增协作编排方式：实现 `AgentOrchestrator` 插件 + 注册 Spring Bean + orchestrations.json 加定义 + （可选）新增一个 invoke_* 协作工具，零侵入主链路；
- delegate 的 `planMode` 与 Shell 审批门禁同用会同步阻塞，建议 `approvalGate=none` 或 Web 模式。
