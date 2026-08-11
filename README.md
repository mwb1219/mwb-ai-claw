# mwb-ai-claw

> 基于 COLA 架构（DDD 思想）实现的智能体 Agent 框架，灵感来自 OpenClaw——本地优先、可真正动手干活、开箱即用的个人 AI 助手。

## 一、项目目标

构建一个 Java 版的智能体 Agent 框架，具备以下核心能力：

- **多渠道接入**：Web / REST API / IM 渠道（飞书、钉钉、微信、Telegram 等）
- **ReAct 推理循环**：思考（Thought）→ 行动（Action）→ 观察（Observation）的迭代执行
- **工具调用能力**：文件读写、Shell 执行、HTTP 请求、数据库操作、浏览器控制等
- **MCP 协议支持**：标准化工具接入，兼容生态内任意 MCP 工具
- **记忆系统**：短期会话记忆 + 长期工作区记忆
- **多模型适配**：支持 OpenAI、Anthropic、通义千问、DeepSeek、本地 Ollama 等
- **本地优先**：会话与记忆数据本地化，支持私有化部署

## 二、整体架构

项目延续 COLA 6 模块分层，将 OpenClaw 的「渠道适配 → 控制面 → Agent 运行时 + 工具」三层模型映射到 DDD 分层中：

```
┌─────────────────────────────────────────────────────────────┐
│                        start (启动)                          │
│              Spring Boot Application                        │
└─────────────────────────────────────────────────────────────┘
                           ▲
┌─────────────────────────────────────────────────────────────┐
│                    adapter (适配层)                          │
│   渠道入口：WebController / IMAdapter / CLIAdapter          │
│   职责：协议转换、鉴权、限流、请求转发到 app 层                │
└─────────────────────────────────────────────────────────────┘
                           ▲
┌─────────────────────────────────────────────────────────────┐
│  client (客户端 SDK)              │     app (应用层)        │
│  - AgentServiceI 等接口            │  - AgentServiceImpl    │
│  - ChatCmd / SessionCmd 等 DTO     │  - executor (Cmd/Qry)   │
│  - 事件契约 AgentRepliedEvent      │  - 会话编排、用例编排     │
└───────────────────────────────────┴────────────────────────┘
                           ▲
┌─────────────────────────────────────────────────────────────┐
│                    domain (领域层)                          │
│   聚合：Session / Agent / Message                            │
│   领域服务：ReActLoopService / ToolRouter / MemoryService    │
│   Gateway 接口：LlmGateway / ToolGateway / MemoryGateway    │
│   值对象：ToolSpec / Thought / Action / Observation          │
└─────────────────────────────────────────────────────────────┘
                           ▲
┌─────────────────────────────────────────────────────────────┐
│                infrastructure (基础设施)                     │
│   core 实现：AgentGatewayImpl                                │
│   tool 实现：ToolGatewayImpl + 内置工具 (EchoTool 等)        │
│   memory 实现：MemoryGatewayImpl (内存/文件/DB)              │
│   llm 实现：LlmGatewayImpl (多模型适配)                      │
│   config：AgentConfiguration / AgentProperties              │
└─────────────────────────────────────────────────────────────┘
```

**依赖方向**：`adapter / app / infrastructure` → `client + domain`；`domain` 不依赖任何下层，通过 Gateway 接口实现依赖倒置（DIP）。

## 三、核心领域模型设计

### 3.1 聚合与实体

| 类型 | 名称 | 职责 |
|------|------|------|
| 聚合根 | `Session` | 一次 Agent 对话会话，聚合 Message 列表、上下文状态、所属 Agent |
| 实体 | `Message` | 单条消息（user/assistant/tool/observation 角色） |
| 实体 | `Agent` | Agent 配置实体（模型、system prompt、可用工具集、人设） |
| 值对象 | `ToolSpec` | 工具规格（名称、描述、参数 JSON Schema） |
| 值对象 | `Thought` | LLM 的思考文本 |
| 值对象 | `Action` | LLM 决定调用的工具 + 入参 |
| 值对象 | `Observation` | 工具执行返回结果 |
| 值对象 | `ModelConfig` | 模型配置（provider、modelName、温度等） |

### 3.2 领域服务

