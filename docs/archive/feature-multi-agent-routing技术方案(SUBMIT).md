# 多 Agent 专家路由技术方案

> 迭代目标：支持多 Agent 协作的第一种方式 —— 专家路由（Supervisor / Router）
> 文档编号：feature-multi-agent-routing

## 1. 背景与目标

### 1.1 背景

当前项目仅支持单一默认 Agent：所有用户请求都由同一个 Agent（同一份 system prompt + 同一套工具集）处理。当用户意图差异较大时（如"写代码" vs "查资料" vs "写文档"），单一 Agent 难以在所有场景下都给出高质量结果。

### 1.2 目标

引入**多 Agent 专家路由**能力，实现：

- 支持配置多个职责不同的专家 Agent（各自拥有独立的 system prompt 与工具集）
- 用户消息到达后，由**路由层**自动识别意图并分发给最合适的专家 Agent
- 保留显式指定 Agent 的能力（向后兼容现有 `agentId` 参数）
- 对话入口（REST / SSE / WebSocket / Shell）无需改造，路由透明生效

### 1.3 非目标（本期不做）

- 多 Agent 对话式协作（Debate）、流水线、层级委派（留待后续迭代）
- Agent 动态注册/热更新
- 跨 Agent 共享上下文/记忆

## 2. 现状分析

### 2.1 现有调用链

```
adapter.AgentController / AgentWebSocketHandler / AgentShell
        │
        ▼
app.ChatCmdExe.execute(cmd, callback, streamCallback)
        │  1. agentGateway.getAgent(cmd.getAgentId())   ← 单 Agent，agentId 为空取默认
        │  2. getOrCreateSession(sessionId, agent)
        │  3. reActLoopService.run/streamRun(session, agent, ...)
        │  4. memoryGateway.saveSession(session)
        ▼
domain.ReActLoopService（核心推理循环）
```

### 2.2 关键现状

| 组件 | 现状 |
|------|------|
| `AgentProperties` | 单 Agent 配置（`agent` 前缀，无列表） |
| `AgentGateway.getAgent(agentId)` | 仅返回唯一默认 Agent，忽略 agentId 差异 |
| `Agent` 实体 | 含 agentId/name/systemPrompt/agentInstructions/modelConfig/toolNames/maxSteps |
| `ChatCmd` | 含 sessionId/agentId/message，agentId 为空则默认 |
| `ChatCmdExe` | 对话编排，直接调用 `getAgent` 后执行 ReAct |
| `Session` | 含 agentId 字段（创建时绑定） |

### 2.3 需要解决的核心问题

1. **多 Agent 定义**：从单 Agent 配置扩展为多 Agent 列表配置。
2. **路由决策**：如何根据用户消息选择目标 Agent。
3. **会话与 Agent 绑定**：同一会话历史可能由不同 Agent 处理，需要明确绑定策略。
4. **向后兼容**：现有 `agentId` 参数、`getAgent(null)` 默认行为不能破坏。

## 3. 总体方案

### 3.1 核心思路

引入「**Router 路由层** + **多 Agent 注册表**」：

```
用户消息 ChatCmd
        │
        ▼
┌─────────────────────────────┐
│   Router（路由层，新增）      │
│  route(message) → agentId    │
└─────────────┬───────────────┘
              │ 目标 agentId
              ▼
┌─────────────────────────────┐
│   AgentRegistry（注册表，扩展）│
│  getAgent(agentId)           │
└─────────────┬───────────────┘
              ▼
      ReActLoopService 执行
```

- **Router**：负责"意图识别 → agentId"的决策，本期提供「规则路由」实现，预留「LLM 路由」扩展点。
- **AgentRegistry**：即扩展后的 `AgentGateway`，管理多个 Agent 定义，按 agentId 返回。

### 3.2 路由策略选型

| 策略 | 说明 | 优点 | 缺点 | 本期 |
|------|------|------|------|------|
| 规则路由 | 关键词/正则匹配 Agent 的 `keywords` | 零成本、确定性、易调试 | 覆盖有限、需维护关键词 | ✅ 默认实现 |
| LLM 路由 | 用 LLM 判断意图返回 agentId | 语义理解强、覆盖广 | 多一次 LLM 调用、有延迟 | 预留接口，后续实现 |

本期采用**规则路由为默认、LLM 路由为预留扩展点**的设计，通过 `AgentRouter` 接口统一抽象，未来无缝切换。

## 4. 详细设计

### 4.1 配置模型（infrastructure 层）

扩展 `AgentProperties`，新增多 Agent 列表配置，同时保留原有单 Agent 配置作为默认 Agent：

```java
@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    // ... 现有单 Agent 字段保持不变（作为默认 Agent 兜底）

    /** 多 Agent 定义列表（专家 Agent） */
    private List<AgentConfig> agents = new ArrayList<>();

    @Data
    public static class AgentConfig {
        private String agentId;          // 唯一标识
        private String name;             // 展示名
        private String description;      // 能力描述（供 LLM 路由使用）
        private List<String> keywords;   // 规则路由关键词
        private String systemPrompt;     // 系统提示词
        private List<String> tools;      // 工具集
        private Integer maxSteps;        // 可选，覆盖默认 maxSteps
    }
}
```

