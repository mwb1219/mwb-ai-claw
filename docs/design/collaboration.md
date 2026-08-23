---
title: 多 Agent 编排
parent: 设计概要
nav_order: 3
---

# 多 Agent 编排

> 面向想理解原理的读者：单 Agent 之外，如何组织多专家协作完成任务。

## 1. 编排抽象（SPI）

多 Agent 编排通过可插拔的 `AgentOrchestrator` SPI 实现，编排方式与主链路解耦。新增一种编排方式无需改动主链路，只需：

1. 实现 `AgentOrchestrator` 接口并注册为 Spring Bean；
2. 在 `orchestrations.json` 新增一条编排定义（`type` 指向该类型）。

### 1.1 AgentOrchestrator 接口

定义于 domain 层 SPI 包（`com.mwb.ai.claw.domain.collaboration.spi.AgentOrchestrator`）：

| 方法 | 说明 |
| --- | --- |
| `String type()` | 编排类型标识（全局唯一，与 `OrchestrationDefinition.type` 匹配） |
| `void validate(OrchestrationDefinition)` | 启动期配置校验（默认空实现），配置不合法抛异常 fail-fast |
| `CollaborationResult orchestrate(OrchestrationContext)` | 执行一次协作编排，返回最终回复 / 主导 Agent / 轨迹 |

编排输入由 `OrchestrationContext` 承载（scope / message / sessionId / explicitAgentId / definition / agentGateway / executionUnit / callback / streamCallback）。编排器通过 `AgentGateway` 取 Agent，通过 `ExecutionUnit` 执行会话与 ReAct、加锁、落盘产物；`config` 为宽松 JSON，由各插件自行解释为类型化对象（如 `ConversationDefinition` / `DelegateDefinition`）。

### 1.2 OrchestratorRegistry

启动期自动收集所有 `AgentOrchestrator` Bean，按 `type()` 建索引：

- `resolve(definition)`：按 `type` 取插件并执行 `validate`，类型未注册或校验失败抛异常；
- 同一 `type` 重复注册，启动即抛异常（`编排类型重复注册: xxx`）。

### 1.3 编排选择

| 优先级 | 途径 | 说明 |
| --- | --- | --- |
| 1 | 显式指定 | `ChatCmd.orchestrationId` / 协作工具 `invoke_*` 引用编排 id |
| 2 | 默认编排 | `agent.orchestration` 配置（引用 orchestrations.json 中的 id，默认 `routing`） |

> 多 Agent 协作编排（conversational / delegate）通常不由消息前置路由触发，而是主 Agent 在 ReAct 循环中通过 `invoke_*` 全局工具自主发起。

## 2. 三种内置编排

| 类型 | 说明 | 适用 |
| --- | --- | --- |
| `routing` | 单专家独立处理（意图路由选 Agent） | 默认兜底 |
| `conversational` | 多方专家多轮讨论 + 收敛（共识/主持/择优） | 选型、方案对比 |
| `delegate` | 主 Agent 规划 Todo → 委托子 Agent 并行/递归执行 | 复杂多步骤任务 |

### 2.1 routing（专家路由）

迁入原单 Agent 主链路逻辑，行为不变：

```text
显式 agentId? ──是──> 直接使用该 Agent
     │否
   意图路由（AgentRouter）命中? ──是──> 使用路由选中的 Agent
     │否
   回退默认 Agent
```

执行流程：按 sessionId 会话粒度加锁（同会话「取会话 → 追加消息 → ReAct → 保存 → 记忆提炼」串行化）→ 追加用户消息（可含多模态片段）→ 执行 ReAct → 回填推理轨迹 → 持久化会话 → 分层记忆提炼（失败仅告警，不阻塞响应）。

### 2.2 conversational（对话式讨论）

多个专家 Agent 围绕同一任务多轮讨论后收敛为最终结论。参与者通过临时会话执行（上下文隔离、不入库）。

```text
首轮（并行）：各参与者独立给出专业观点（置信度 0-1）
    │
讨论轮（串行，r = 2..rounds）：参与者看到其他专家最近 visibleHistory 轮发言后回应（同意/质疑/补充）
    │   （convergence=consensus 且支持数 >= minConsensus 时提前收敛）
    ▼
收敛：consensus / moderator / best 产出最终结论
```

