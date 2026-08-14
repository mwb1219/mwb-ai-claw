# mwb-ai-claw

> 基于 COLA 架构（DDD 思想）实现的智能体 Agent 框架，灵感来自 OpenClaw——本地优先、可真正动手干活、开箱即用的个人 AI 助手。

## 一、项目目标

构建一个 Java 版的智能体 Agent 框架，具备以下核心能力：

- **多渠道接入**：Web / REST API / WebSocket / Shell 终端
- **ReAct 推理循环**：思考（Thought）→ 行动（Action）→ 观察（Observation）的迭代执行
- **工具调用能力**：文件读写、Shell 执行、HTTP 请求、长期记忆读写
- **MCP 协议支持**：标准化工具接入，兼容生态内任意 MCP 工具
- **记忆系统**：短期会话记忆（文件持久化）+ 长期工作区记忆（AGENT.md / MEMORY.md）
- **多模型适配**：支持 OpenAI 兼容接口（DeepSeek、通义千问等）
- **本地优先**：会话与记忆数据本地化，工具执行受安全沙箱保护

## 二、整体架构

项目采用 DDD 六模块分层，通过 Gateway 接口实现依赖倒置：

```
┌─────────────────────────────────────────────────────────────┐
│                      start（启动）                           │
│            Spring Boot Application                          │
└─────────────────────────────────────────────────────────────┘
                           ▲
┌─────────────────────────────────────────────────────────────┐
│                   adapter（适配层）                           │
│   AgentController (REST/SSE) / WebSocket / AgentShell       │
│   职责：协议转换、请求转发到 app 层                            │
└─────────────────────────────────────────────────────────────┘
                           ▲
┌───────────────────────┬────────────────────────────────────┐
│ client（客户端 SDK）    │          app（应用层）             │
│ - AgentServiceI 接口   │ - AgentServiceImpl                 │
│ - ChatCmd / DTO        │ - ChatCmdExe（对话编排）            │
│ - SessionDTO           │ - SessionListQryExe               │
│                        │ - SessionDeleteCmdExe              │
│                        │ - SessionAssembler                 │
└───────────────────────┴────────────────────────────────────┘
                           ▲
┌─────────────────────────────────────────────────────────────┐
│                    domain（领域层）                           │
│   聚合：Session / Agent / Message                            │
│   领域服务：ReActLoopService                                 │
│   Gateway 接口：LlmGateway / ToolGateway / MemoryGateway    │
│                 LongTermMemoryGateway / AgentGateway        │
│   值对象：ToolSpec / ToolResult / ToolCall / LlmMessage      │
│   回调接口：ProgressCallback / LlmStreamCallback            │
└─────────────────────────────────────────────────────────────┘
                           ▲
┌─────────────────────────────────────────────────────────────┐
│                infrastructure（基础设施）                     │
│   core：AgentGatewayImpl                                    │
│   tool：ToolGatewayImpl + 内置工具 + MCP 适配                │
│   memory：FileBasedSessionGateway + FileBasedMemoryGateway  │
│   llm：LlmGatewayImpl（OpenAI 兼容流式/非流式）              │
│   config：AgentConfiguration / AgentProperties / AgentConfigLoader│
│   security：ToolSecurity（命令白名单/路径限制/超时控制）       │
└─────────────────────────────────────────────────────────────┘
```

**依赖方向**：`adapter / app / infrastructure` → `client + domain`；`domain` 不依赖任何下层。

## 三、已实现能力

### 3.1 Phase 1：最小可用 Agent（MVP）✅

- [x] `client` 层：`AgentServiceI`、`ChatCmd`、`ChatResponseDTO`、`SessionDTO`
- [x] `domain` 层：`Session`/`Message` 聚合、`LlmGateway`/`ToolGateway`/`MemoryGateway` 接口、`ReActLoopService`
- [x] `infrastructure` 层：`LlmGatewayImpl`（OpenAI 兼容 API）、内置 `EchoTool`
- [x] `adapter` 层：`AgentController`（REST POST + SSE 流式 GET）
- [x] 跑通「用户提问 → LLM 回答」与「LLM 调用 EchoTool」闭环