- **`ReActLoopService`**：ReAct 推理循环编排，是 Agent 的核心引擎。负责驱动 Thought → Action → Observation 的迭代，直到产出最终回答或达到终止条件（最大步数 / 无需工具）。
- **`ToolRouter`**：根据 LLM 输出的 Action 路由到对应工具执行，聚合工具注册表。
- **`MemoryService`**：管理短期记忆（当前会话消息窗口）与长期记忆（跨会话工作区记忆检索）。
- **`PromptAssembler`**：组装 system prompt、历史消息、工具规格，输出给 LLM。

### 3.3 Gateway 接口（依赖倒置）

```
domain/
├── core/                              # 核心域
│   ├── Session.java                   # 聚合根：会话
│   ├── Message.java                   # 实体：消息
│   ├── MessageRole.java               # 枚举
│   ├── SessionStatus.java             # 枚举
│   ├── Agent.java                     # 实体：Agent 配置
│   ├── ModelConfig.java               # 值对象：模型配置
│   ├── ReActLoopService.java          # 领域服务：ReAct 推理循环
│   ├── ReActResult.java               # 值对象：推理结果
│   └── AgentGateway.java              # 接口：Agent 配置管理
├── tool/                              # 工具域
│   ├── ToolGateway.java               # 接口：工具执行路由
│   ├── ToolExecutor.java              # 接口：工具执行器（扩展点）
│   ├── ToolSpec.java                  # 值对象：工具规格
│   └── ToolResult.java                # 值对象：工具执行结果
├── memory/                            # 记忆域
│   └── MemoryGateway.java             # 接口：记忆持久化
└── llm/                               # LLM 域
    ├── LlmGateway.java                # 接口：LLM 调用
    ├── LlmRequest.java                # 值对象：LLM 请求
    ├── LlmResponse.java               # 值对象：LLM 响应
    ├── LlmMessage.java                # 值对象：LLM 消息
    └── ToolCall.java                  # 值对象：工具调用
```

## 四、模块与目录结构规划

```
mwb-ai-claw
├── mwb-ai-claw-client        # 对外 API 与 DTO
│   └── com.mwb.ai.claw
│       ├── api/              # AgentServiceI, SessionServiceI
│       ├── dto/              # ChatCmd, ChatQry, SessionCmd, AgentConfigCmd
│       │   ├── cmd/          # ChatCmd, CreateSessionCmd
│       │   ├── qry/          # SessionListQry, MessageHistoryQry
│       │   ├── data/         # ChatResponseDTO, SessionDTO, AgentDTO
│       │   └── event/        # AgentRepliedEvent, ToolInvokedEvent
├── mwb-ai-claw-adapter       # 渠道适配
│   └── com.mwb.ai.claw
│       ├── web/              # AgentController (REST/SSE 流式)
│       ├── im/               # FeishuAdapter, DingTalkAdapter, TelegramAdapter
│       └── cli/              # CliAdapter (可选)
├── mwb-ai-claw-app           # 应用层
│   └── com.mwb.ai.claw
│       ├── agent/            # AgentServiceImpl, executor/ChatCmdExe
│       ├── session/          # SessionServiceImpl, executor/
│       └── assembler/        # DTO ↔ Domain 转换
├── mwb-ai-claw-domain        # 领域层（见 3.3）
├── mwb-ai-claw-infrastructure
│   └── com.mwb.ai.claw
│       ├── llm/              # LlmGatewayImpl + 多 provider 适配
│       │   ├── openai/ deepseek/ qwen/ ollama/ anthropic/
│       │   └── LlmGatewayImpl.java
│       ├── tool/             # ToolGatewayImpl + 内置工具实现
│       │   ├── builtin/      # FileTool, ShellTool, HttpTool, DbTool
│       │   ├── mcp/          # MCP 协议工具适配
│       │   └── ToolGatewayImpl.java
│       ├── memory/           # MemoryGatewayImpl (文件/DB)
│       ├── im/               # 飞书/钉钉 SDK 接入
│       └── config/           # Spring 配置
└── start                     # 启动模块
    └── resources/application.yml
```

## 五、关键流程设计

### 5.1 一次对话的 ReAct 循环