- **首轮并行**：参与者通过固定线程池并行发言（首轮无流式输出，避免多线程交错）；
- **讨论轮串行**：仅注入其他专家的历史发言（不含自己的），受 `visibleHistory` 截断控制上下文占用；
- **收敛策略**：
  - `consensus`：统计含共识信号词（同意/赞同/支持/一致…）的发言，取支持数最多者；无共识回退 moderator；
  - `best`：解析「置信度: 0.x」标注，取置信度最高者；均未标注回退 moderator；
  - `moderator`（默认）：决策主持 Agent 读取全部讨论记录，综合各方观点输出明确可执行的最终结论。

### 2.3 delegate（任务拆解委派）

主 Agent（规划者）将任务拆解为 Todo 列表，委托子 Agent 执行；子 Agent 执行时可再规划子 Todo 并委托下一级（递归，受 `maxDepth` / `maxTodos` 限制），每层规划者汇总子结果，最终由根规划者输出整体结论。

```text
规划（Plan）：规划 Agent 将任务拆解为 Todo JSON（todoId/title/description/agentId/dependsOn）
    │   输出非 JSON 重试一次，仍失败降级直执行
    ▼
审批门禁（可选）：approvalGate 命中层暂停，等待 approve/reject（超时 approvalTimeoutMs 降级直执行）
    ▼
委派（Execute）：Kahn 拓扑分层为 Wave（无依赖并行，并发度 concurrency）
    │   子 Todo：非叶子层递归再规划 / 指定 orchestrationId 嵌套编排 / 叶子层直执行
    │   （replanRounds > 0：每完成一个 Wave，规划者 re-plan 剩余 Todo）
    ▼
汇总（Summarize）：规划者收集全部子结果，resultPass 注入（text 拼 prompt / file 落盘传路径）
    │   子结果数 > topK 时按与父任务相关性压缩 top-k 注入
    ▼
最终答复（根节点）
```

关键机制：

- **拓扑排序**：`dependsOn` 声明依赖，Kahn 分层出可并行的 Wave；存在依赖环时回退按声明顺序串行；
- **递归委托**：`maxDepth` 控制递归深度，`maxTodos` 限制单层 Todo 数量（超出截断并告警）；规划者自认任务简单（单 Todo 且为自己）时直执行，避免无谓递归；
- **动态规划**（`replanRounds` > 0）：每完成一个 Wave，规划者结合已得结果 re-plan 剩余 Todo（支持完整替换或 `adjust` 增量调整 keep/drop/modify）；
- **防环**：同线程维护嵌套调用链，A→B→A 循环引用立即抛异常终止；
- **记忆沉淀**：叶子 Todo 结论以 `delegate-todo:{path}` 为 key 沉淀 FACT 记忆（重要度 1.0，失败仅告警）；
- **产物落盘**：规划 / 结果按层路径落盘到 `{workdir}/{namespace}/{sessionId}/{时间戳}` 隔离目录（多租户产物互不可见）。

## 3. 协作工具（自主发起）

- [ ] `invoke_discussion` / `invoke_delegate` 为全局工具（global=true），无需在配置中声明，由主 Agent 在 ReAct 中根据任务性质自主决定发起
- [ ] 对应关系：`invoke_discussion` → `team-discussion`（conversational）、`invoke_delegate` → `todo-delegate`（delegate）
- [ ] 嵌套组合与防环：delegate 的 Todo 可指定 `orchestrationId` 调起其他编排（如 conversational），嵌套调用链检测循环引用
- [ ] 审批门禁（`approvalGate`）、动态再规划（`replanRounds`）

## 4. 配置

- [ ] `orchestrations.json` 定义编排：完整配置示例与逐字段说明见 [guide/agents-config.md](../guide/agents-config.md)
- [ ] 加载优先级：运行目录（user.dir）→ 用户配置目录（`$MWB_AI_CLAW_HOME/config/`）→ classpath 内置默认；命中即用、不再读取低优先级来源
- [ ] 启动校验（fail-fast）：`id` 唯一、`type` 已注册、引用的 `agentId` 存在、插件级配置合法

---

相关：[Agent 与编排配置](../guide/agents-config.md)