### 3.2 Phase 2：工具能力扩展 ✅

- [x] 内置工具：`FileTool`（读/写/列目录）、`ShellTool`（沙箱执行）、`HttpTool`（GET/POST）、`EchoTool`
- [x] MCP 协议适配：stdio / SSE 传输层 + JSON-RPC，动态注册外部 MCP Server 工具
- [x] 工具安全沙箱：命令白名单（65 个）+ 黑名单（21 个）、路径限制、超时控制（30s）、输出截断（10000 字符）
- [x] **WebSocket 流式接口**：`/ws/agent` 端点，JSON 事件推送
- [x] LLM 流式回调：`LlmStreamCallback`（onToken / onToolName / onToolArguments）

### 3.3 Phase 3：记忆与多渠道 ✅

- [x] 文件式长期记忆：`AGENT.md`（Agent 扩展指令）+ `MEMORY.md`（跨会话记忆）
- [x] 长期记忆工具：`read_memory` / `write_memory`
- [x] 会话文件持久化：`.agent/sessions/<id>.json`，跨重启保留
- [x] 多会话管理：创建、列表、切换、删除，按时间倒序
- [x] 会话自动标题：取首条消息前 30 字符
- [x] **Shell 终端交互**：JLine REPL，支持流式/同步对话，ANSI 彩色输出

### 3.4 Phase 4：多 Agent 路由与配置工程 ✅

- [x] 多 Agent 专家路由：规则路由（关键词）+ LLM 语义路由（LLM 决策）+ 组合路由（规则优先、LLM 兜底）
- [x] Context Engineering 领域抽象：`ContextAssembler`（system prompt + 历史 + 工具统一组装）
- [x] 敏感配置抽象到 `.env`：`application.yml` 通过 `${VAR:default}` 占位符引用，避免密钥泄露
- [x] Agent 配置按协作模式分文件：`{mode}-agents.json`（运行目录优先），`--agent.mode` 启动参数切换
- [x] 多 Agent 独立模型：每个 Agent 可配置自己的 `model` / `api-key`（缺省继承默认）

### 3.5 Phase 5：分层记忆（Layered Memory）✅

- [x] 五层记忆模型：指令层 → 工作记忆（Hot）→ 短期（会话全量）→ 中期（摘要页）→ 长期（事实页）
- [x] Token 预算模型：`contextWindow × 60%` 预算，System / Tools / Memory 按 25/25/50 分配，预算内组装上下文
- [x] 动态换页（Paging）：每轮检查，预算溢出或未摘要消息达到阈值时，将最旧块压缩为摘要页落盘 `.agent/memory/pages/{sessionId}/summary-{blockStart}.json`
- [x] 历史摘要注入 System 提示：换页后早期信息不丢失，LLM 仍可回答早期对话内容
- [x] 结构化长期记忆：LLM 提炼事实（key/content/importance），重要度过滤 + 同 key 合并去重，落盘 `.agent/memory/facts.jsonl`
- [x] 关键词检索：中文 bigram 分词 BM25 简化版，`read_memory` 工具支持 `query` 参数检索事实与摘要
- [x] `write_memory` 工具升级：`content` + `topic` + `importance` 三参数，按重要度阈值写入事实
- [x] 换页策略可插拔：`token`（预算驱动，默认）/ `importance`（重要度驱动，低价值话题提前压缩、高价值保留）
- [x] 事实 merge 去重深化：同 key 按重要度/信息量择优，版本号自增、时间戳保留最新，`facts.jsonl` 单条维护
- [x] 提炼异步化：摘要/事实提炼在独立线程池串行执行，不阻塞主对话链路（`synthesis-async`）
- [x] 优雅降级：提炼/换页失败仅记录日志，不阻塞主对话链路

### 3.6 待实施

- [ ] IM 渠道接入：飞书、钉钉、Telegram
- [ ] 多 Agent 协作模式：编排（orchestration）/ 流水线（pipeline）编排服务
- [ ] 浏览器控制工具（CDP）
- [ ] 本地 Ollama 离线部署支持
- [ ] Context Engineering：上下文压缩/裁剪（token 预算、历史裁剪、摘要）
- [ ] Context Engineering：检索增强（RAG/向量库相关内容注入）
- [ ] Context Engineering：上下文策略（优先级排序、多策略实现）
- [ ] Context Engineering：成本优化（token 用量统计与控制）