### 4.2 领域模型扩展（domain 层）

#### 4.2.1 Agent 实体增加路由元数据

```java
@Data
public class Agent {
    private String agentId;
    private String name;
    private String systemPrompt;
    private String agentInstructions;
    private ModelConfig modelConfig;
    private List<String> toolNames;
    private int maxSteps = 8;

    // ==== 新增：路由元数据 ====
    private String description;          // 能力描述
    private List<String> keywords;       // 规则路由关键词
}
```

#### 4.2.2 AgentGateway 扩展（依赖倒置）

```java
public interface AgentGateway {
    /** 获取指定 Agent，agentId 为空时返回默认 Agent */
    Agent getAgent(String agentId);

    /** 列出所有 Agent（供路由使用） */
    List<Agent> listAgents();
}
```

#### 4.2.3 新增路由接口 AgentRouter

```java
package com.mwb.ai.claw.domain.core;

/**
 * Agent 路由接口：根据用户消息决定由哪个 Agent 处理。
 * 依赖倒置，规则路由/LLM 路由均实现此接口。
 */
public interface AgentRouter {
    /**
     * 路由决策
     * @param message 用户消息
     * @return 目标 agentId；无法判断时返回 null（由调用方回退到默认 Agent）
     */
    String route(String message);
}
```

### 4.3 基础设施实现（infrastructure 层）

#### 4.3.1 AgentGatewayImpl 改造

- 从 `AgentProperties.agents` 读取多 Agent 配置，构建 `List<Agent>`
- `getAgent(agentId)`：在列表中按 agentId 查找；未命中或 agentId 为空时返回默认 Agent
- `listAgents()`：返回全部 Agent
- 默认 Agent 由现有单 Agent 配置（`agent.*`）构建，保证向后兼容

```java
@Component
public class AgentGatewayImpl implements AgentGateway {
    @Resource private AgentProperties agentProperties;
    @Resource private LongTermMemoryGateway longTermMemoryGateway;

    @Override
    public Agent getAgent(String agentId) {
        // 1. 显式指定且命中 → 返回该 Agent
        // 2. 否则返回默认 Agent（agent.* 配置构建）
    }

    @Override
    public List<Agent> listAgents() {
        // 默认 Agent + 所有专家 Agent
    }
}
```

#### 4.3.2 规则路由实现 RuleBasedAgentRouter

```java
@Component
public class RuleBasedAgentRouter implements AgentRouter {
    @Resource private AgentGateway agentGateway;

    @Override
    public String route(String message) {
        // 遍历 listAgents()，若 message 命中某 Agent 的 keywords → 返回其 agentId
        // 全部未命中 → 返回 null（回退默认 Agent）
    }
}
```

> 预留 `LlmBasedAgentRouter`（后续实现）：调用 `LlmGateway`，将各 Agent 的 description 作为候选，让 LLM 返回最匹配的 agentId。

### 4.4 应用层编排改造（app 层）

#### 4.4.1 ChatCmdExe 改造

在现有对话编排前插入路由步骤：

```java
@Component
public class ChatCmdExe {
    @Resource private AgentGateway agentGateway;
    @Resource private AgentRouter agentRouter;   // 新增
    @Resource private MemoryGateway memoryGateway;
    @Resource private ReActLoopService reActLoopService;

    public SingleResponse<ChatResponseDTO> execute(ChatCmd cmd, ...) {
        // 1. 解析目标 Agent：
        //    - 若 cmd.agentId 显式指定 → 直接用
        //    - 否则调用 agentRouter.route(cmd.message) 路由
        //    - 路由结果为 null → 回退默认 Agent
        Agent agent = resolveAgent(cmd);

        // 2. getOrCreateSession（会话与 agent 绑定，见 4.5）
        // 3. ReAct 循环（复用现有逻辑）
        // 4. 持久化会话
        // 5. 响应中携带实际使用的 agentId（供前端展示）
    }
}
```

`ChatResponseDTO` 增加 `agentId` 字段，让调用方知道实际由哪个 Agent 处理。

### 4.5 会话与 Agent 绑定策略

现有 `Session.agentId` 在创建会话时绑定。多 Agent 场景下需要明确：

| 策略 | 说明 | 选择 |
|------|------|------|
| 会话绑定 Agent | 会话创建后 agentId 固定，路由结果与会话 agent 不一致时报错或提示 | 复杂 |
| **消息级路由** | 每轮对话重新路由，会话可被不同 Agent 处理 | ✅ 本期 |
| 强制新建 | 路由结果变化时自动新建会话 | 备选 |