```
用户消息
  │
  ▼
adapter.AgentController.chat(ChatCmd)
  │
  ▼
app.ChatCmdExe.execute()
  │  1. 加载/创建 Session 聚合
  │  2. 追加 user Message
  │  3. 调用 ReActLoopService
  ▼
domain.core.ReActLoopService.run(session, agent)
  │
  │  loop (maxSteps):
  │    ┌─ MemoryGateway 裁剪上下文窗口
  │    ├─ PromptAssembler 组装 system+history+tools
  │    ├─ LlmGateway.chat(messages, toolSpecs)   ← 依赖倒置
  │    ├─ 解析返回：纯文本回答 → 终止；tool_call → Action
  │    ├─ ToolGateway.execute(toolName, args) ← 依赖倒置
  │    │    └─ ToolExecutor.execute()
  │    ├─ 将 Observation 追加到 Session
  │    └─ 继续下一轮
  │
  ▼
返回最终回答 (SSE 流式推送到 adapter)
```

### 5.2 工具注册与路由

- 启动时 Spring 自动收集所有 `ToolExecutor` Bean，`ToolGatewayImpl` 按名称路由。
- 新增工具只需实现 `ToolExecutor` 接口并加 `@Component`，无需修改领域层。
- 工具执行结果统一封装为 `ToolResult`，供下一轮 LLM 推理。

### 5.3 记忆管理

- **短期记忆**：当前 Session 的 Message 列表，按 token 上限滑动窗口。
- **长期记忆**：工作区 `~/.mwb-ai-claw/workspace/` 下的 `AGENT.md`、`MEMORY.md`，跨会话检索注入。

## 六、技术选型

| 维度 | 选型 | 说明 |
|------|------|------|
| 框架 | Spring Boot 2.7 + COLA 5.0 | 沿用现有架构 |
| LLM SDK | OkHttp + 各厂商 OpenAI 兼容 API | 统一 Chat Completions 接口 |
| 流式输出 | SSE (SseEmitter) | 支持 Token 级流式返回 |
| 工具协议 | MCP (Model Context Protocol) | 兼容 OpenClaw 生态工具 |
| 持久化 | MyBatis + MySQL / 本地文件 | 会话与记忆存储 |
| 序列化 | Fastjson + Jackson | 沿用现有依赖 |
| 异步 | Spring 异步线程池 | 长任务工具执行 |

## 七、实施路线图

### Phase 1：最小可用 Agent（MVP）
- [ ] client 层：定义 `AgentServiceI`、`ChatCmd`、`ChatResponseDTO`
- [ ] domain 层：`Session`/`Message` 聚合、`LlmGateway`/`ToolGateway` 接口、`ReActLoopService`
- [ ] infrastructure 层：`LlmGatewayImpl`（OpenAI 兼容）、内置 `EchoTool`、内存版 `MemoryGatewayImpl`
- [ ] adapter 层：`AgentController`（REST + SSE 流式）
- [ ] 跑通「用户提问 → LLM 回答」与「LLM 调用 EchoTool」闭环

### Phase 2：工具能力扩展
- [ ] 内置工具：`FileTool`（读写）、`ShellTool`（执行命令）、`HttpTool`、`DbTool`
- [ ] MCP 协议适配，支持接入外部 MCP Server
- [ ] 工具权限与安全沙箱（命令白名单、路径限制）

### Phase 3：记忆与多渠道
- [ ] 文件式长期记忆（`AGENT.md` / `MEMORY.md`）
- [ ] IM 渠道接入：飞书、钉钉、Telegram
- [ ] 多会话管理与隔离

### Phase 4：进阶能力
- [ ] 多 Agent 路由与协作
- [ ] 浏览器控制工具（CDP）
- [ ] 本地 Ollama 离线部署支持
- [ ] 可视化控制台（Web Dashboard）

## 八、架构约束与原则

1. **领域层纯净**：`domain` 模块不依赖 Spring、不依赖任何 LLM SDK，仅通过 Gateway 接口定义能力契约。
2. **依赖倒置**：所有外部能力（LLM、工具、存储、IM）的实现都在 `infrastructure`，向 `domain` 的接口靠拢。
3. **CQRS 友好**：命令（ChatCmd）与查询（MessageHistoryQry）分离，执行器各司其职。
4. **可扩展**：新增 LLM Provider 或工具，只需在 `infrastructure` 实现对应接口并注册，不改领域层。
5. **本地优先**：默认数据本地化，敏感操作工具需显式授权。