## 四、领域模型

### 4.1 包结构

```
domain/
├── core/                  # 核心域
│   ├── Agent.java         # 实体：Agent 配置（含 agentInstructions）
│   ├── AgentGateway.java  # 接口：Agent 配置加载
│   ├── Session.java       # 聚合根：会话（含 createTime/updateTime/自动标题）
│   ├── SessionStatus.java # 枚举
│   ├── Message.java       # 实体：消息
│   ├── MessageRole.java   # 枚举
│   ├── ModelConfig.java   # 值对象：模型配置
│   ├── ReActLoopService   # 领域服务：ReAct 推理循环
│   ├── ReActResult.java   # 值对象：推理结果
│   └── ProgressCallback   # 回调：进度推送
├── context/               # 上下文工程域
│   ├── ContextAssembler.java        # 接口：上下文组装（Context Engineering 核心入口）
│   └── DefaultContextAssembler.java # 默认实现：system prompt + 历史 + 工具
├── llm/                   # LLM 域
│   ├── LlmGateway.java    # 接口：LLM 调用（流式 + 非流式）
│   ├── LlmRequest.java
│   ├── LlmResponse.java
│   ├── LlmMessage.java
│   ├── LlmStreamCallback  # 流式回调接口
│   └── ToolCall.java      # 工具调用值对象
├── tool/                  # 工具域
│   ├── ToolGateway.java   # 接口：工具注册与执行
│   ├── ToolExecutor.java  # 接口：工具执行器（扩展点）
│   ├── ToolSpec.java      # 工具规格
│   ├── ToolResult.java    # 工具结果
│   ├── DynamicToolRegistry# 接口：动态工具注册
│   ├── McpServerConfig    # MCP Server 配置
│   └── McpToolDef.java    # MCP 工具定义
└── memory/                # 记忆域
    ├── MemoryGateway.java        # 接口：会话级记忆
    └── LongTermMemoryGateway.java # 接口：长期记忆（AGENT.md/MEMORY.md）
```

### 4.2 基础设施实现

```
infrastructure/
├── core/AgentGatewayImpl         # Agent 配置加载 + AGENT.md 注入
├── llm/LlmGatewayImpl            # OpenAI 兼容 API（流式 SSE 解析）
├── tool/
│   ├── ToolGatewayImpl           # Bean 自动收集 + 动态注册
│   ├── ToolSecurity.java         # 安全沙箱（路径/命令/输出）
│   ├── builtin/
│   │   ├── EchoTool              # 回显测试
│   │   ├── FileTool              # 文件操作（受路径限制）
│   │   ├── ShellTool             # Shell 执行（受白名单保护）
│   │   ├── HttpTool              # HTTP 请求（受 host 限制）
│   │   ├── ReadMemoryTool        # 读取 MEMORY.md
│   │   └── WriteMemoryTool       # 写入 MEMORY.md
│   └── mcp/                      # MCP 协议栈
│       ├── McpClient / McpClientManager
│       ├── McpToolAdapter / McpToolRegistrar
│       └── transport/StdioTransport / SseTransport
├── memory/
│   ├── FileBasedSessionGateway   # 会话文件持久化
│   ├── FileBasedMemoryGateway    # 长期记忆文件读写
│   └── MemoryGatewayImpl         # 纯内存版（测试用）
└── config/
    ├── AgentProperties            # YAML 配置映射
    └── AgentConfiguration         # Spring Bean 装配
```

## 五、交互方式

### 5.1 REST API

| 方法       | 路径                    | 说明                    |
| -------- | --------------------- | --------------------- |
| `POST`   | `/agent/chat`         | 同步对话                  |
| `GET`    | `/agent/chat/stream`  | SSE 流式对话（实时 token 推送） |
| `POST`   | `/agent/session`      | 创建会话                  |
| `GET`    | `/agent/session/{id}` | 查询会话详情                |
| `GET`    | `/agent/sessions`     | 列出所有会话                |
| `DELETE` | `/agent/session/{id}` | 删除会话                  |