**本期采用「消息级路由」**：路由仅影响"本轮消息由谁处理"，会话历史仍连续保留。由于不同 Agent 的 system prompt 不同，历史消息中会混入不同人设的回复，这是已知取舍，MVP 阶段可接受（后续迭代再引入会话-Agent 严格绑定）。

> 说明：`Session.agentId` 保留为「会话创建时的初始 Agent」，仅作元数据记录，不再作为路由约束。

### 4.6 核心数据流

```
用户消息 "帮我修复登录 bug"
        │
        ▼
ChatCmdExe.execute(cmd)
        │  cmd.agentId == null
        ▼
agentRouter.route("帮我修复登录 bug")
        │  RuleBasedAgentRouter 命中 "bug" → 返回 "coder"
        ▼
agentGateway.getAgent("coder")
        │  返回 coder 专家 Agent（system prompt + tools）
        ▼
getOrCreateSession(sessionId, coderAgent)
        ▼
reActLoopService.run/streamRun(session, coderAgent, ...)
        ▼
返回 ChatResponseDTO(agentId="coder", reply=...)
```

## 5. 模块改动清单

| 层 | 文件 | 改动 |
|----|------|------|
| domain/core | `Agent.java` | 新增 description、keywords 字段 |
| domain/core | `AgentGateway.java` | 新增 `listAgents()` 方法 |
| domain/core | `AgentRouter.java` | **新增**：路由接口 |
| infrastructure/config | `AgentProperties.java` | 新增 `agents` 列表 + `AgentConfig` 内部类 |
| infrastructure/core | `AgentGatewayImpl.java` | 支持多 Agent 加载 + `listAgents()` |
| infrastructure/core | `RuleBasedAgentRouter.java` | **新增**：规则路由实现 |
| app/executor | `ChatCmdExe.java` | 插入路由步骤 + `resolveAgent()` |
| client/dto | `ChatResponseDTO.java` | 新增 `agentId` 字段 |

## 6. 配置示例

```yaml
agent:
  # 默认 Agent（兜底，兼容现有配置）
  agent-id: general
  name: 通用助手
  system-prompt: 你是通用智能助手...
  model: deepseek-v4-pro
  base-url: https://api.deepseek.com
  api-key: "sk-xxx"
  tools: [echo, http, read_memory, write_memory]

  # 专家 Agent 列表（新增）
  agents:
    - agent-id: coder
      name: 编码专家
      description: 擅长编写代码、调试 bug、代码审查、技术实现
      keywords: [代码, bug, 实现, 开发, 调试, 编译, 报错, 函数, 类, 接口]
      system-prompt: 你是资深软件工程师，擅长编码与问题排查...
      tools: [file, shell, http, read_memory, write_memory]
      max-steps: 10

    - agent-id: researcher
      name: 信息检索专家
      description: 擅长信息检索、资料查询、知识整理
      keywords: [搜索, 查询, 资料, 调研, 对比, 最新, 有哪些]
      system-prompt: 你是信息检索专家，擅长高效检索与归纳...
      tools: [http, read_memory]
      max-steps: 5
```

## 7. 实施步骤

1. **domain 层**：`Agent` 增加 description/keywords；`AgentGateway` 增加 `listAgents()`；新增 `AgentRouter` 接口
2. **client 层**：`ChatResponseDTO` 增加 agentId 字段
3. **infrastructure 层**：`AgentProperties` 增加多 Agent 配置；`AgentGatewayImpl` 支持多 Agent；新增 `RuleBasedAgentRouter`
4. **app 层**：`ChatCmdExe` 插入路由逻辑 + `resolveAgent()`
5. 编译验证 + 补充多 Agent 配置示例
6. 编写路由单元测试（规则命中/未命中/显式指定）

## 8. 测试计划

| 用例 | 预期 |
|------|------|
| 消息命中关键词 | 路由到对应专家 Agent |
| 消息未命中任何关键词 | 回退默认 Agent |
| cmd.agentId 显式指定 | 跳过路由，直接用指定 Agent |
| 路由结果对应 Agent 不存在 | 回退默认 Agent |
| listAgents 返回 | 包含默认 Agent + 全部专家 Agent |
| 响应 agentId | 返回实际处理 Agent 的 id |

## 9. 风险与应对

| 风险 | 应对 |
|------|------|
| 关键词路由覆盖不足 | 预留 LLM 路由扩展点，后续替换/混合 |
| 多 Agent 混用同一会话导致上下文割裂 | 本期接受，后续引入会话-Agent 绑定 |
| 配置膨胀、易出错 | 提供默认值 + 配置校验（agentId 唯一性） |
| 路由判断增加一次处理 | 规则路由零成本；LLM 路由可缓存结果 |

## 10. 后续演进（预留）

- LLM 路由：`LlmBasedAgentRouter` 实现 `AgentRouter`
- 路由结果缓存与可观测（日志记录每次路由决策）
- 会话-Agent 严格绑定策略
- 向流水线、对话式协作演进