### 5.2 WebSocket

```
ws://localhost:8080/ws/agent
```

客户端发送 JSON：

```json
{"type":"chat","message":"你好","sessionId":"xxx","agentId":"default"}
```

服务端推送 JSON 事件流：`session` → `step` → `token` → `tool_name` → `tool_args` → `reply` → `done`

### 5.3 Shell 终端（REPL）

```bash
# 构建
mvn package -pl start -am -DskipTests

# 启动 Shell 模式（可追加 --agent.mode=xxx 切换协作模式）
java -jar start/target/start-*.jar --spring.profiles.active=shell
```

**支持的命令**：

| 命令                     | 功能           |
| ---------------------- | ------------ |
| 自由文本                   | 发送给 Agent 对话 |
| `/mode`                | 切换 流式/同步 模式  |
| `/session`             | 查看当前会话       |
| `/session new`         | 创建新会话        |
| `/session list`        | 列出所有会话       |
| `/session switch <id>` | 切换会话（支持前缀匹配） |
| `/session delete <id>` | 删除会话         |
| `/clear`               | 清屏           |
| `/exit` / `/quit`      | 退出           |

流式模式下 AI 回复逐 token 绿色打印，Thought 紫色、Action 黄色、Observation 蓝色。命令历史自动保存至 `~/.mwb-ai-claw-history`。

### 5.4 前端测试控制台

项目根目录下的 `frontend/` 为纯静态前端，包含同步/流式/WebSocket 三种模式切换、会话列表（刷新/删除）、推理轨迹面板、Markdown 渲染。

## 六、配置说明

### 6.1 密钥配置（.env）

敏感配置（API Key 等）统一通过 `.env` 环境变量文件注入，避免提交代码时泄露：

```bash
# 1. 复制模板（首次运行）
cp .env.example .env

# 2. 填入真实密钥
DEFAULT_API_KEY=sk-xxx
```

- `.env` 已被 `.gitignore` 排除，不会提交；`.env.example`（key 留空）作为模板提交供团队参考。
- **环境变量优先级**（由高到低）：命令行参数 > 系统环境变量 > `.env` 文件 > 配置文件默认值。生产环境可直接注入系统环境变量覆盖 `.env`。
- 支持 `KEY=value` 格式（忽略 `#` 注释、去除引号），Spring 配置中用 `${VAR:default}` 引用，`default` 为兜底值。

### 6.2 核心配置（application.yml）

```yaml
agent:
  agent-id: default
  name: mwb-ai-claw
  system-prompt: "你是 mwb-ai-claw 智能助手..."
  mode: routing                      # 协作模式，决定加载哪个 {mode}-agents.json
  model: ${DEFAULT_MODEL:deepseek-chat}            # 通过环境变量引用，避免硬编码
  base-url: ${DEFAULT_BASE_URL:https://api.deepseek.com}
  api-key: ${DEFAULT_API_KEY:}
  temperature: 0.7
  max-tokens: 2048
  max-steps: 8
  memory-dir: ""                      # 长期记忆目录，默认 ${user.dir}/.agent
  tools:
    - echo
    - http
    - file
    - shell
    - read_memory
    - write_memory

  # 分层记忆（突破上下文窗口：分层存储 + 动态换页 + 检索召回）
  memory:
    enabled: true                  # 是否启用分层记忆
    context-window-tokens: 65536   # 模型上下文窗口（tokens），用于预算计算
    context-budget-ratio: 0.6      # 记忆区占模型窗口比例
    prompt-budget-ratio: 0.25      # System 区（AGENT.md + 事实页）占记忆预算比例
    tool-budget-ratio: 0.25        # Tools 区占记忆预算比例
    hot-window-size: 20            # 工作记忆：Hot 原文最大条数
    summary-block-size: 10         # 多少条消息合成一个摘要块（触发换页）
    importance-threshold: 0.6      # 事实写入长期记忆的重要度阈值
    top-k: 5                       # 关键词检索召回条数
    eviction-policy: token         # 换页策略：token（预算驱动）| importance（重要度驱动）
    synthesis-async: true          # 提炼是否异步执行（线程池串行，不阻塞主对话链路）

  # 工具安全沙箱
  security:
    enabled: true
    workspace-dir: ""                        # 文件操作根目录
    shell-whitelist: [ls, cat, grep, ...]    # 65 个命令
    shell-blacklist: ["rm -rf /", sudo, ...]  # 21 个危险模式
    tool-timeout-seconds: 30
    max-output-length: 10000
    http-allowed-hosts: []
```

### 6.3 多 Agent 配置（{mode}-agents.json）

专家 Agent 定义按协作模式分文件存放，命名 `{mode}-agents.json`（如 `routing-agents.json`）。**加载优先级：运行目录下的同名文件 > jar 内置 classpath 默认模板**。使用者可在运行目录放置自己的 `routing-agents.json`，自由增删、调整 Agent，无需重新打包。

```bash
# 从 jar 内置模板导出后按需修改（或直接参照下方格式在运行目录新建）
unzip -p start/target/start-*.jar routing-agents.json > routing-agents.json
```

文件格式：

```json
{
  "mode": "routing",
  "agents": [
    {
      "agentId": "coder",
      "name": "编码专家",
      "description": "擅长编写代码、调试 bug、代码审查与技术实现",
      "keywords": ["代码", "bug", "实现", "开发", "调试", "编译", "报错", "函数", "接口"],
      "systemPrompt": "你是资深软件工程师，擅长编码、调试与问题排查，代码示例清晰规范。",
      "tools": ["file", "shell", "http", "read_memory", "write_memory"],
      "maxSteps": 10,
      "model": "${CODER_MODEL:${DEFAULT_MODEL:deepseek-chat}}",
      "apiKey": "${CODER_API_KEY:${DEFAULT_API_KEY:}}"
    }
  ]
}
```

字段说明：

| 字段                             | 必填 | 说明                                    |
| ------------------------------ | -- | ------------------------------------- |
| `agentId`                      | 是  | Agent 标识（路由目标）                        |
| `name`                         | 是  | 显示名称                                  |
| `description`                  | 否  | 能力描述，供 LLM 语义路由判断意图                   |
| `keywords`                     | 否  | 规则路由关键词                               |
| `systemPrompt`                 | 否  | 系统提示词，缺省继承默认                          |
| `tools`                        | 否  | 可用工具列表，缺省继承默认                         |
| `maxSteps`                     | 否  | 最大推理步数，缺省继承默认                         |
| `model` / `baseUrl` / `apiKey` | 否  | 独立模型配置，缺省继承默认，支持 `${VAR:default}` 占位符 |
| `temperature` / `maxTokens`    | 否  | 采样温度 / 单次最大 tokens，缺省继承默认             |

### 6.4 协作模式切换与多模型

启动时通过 `--agent.mode=xxx` 指定加载哪个 `{mode}-agents.json`：

```bash
# 专家路由模式（默认，内置 coder / researcher 两个专家）
java -jar start/target/start-*.jar --agent.mode=routing

# 未来编排 / 流水线模式（需在运行目录提供对应 agents 文件）
java -jar start/target/start-*.jar --agent.mode=orchestration
```

每个 Agent 的模型独立配置：在 `{mode}-agents.json` 中为 Agent 指定 `model` / `apiKey`（用 `${VAR}` 引用 `.env` 变量），未配置的字段自动继承默认值。`.env` 示例：

```bash
# 默认模型
DEFAULT_MODEL=deepseek-chat
DEFAULT_API_KEY=sk-default-xxx

# coder 专家（独立模型，key 留空则继承 DEFAULT_API_KEY）
CODER_MODEL=deepseek-coder
CODER_API_KEY=

# researcher 专家
RESEARCHER_MODEL=deepseek-chat
RESEARCHER_API_KEY=sk-researcher-xxx
```

### 6.5 记忆文件

```
.agent/
├── AGENT.md                # Agent 扩展指令（追加到 system prompt）
├── MEMORY.md               # 长期记忆（非分层模式使用，Agent 可通过工具读写）
├── sessions/
│   ├── a1b2c3d4.json       # 会话文件（JSON 持久化）
│   └── e5f6g7h8.json
└── memory/                 # 分层记忆（enabled=true 时启用）
    ├── facts.jsonl         # 长期事实（JSONL，重要度过滤 + 同 key 合并去重）
    └── pages/
        └── {sessionId}/
            ├── summary-0.json   # 摘要页：历史消息压缩（blockStart 标记）
            ├── summary-10.json
            └── ...
```

### 6.6 MCP Server 配置（mcp-server.json）

MCP Server 配置独立在 `mcp-server.json`（与 Cursor / Claude 的 mcp.json 格式一致），**加载优先级：运行目录下的** **`mcp-server.json`** **> classpath 默认模板**。支持 stdio 与 streamable\_http 两种传输：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["@modelcontextprotocol/server-filesystem", "/tmp/workspace"]
    },
    "fetch": {
      "type": "streamable_http",
      "url": "https://mcp.example.com/fetch"
    }
  }
}
```

- **stdio**：`command` + `args`，可加 `env` 传入密钥（如 `TAVILY_API_KEY`）。
- **streamable\_http**：`type: streamable_http` + `url`（单端点 HTTP 传输，自动兼容 SSE 响应与 `Mcp-Session-Id`）。

## 七、安全机制

| 机制      | 说明                                                        |
| ------- | --------------------------------------------------------- |
| 命令白名单   | 65 个允许的 Shell 命令，涵盖文件操作、文本处理、构建工具、包管理等                    |
| 命令黑名单   | 21 个危险模式：`rm -rf /`、`sudo`、`mkfs`、fork bomb、`chmod 777` 等 |
| 路径限制    | `FileTool` 和 `ShellTool` 仅允许在配置的 `workspace-dir` 内操作      |
| 超时控制    | 工具执行 30 秒超时，超时后强制终止进程                                     |
| 输出截断    | 工具输出限制 10000 字符，防止撑爆上下文                                   |
| HTTP 限制 | 可配置允许的 host 列表，阻止 SSRF                                    |

所有安全违规均捕获为 `SecurityException`，返回 `ToolResult.error("安全拦截: ...")`，不会中断 ReAct 循环。

## 八、技术选型

| 维度       | 选型                                                  | 说明                               |
| -------- | --------------------------------------------------- | -------------------------------- |
| 框架       | Spring Boot 2.7 + COLA 5.0                          | DDD 分层架构                         |
| LLM 调用   | OkHttp + OpenAI 兼容 API                              | 统一 Chat Completions 接口           |
| 流式输出     | SSE (SseEmitter) + WebSocket (TextWebSocketHandler) | Token 级实时推送                      |
| 工具协议     | MCP (Model Context Protocol)                        | stdio / streamable\_http（SSE 兼容） |
| Shell 终端 | JLine 3.20                                          | ANSI 着色、命令历史、行编辑                 |
| 序列化      | Jackson                                             | Session JSON 持久化                 |
| 持久化      | 本地文件 (.agent/ 目录)                                   | 会话文件 + 长期记忆文件                    |
| 前端       | 原生 HTML/CSS/JS                                      | 无框架依赖，可直接打开                      |

## 九、开发指南

### 新增工具

实现 `ToolExecutor` 接口并添加 `@Component`：

```java
@Component
public class MyTool implements ToolExecutor {
    public String getName() { return "my_tool"; }
    public ToolSpec getSpec() { return new ToolSpec("my_tool", "描述", schema); }
    public ToolResult execute(String argsJson) { ... }
}
```

然后在 `application.yml` 的 `agent.tools` 列表中添加工具名即可。

### 新增 LLM Provider

实现 `LlmGateway` 接口的 `chat()` 和 `streamChat()` 方法，替换或扩展 `LlmGatewayImpl`。

### 测试

```bash
# 运行长期记忆测试
mvn test -pl mwb-ai-claw-infrastructure -Dtest=MemoryFilePersistenceTest
```

