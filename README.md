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
│ - ChatCmd / DTO        │ - ChatCmdExe（编排选择 + 分发）      │
│   （含 orchestrationId）│ - SessionListQryExe               │
│ - SessionDTO           │ - SessionDeleteCmdExe              │
│                        │ - SessionAssembler                 │
└───────────────────────┴────────────────────────────────────┘
                           ▲
┌─────────────────────────────────────────────────────────────┐
│                    domain（领域层）                           │
│   聚合：Session / Agent / Message                            │
│   领域服务：ReActLoopService                                 │
│   Gateway 接口：LlmGateway / ToolGateway / MemoryGateway    │
│                 LongTermMemoryGateway / AgentGateway        │
│   collaboration：OrchestrationDefinition / OrchestrationContext│
│                 AgentOrchestrator / CollaborationResult     │
│                 ExecutionUnit                                │
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
│   config：AgentProperties / AgentRegistryLoader             │
│           / OrchestrationConfigLoader                       │
│   collaboration：OrchestratorRegistry / ExecutionUnitImpl   │
│           / RoutingOrchestrator / TodoDelegateOrchestrator  │
│           / 协作工具(invoke_*)                              │
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
- [x] Agent 注册表：`agents.json` 定义可复用的专家 Agent（跨编排共享，运行目录优先，支持 `${VAR}` 占位符）
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

### 3.6 Phase 6：检索增强与成本优化 ✅

- [x] 向量检索：`EmbeddingGateway` + `VectorMemoryRetriever`（余弦相似度），页向量三级缓存（内存 → `.agent/memory/vectors/` 磁盘 → 惰性计算）
- [x] 混合检索（RRF 融合）：`HybridMemoryRetriever`，`keyword | vector | hybrid` 三模式，embedding 失败自动降级回退关键词
- [x] 跨会话档案 RAG：会话结束原文增量归档为 ARCHIVE 页（幂等），检索候选纳入跨会话档案
- [x] 多 Agent 共享记忆：`readContext` 以最新用户消息自动检索跨会话记忆并注入 System prompt（`shared-retrieve`）
- [x] 检索增强注入：检索召回页通过「相关记忆（检索）」标题注入上下文（Context Engineering RAG 闭环）
- [x] 提炼成本优化：小模型提炼（`synthesizer-model` 独立配置）+ 提炼结果缓存（按内容哈希去重，LRU）+ 同会话提炼任务去重调度
- [x] 记忆可视化：shell `/memory` 命令（`stats/facts/summaries/archive/search`）+ REST `/memory` 面板接口（各层统计/配置快照/缓存命中率/检索调试）

### 3.7 Phase 7：配置与编排分离 ✅

- [x] 配置与编排分离：`agents.json`（Agent 注册表，跨编排复用）+ `orchestrations.json`（编排注册表 + 意图元数据），彻底废弃旧 `{mode}-agents.json` 与 `agent.mode`
- [x] 编排插件化（SPI）：`AgentOrchestrator` 接口（`type`/`validate`/`orchestrate`），`OrchestratorRegistry` 自动收集注册，新增编排零主链路改动
- [x] 编排选择两层化：显式指定（`orchestrationId`）> 默认编排（`agent.orchestration`，默认 `routing`）；多 Agent 协作编排封装为全局协作工具（`invoke_discussion` / `invoke_delegate`），由主 Agent 在 ReAct 推理中自主发起，废弃消息前置意图路由（`OrchestrationSelector`）
- [x] 内置编排：`RoutingOrchestrator`（单专家独立处理，默认兜底）+ `ConversationalOrchestrator`（多方专家多轮讨论，首轮并行观点→讨论轮串行回应→收敛，支持 consensus / moderator / best 三种收敛策略）+ `TodoDelegateOrchestrator`（主 Agent 思考→规划 Todo 列表→委托子 Agent 执行，子 Agent 可递归再委托，无依赖 Todo 并行执行、依赖 Todo 分层推进，逐层汇总返回；产物按 `{workdir}/{sessionId}/{时间戳}` 隔离落盘 plan.json/result.txt，叶子结论沉淀分层记忆 FACT；支持人工审批门禁 `approvalGate=root/all`（REST/WebSocket 审批 API 或 Shell 命令 `/pending` `/approve <layerKey>` `/reject <layerKey>`，拒绝或超时降级直执行）、汇总 top-k 上下文压缩、`replanRounds` 动态规划（首波执行后结合已得结果调整剩余 Todo，keep/drop/modify 协议）与 Todo 级编排嵌套组合（`orchestrationId` 可引用 conversational/delegate，嵌套调用链防环 A→B→A））
- [x] 对话式定义类型化：`ConversationDefinition` 实体类解析 conversation 配置（`rounds` / `participants` / `moderator` / `convergence` / `visibleHistory` / `thinking`）
- [x] 思考模式控制：阶段/参与者级 `thinking: false` 关闭推理（DeepSeek 思考模式会吃满输出预算导致正文为空）
- [x] 启动校验：编排 id 唯一、type 已注册、引用的 agentId 存在于注册表

### 3.8 Phase 8：Skill 技能支持 ✅

- [x] Skill 定义标准：`skills/<name>/SKILL.md`（YAML frontmatter：`name` / `description` + Markdown 指令正文）+ 可选 `resources/` 资源目录，遵循 Agent Skills 开放标准
- [x] 渐进式披露（三层）：L1 技能清单（name + description）常驻 system prompt → L2 `use_skill` 工具按需加载 SKILL.md 全文 → L3 资源经 `$SKILL_DIR` 按需读取，控制 token 成本
- [x] 零侵入扩展：新增技能 = 放目录重启即可，所有 Agent（routing / conversational / delegate 任意编排内）自动可用，主链路零改动
- [x] `use_skill` 注册为全局工具（对齐 MCP 工具），技能清单由 `DefaultContextAssembler` 注入 system prompt
- [x] 启动校验：技能 name / description 缺失、name 与目录不一致、name 重复 → 启动报错
- [x] 内置 12 个技能（classpath 模板）：`code-review`、`project-structure-analysis`、`unit-test-writing`、`git-workflow`、`ddd-modeling`、`tech-design-doc`、`web-research`、`database-design`、`doc-writing-guide`、`markdown-diagramming`、`doc-review`、`example-skill`（详见 `docs/feature-skill-support技术方案(SUBMIT).md` §6.2）

### 3.9 Phase 9：Shell 模式增强 ✅

- [x] **完整 shell 语义**：`ShellTool` 经 `bash -lc`（Windows 为 `cmd /c`）执行，支持管道 / 重定向 / 通配符 / `&&` / 变量；白名单升级为按命令段逐段校验（引号感知切分，防 `ls; rm -rf` 绕过）
- [x] **审批模式三档**：`shell-approval-mode`（`auto` 自动执行 / `ask` 命中规则弹 Y/N 确认（默认）/ `read-only` 拒绝）+ 30+ 高风险审批规则（`git push`、`rm`、`npm install`、`curl -X` 等）；headless / Web 场景无审批器时安全默认拒绝
- [x] **长时任务不超时强杀**：前台超时转为后台任务返回 taskId；新增 `shell_status` 工具（status/output/kill）；`shell` 支持 `background=true` 参数
- [x] **命令流式回显**：`ToolExecutor`/`ToolGateway` 回调版 execute，Shell 输出经 `ProgressCallback` 逐行实时绿色回显
- [x] **headless 模式**：`--prompt/-p` 单轮非交互 + stdin 管道自动进入（`echo "问题" | mwb-ai-claw`）；`--resume` / `--mode` / `--bg` 启动参数
- [x] **上下文管理**：`/clear` 语义修正为「清屏 + 重置上下文」；`/compact` 将历史消息 LLM 摘要压缩（保留最近 10 条）
- [x] **交互体验**：多行输入（``` ``` ``` / 引号 / 花括号未闭合自动续行）、Tab 补全（斜杠命令 / 会话 ID / 文件路径）、`!` 快捷执行（复用 Shell 沙箱 + 审批）、会话自动标题 + `/session rename`、`/cost` token 用量统计
- [x] **敏感信息脱敏**：shell 输出与工具入参中的密钥（`sk-` / `api_key=` / `token:` / `password=` / `Bearer` / `AKIA`）自动打码后再进上下文
- [x] **计划模式**：`/plan` 先让 Agent 输出方案（不执行工具），用户 y/N 确认后再执行
- [x] **自定义斜杠命令**：`~/.claw/commands/*.md`（frontmatter `name`/`description` + 模板占位符 `{args}`/`{1}`…），启动加载注册、Tab 补全、/help 展示
- [x] **MCP 管理**：`/mcp` 查看列表 / `/mcp connect`（重连）/ `/mcp disconnect`（断开并自动注销工具）
- [x] **会话导出 / 分叉**：`/session export <id>`（JSON 落盘 `~/.claw/exports/`）、`/fork [id]`（复制为独立新会话）
- [x] **后台 agent**：`--bg "任务"` 启动 + `/agent list` / `/agent attach <id>`（独立线程新会话执行）
- [x] **状态栏**：prompt 行显示会话 ID + 上下文估算 token（`≈Ntk`）+ plan 模式标记

### 3.10 待实施

- [ ] IM 渠道接入：飞书、钉钉、Telegram
- [ ] 浏览器控制工具（CDP）
- [ ] 本地 Ollama 离线部署支持
- [ ] Agent 级技能绑定（agents.json `skills` 字段静态注入）、技能热加载、技能市场

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
│   ├── MessageRole.java   # 枚举（已废弃：Message.role 改用 String）
│   ├── ModelConfig.java   # 值对象：模型配置
│   ├── ReActLoopService   # 领域服务：ReAct 推理循环
│   ├── ReActResult.java   # 值对象：推理结果
│   ├── ProgressCallback   # 回调：进度推送
│   ├── AgentRouter.java   # 接口：路由策略（意图 → agentId）
│   └── strategy/          # 路由策略实现
│       ├── RuleBasedAgentRouter  # 规则路由（关键词匹配）
│       ├── LlmBasedAgentRouter   # LLM 路由（语义匹配，兜底）
│       └── CompositeAgentRouter  # 组合路由（规则优先 + LLM 兜底）
├── collaboration/         # 编排协作域
│   ├── OrchestrationDefinition.java # 编排定义（id/type/keywords/config/agents）
│   ├── OrchestrationContext.java    # 编排上下文（消息/会话/网关/执行单元/回调）
│   ├── AgentOrchestrator.java       # 编排插件 SPI（type/validate/orchestrate）
│   ├── CollaborationResult.java     # 编排结果（reply/agentId/traceSteps）
│   └── ExecutionUnit.java           # 执行原语接口（会话复用/运行/产物落盘）
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
├── skill/                 # 技能域（Skill）
│   ├── Skill.java         # 值对象：技能（name/description/content/baseDir）
│   └── SkillGateway.java  # 接口：技能发现与按需加载（渐进式披露）
└── memory/                # 记忆域
    ├── MemoryGateway.java        # 接口：会话级记忆
    └── LongTermMemoryGateway.java # 接口：长期记忆（AGENT.md/MEMORY.md）
```

### 4.2 基础设施实现

```
infrastructure/
├── core/AgentGatewayImpl         # Agent 配置加载 + AGENT.md 注入
├── llm/
│   └── strategy/                 # LLM 策略实现
│       ├── LlmGatewayImpl            # OpenAI 兼容 API（流式 SSE 解析）
│       └── OpenAiEmbeddingGateway    # Embedding 实现（向量检索底座）
├── tool/
│   ├── ToolGatewayImpl           # Bean 自动收集 + 动态注册
│   ├── ToolSecurity.java         # 安全沙箱（路径/命令/输出）
│   ├── builtin/
│   │   ├── EchoTool              # 回显测试
│   │   ├── FileTool              # 文件操作（受路径限制）
│   │   ├── ShellTool             # Shell 执行（受白名单保护）
│   │   ├── HttpTool              # HTTP 请求（受 host 限制）
│   │   ├── ReadMemoryTool        # 读取 MEMORY.md
│   │   ├── WriteMemoryTool       # 写入 MEMORY.md
│   │   └── UseSkillTool          # 技能加载（global 工具，按需加载 SKILL.md 全文）
│   └── mcp/                      # MCP 协议栈
│       ├── McpClient / McpClientManager
│       ├── McpToolAdapter / McpToolRegistrar
│       └── transport/StdioTransport / SseTransport
├── skill/
│   ├── SkillLoader               # 目录扫描 + frontmatter 解析 + 启动校验（运行目录 > classpath）
│   └── SkillRegistryImpl         # 技能注册表（listSkills / getSkill 渐进式披露）
├── memory/
│   ├── FileBasedSessionGateway   # 会话文件持久化
│   ├── FileBasedMemoryGateway    # 长期记忆文件读写
│   ├── MemoryGatewayImpl         # 纯内存版（测试用）
│   ├── LayeredMemoryGatewayImpl  # 分层记忆门面（工作记忆 + 换页 + 提炼 + 检索）
│   └── strategy/                 # 记忆策略实现（可插拔）
│       ├── ImportanceEvictionPolicy / TokenBudgetEvictionPolicy # 换页策略（重要度 / 预算）
│       ├── KeywordMemoryRetriever / VectorMemoryRetriever / HybridMemoryRetriever # 检索策略
│       └── LlmMemorySynthesizer  # 提炼策略（LLM 摘要 / 事实提取 / 合并去重）
├── collaboration/
│   ├── OrchestratorRegistry      # 编排插件注册中心（SPI 自动收集）
│   ├── ConversationDefinition    # 对话式定义（类型化解析 config.conversation）
│   ├── DelegateDefinition        # 委托编排定义（类型化解析 config.delegate）
│   ├── TodoDefinition            # 委托编排规划产物（Todo 任务项）
│   ├── ExecutionUnitImpl         # 执行原语实现（临时会话/产物落盘）
│   └── strategy/                 # 编排 / 选择策略实现（SPI 插件）
│       ├── RoutingOrchestrator       # 路由编排（单专家独立处理，默认兜底）
│       ├── ConversationalOrchestrator # 对话式编排（多专家多轮讨论 + 收敛）
│       ├── TodoDelegateOrchestrator  # 委托编排（规划 Todo → 委派子 Agent，可递归 + 并行）
│       └── tool/builtin/             # 协作编排工具（多 Agent 协作经 ReAct 工具由主 Agent 自主发起）
│           ├── InvokeDiscussionTool  # invoke_discussion：对话式编排（多专家讨论收敛）
│           └── InvokeDelegateTool    # invoke_delegate：委托编排（Todo 拆解委派）
└── config/
    ├── AgentProperties            # YAML 配置映射（orchestration/tools/memory）
    ├── AgentConfiguration         # Spring Bean 装配
    ├── AgentRegistryLoader        # agents.json 加载（${VAR} 解析）
    └── OrchestrationConfigLoader  # orchestrations.json 加载 + 启动校验
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

> `/agent/chat` 请求体支持可选字段 `orchestrationId`（显式指定编排，未指定走默认编排 `routing`），响应返回实际使用的 `orchestrationId`。

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

#### 5.3.1 一键安装为全局命令（推荐）

项目 `tools/` 目录提供 `install.sh`，安装后可在任意目录直接执行 `mwb-ai-claw` 进入 Agent Shell（类似 `claude` 命令）：

```bash
# 在项目根目录执行
./tools/install.sh
```

脚本做的事：

1. `mvn package -pl start -am -DskipTests` 构建 `start-*.jar`
2. 安装到 `~/.mwb-ai-claw/`（可用 `MWB_AI_CLAW_HOME` 环境变量覆盖）：
   - `lib/start.jar` —— 构建产物
   - `bin/mwb-ai-claw` —— 启动器（加载 `.env` → `java -jar --spring.profiles.active=shell`）
   - `.env` —— 全局密钥配置（首次安装从项目 `.env` 或 `.env.example` 初始化）
3. 将启动器软链到 `PATH`（优先 `/usr/local/bin`，不可写则 `~/.local/bin`；不在 `PATH` 时自动追加到 `~/.zshrc` / `~/.bashrc`）

**设计要点**：

- **密钥全局兜底**：`~/.mwb-ai-claw/.env` 中的变量作为环境变量注入（优先级低于项目 `.env`），任意目录执行都能读取到 API Key；项目内 `.env` 可覆盖全局配置。
- **会话/记忆按项目隔离**：启动器不切换工作目录，`.agent/` 落在当前目录，不同项目互不干扰。
- **参数透传**：可覆盖任意 Spring 配置，如 `mwb-ai-claw --agent.orchestration=todo-delegate`。

```bash
# 安装完成后（重开终端或 source rc 后）
mwb-ai-claw                          # 进入 Agent Shell
mwb-ai-claw --agent.orchestration=... # 指定编排
./tools/install.sh --uninstall        # 卸载（清理安装目录与 PATH 链接，保留各项目 .agent/）
```

> 首次安装后请编辑 `~/.mwb-ai-claw/.env` 填入 `DEFAULT_API_KEY`。

#### 5.3.2 打包二进制分发包（不含源码）

项目 `tools/` 目录的 `package.sh` 可产出不含源码的可分发 tarball，便于交付给无 Maven/源码环境的用户：

```bash
./tools/package.sh                 # 构建并打包
./tools/package.sh --skip-build     # 复用已构建的 jar，跳过 mvn
```

产物 `dist/mwb-ai-claw-<version>-bin.tar.gz` 内含：

```
mwb-ai-claw-<version>-bin/
├── install.sh        安装脚本（自动识别二进制模式，跳过 mvn）
├── lib/start.jar     预构建可执行 jar（无源码）
└── .env.example      密钥配置模板
```

#### 5.3.3 用户使用流程

分发包同时包含 `install.sh`（Linux/macOS）与 `install.ps1`（Windows），用户按平台选其一，自动识别二进制模式（跳过 mvn 构建）。

##### Linux / macOS

**打包方**（项目维护者，需 java + mvn）：

```bash
# 1. 在项目根目录打包（构建 jar + 组装 dist + 生成 tar.gz）
./tools/package.sh

# 2. 得到产物 dist/mwb-ai-claw-<version>-bin.tar.gz（约 21M，不含源码）
# 3. 将该 tar.gz 分发给用户
```

**安装方**（终端用户，仅需 java，无需 Maven/源码）：

```bash
# 1. 解压分发包
tar -xzf mwb-ai-claw-<version>-bin.tar.gz

# 2. 进入解压目录执行安装（自动识别二进制模式，跳过 mvn 构建）
cd mwb-ai-claw-<version>-bin
./install.sh

# 3. （首次安装）编辑全局密钥配置，填入 DEFAULT_API_KEY
vi ~/.mwb-ai-claw/.env

# 4. 重开终端或 source rc 后，在任意目录执行命令进入 Agent Shell
mwb-ai-claw
```

##### Windows

**打包方**（项目维护者，需 java + mvn，在 PowerShell 中执行）：

```powershell
# 1. 在项目根目录打包（构建 jar + 组装 dist + 生成 zip）
.\tools\package.ps1

# 2. 得到产物 dist\mwb-ai-claw-<version>-bin.zip（不含源码）
# 3. 将该 zip 分发给用户
```

**安装方**（终端用户，仅需 java，无需 Maven/源码）：

```powershell
# 0. 若遇执行策略限制，先放开（仅当前会话）
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

# 1. 解压分发包
Expand-Archive mwb-ai-claw-<version>-bin.zip

# 2. 进入解压目录执行安装（自动识别二进制模式，跳过 mvn 构建）
cd mwb-ai-claw-<version>-bin
.\install.ps1

# 3. （首次安装）编辑全局密钥配置，填入 DEFAULT_API_KEY
notepad $HOME\.mwb-ai-claw\.env

# 4. 重开终端后，在任意目录执行命令进入 Agent Shell
mwb-ai-claw
```

> - 安装脚本会自动检测到同目录的 `lib/start.jar`（或 `lib\start.jar`）切换为二进制模式（跳过 Maven 构建），其余安装步骤（生成启动器、初始化 `.env`、配置 PATH）与源码模式一致。
> - 升级时重新执行安装脚本即可覆盖旧版本。
> - Windows 安装会将启动器 `mwb-ai-claw.cmd` 与 `mwb-ai-claw.ps1` 复制到 `%LOCALAPPDATA%\mwb-ai-claw-bin\` 并加入用户 PATH；在 cmd/PowerShell 中均可直接 `mwb-ai-claw` 调用。

#### 5.3.4 一键打包 + 安装（项目维护者）

`tools/setup.sh`（Windows 为 `tools\setup.ps1`）将「构建 → 打包分发包 → 用该包本地安装 → 清理」合并为一步，便于维护者本地验证打包与安装链路：

```bash
# Linux / macOS
./tools/setup.sh                # 构建 + 打包 + 安装
./tools/setup.sh --skip-build    # 复用已构建 jar + 打包 + 安装
```

```powershell
# Windows
.\tools\setup.ps1                # 构建 + 打包 + 安装
.\tools\setup.ps1 -SkipBuild      # 复用已构建 jar + 打包 + 安装
```

脚本执行流程：

1. 调用 `package.sh` / `package.ps1` 构建并产出分发包（`dist/`）
2. 解压刚生成的包到临时目录
3. 执行包内 `install.sh` / `install.ps1` 以二进制模式安装（验证包可用，不重复 mvn）
4. 清理临时目录

> 此脚本用于项目维护者本地「构建 + 验证打包 + 安装」一步完成。终端用户只需拿到分发包执行其中的安装脚本即可，无需此脚本。

#### 5.3.5 手动启动

```bash
# 构建
mvn package -pl start -am -DskipTests

# 启动 Shell 模式（编排按意图自动选择，默认 routing）
java -jar start/target/start-*.jar --spring.profiles.active=shell
```

**支持的命令**：

| 命令                     | 功能           |
| ---------------------- | ------------ |
| 自由文本                   | 发送给 Agent 对话 |
| `!<命令>`                | 本地执行 shell 命令并把输出交给 Agent 分析（如 `!npm test`） |
| `/mode`                | 切换 流式/同步 模式  |
| `/trace`               | 切换 观察结果 完整/缩写 |
| `/plan`                | 切换计划模式（先出方案，用户确认后执行） |
| `/compact`             | 压缩当前会话历史上下文（保留最近 10 条 + 摘要） |
| `/cost [id]`           | 当前会话（或指定会话）Token 用量估算 |
| `/session`             | 查看当前会话       |
| `/session new`         | 创建新会话        |
| `/session list`        | 列出所有会话       |
| `/session switch <id>` | 切换会话（支持前缀匹配） |
| `/session rename <id> <标题>` | 重命名会话 |
| `/session export <id> [path]` | 导出会话为 JSON（默认 `~/.claw/exports/`） |
| `/session delete <id>` | 删除会话         |
| `/fork [id]`           | 分叉当前（或指定）会话为新会话 |
| `/mcp`                 | 查看 MCP Server 列表 |
| `/mcp connect <name>`  | 连接（重连）MCP Server |
| `/mcp disconnect <name>` | 断开 MCP Server（自动注销其工具） |
| `/agent`               | 查看后台 agent 任务 |
| `/agent attach <id>`   | 查看后台 agent 结果 |
| `/memory`              | 分层记忆总览 / `stats` / `facts` / `summaries` / `archive` / `search <q>` |
| `/clear`               | 清屏并重置上下文（新建会话） |
| `/exit` / `/quit`      | 退出           |

流式模式下 AI 回复逐 token 绿色打印，Thought 紫色、Action 黄色、Observation 蓝色、Shell 输出绿色。命令历史自动保存至 `~/.mwb-ai-claw-history`。

**启动参数**（`mwb-ai-claw --prompt "问题"` 等）：

| 参数 | 功能 |
| --- | --- |
| `--prompt "问题"` / `-p` | headless 单轮非交互执行，纯文本输出（stdin 为管道时自动进入） |
| `--resume <sessionId>` | 恢复指定会话 |
| `--mode stream\|sync` | 指定流式 / 同步模式 |
| `--bg "任务"` | 启动后台 agent（独立新会话，`/agent attach` 查看结果） |
| `--agent.*=...` | 覆盖任意 Spring 配置（如审批模式 `--agent.security.shell-approval-mode=auto`） |

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
- **配置优先级**（由高到低）：命令行参数 > 项目 `.env` 文件 > 系统环境变量 > 配置文件默认值。生产环境可通过命令行参数或 `-D` 系统属性覆盖 `.env`。
- 支持 `KEY=value` 格式（忽略 `#` 注释、去除引号），Spring 配置中用 `${VAR:default}` 引用，`default` 为兜底值。

### 6.2 核心配置（application.yml）

```yaml
agent:
  agent-id: default
  name: mwb-ai-claw
  system-prompt: "你是 mwb-ai-claw 智能助手..."
  orchestration: routing             # 默认编排 id（引用 orchestrations.json 中的 id；多 Agent 协作编排经 invoke_* 工具由主 Agent 自主发起）
  model: ${DEFAULT_MODEL:deepseek-chat}            # 通过环境变量引用，避免硬编码
  base-url: ${DEFAULT_BASE_URL:https://api.deepseek.com}
  api-key: ${DEFAULT_API_KEY:}
  temperature: 0.7
  max-tokens: 8192                   # 思考模型 reasoning 计入 max_tokens，需预留足够空间（默认 2048）
  max-steps: 8
  max-steps-extension: 2.0            # ReAct 步数扩展系数：预算(max-steps)用尽且工具链未完成时自动扩展，硬上限 = max-steps × 系数
  memory-dir: ""                      # 长期记忆目录，默认 ${user.dir}/.agent
  skills-enabled: true                # 技能总开关（false 时不加载技能、不注册 use_skill 工具）
  skills-dir: ""                      # 技能根目录（默认 ${user.dir}/skills；classpath skills/ 为内置模板兜底）
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
    shell-whitelist: [ls, cat, grep, ...]    # 允许的 Shell 命令（按命令段逐段校验）
    shell-blacklist: ["rm -rf /", sudo, ...]  # 21 个危险模式
    shell-approval-mode: ask                 # 审批模式：auto（自动执行）| ask（命中规则弹确认，默认）| read-only（拒绝）
    shell-approval-patterns: [git push, rm , npm install, npm cache, curl -X, ...]  # 50+ 高风险命令，命中即需审批
    tool-timeout-seconds: 30                 # 超时后转为后台任务（shell_status 查询 / 终止）
    max-output-length: 10000
    http-allowed-hosts: []
```

### 6.3 Agent 注册表（agents.json）与编排注册表（orchestrations.json）

Agent 配置与编排定义完全解耦，分两个独立文件存放（`start/src/main/resources/` 内置默认模板）。**加载优先级：运行目录（user.dir）下的同名文件（命中即用，不再读取内置）> jar 内置 classpath 默认模板**，支持 `${VAR:default}` 占位符。使用者可在运行目录放置同名文件覆盖，自由增删 Agent / 编排，无需重新打包。

#### 6.3.1 Agent 注册表（agents.json）

定义可复用的专家 Agent（跨编排共享，不再按协作模式分文件）：

```json
{
  "agents": [
    {
      "agentId": "coder",
      "name": "编码专家",
      "description": "擅长编写代码、调试 bug、代码审查与技术实现",
      "keywords": ["代码", "bug", "实现", "开发", "调试", "编译", "报错", "函数", "接口"],
      "systemPrompt": "你是资深软件工程师，擅长编码、调试与问题排查，代码示例清晰规范。",
      "tools": ["file", "shell", "http", "read_memory", "write_memory"],
      "maxSteps": 10,
      "maxTokens": 16384,
      "model": "${CODER_MODEL:${DEFAULT_MODEL:deepseek-chat}}",
      "apiKey": "${CODER_API_KEY:${DEFAULT_API_KEY:}}"
    }
  ]
}
```

字段说明：

| 字段                             | 必填 | 说明                                    |
| ------------------------------ | -- | ------------------------------------- |
| `agentId`                      | 是  | Agent 标识（编排引用的目标）                    |
| `name`                         | 是  | 显示名称                                  |
| `description`                  | 否  | 能力描述，供 LLM 语义路由判断意图                   |
| `keywords`                     | 否  | 规则路由关键词（路由编排内部选择 Agent 使用）           |
| `systemPrompt`                 | 否  | 系统提示词，缺省继承默认                          |
| `tools`                        | 否  | 可用工具列表，缺省继承默认                         |
| `maxSteps`                     | 否  | 初始推理预算步数，缺省继承默认；预算用尽且工具链未完成时按 `max-steps-extension` 自动扩展（硬上限 = maxSteps × 系数） |
| `model` / `baseUrl` / `apiKey` | 否  | 独立模型配置，缺省继承默认，支持 `${VAR:default}` 占位符 |
| `temperature` / `maxTokens`    | 否  | 采样温度 / 单次最大 tokens，缺省继承默认             |

#### 6.3.2 编排注册表（orchestrations.json）

编排定义 + 意图元数据（`keywords`），供「意图驱动选择编排」使用：

```json
{
  "orchestrations": [
    {
      "id": "team-discussion",
      "type": "conversational",
      "description": "多方专家对话式讨论：架构师 / 编码专家 / 审查专家围绕同一问题多轮讨论，最后由决策主持收敛为明确结论",
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
    }
  ]
}
```

编排字段说明：

| 字段           | 必填 | 说明                                        |
| ------------ | -- | ----------------------------------------- |
| `id`         | 是  | 编排标识（意图命中 / 显式指定 / 默认兜底均引用此 id）        |
| `type`       | 是  | 编排插件类型：`routing` / `conversational` / `delegate`（已注册 SPI，可扩展） |
| `description` | 否 | 编排能力描述                                  |
| `keywords`   | 否  | 意图关键词（命中数最多者胜出；**兜底编排不设 keywords 不参与竞争**） |
| `agents`     | 否  | 该编排涉及 Agent 列表（启动校验引用存在性）               |
| `config`     | 否  | 编排自定义配置（结构由插件自行解释，如 conversational 的 conversation / delegate 的 delegate）   |

### 6.4 编排选择与多模型

对话请求默认走 `routing` 编排（单专家 ReAct 独立处理）；多 Agent 协作编排（对话式 / 委托）不再由消息前置意图路由选择，而是封装为全局协作工具，由主 Agent 在 ReAct 推理中自主决定是否发起：

| 协作工具 | 对应编排 | 适用场景 |
| ------- | ------- | ------- |
| `invoke_discussion` | `team-discussion` | 技术选型、方案对比、权衡决策类问题（多专家讨论 + 主持收敛） |
| `invoke_delegate` | `todo-delegate` | 复杂、多步骤、跨领域任务（主 Agent 规划 Todo → 委托子 Agent 分步执行） |

编排选择优先级（两层）：**显式指定**（请求体携带 `orchestrationId`，REST / WebSocket / Shell 均支持）> **默认编排**（`agent.orchestration`，默认 `routing`）。协作工具全局注册（`global=true`），对所有 Agent 可见，无需在 `agents.json` / `application.yml` 中声明。

```bash
# 默认编排 routing（单专家独立处理）
java -jar start/target/start-*.jar --spring.profiles.active=shell

# 修改默认编排
java -jar start/target/start-*.jar --agent.orchestration=team-discussion

# 默认使用委托编排（主 Agent 规划 Todo 拆解任务，委托子 Agent 并行/递归执行）
java -jar start/target/start-*.jar --agent.orchestration=todo-delegate
```

协作工具用法示例（主 Agent 自主发起，无需用户指定编排 id）：

```text
用户：帮我从零实现一个 REST 服务并审查代码
主 Agent：→ 调用 invoke_delegate(message="从零实现一个 REST 服务并审查代码")
          → 返回汇总产出（实现代码 + 审查结论），整理后回复用户

用户：Kafka 还是 RabbitMQ 更适合我们？
主 Agent：→ 调用 invoke_discussion(message="Kafka 还是 RabbitMQ 更适合我们？")
          → 返回讨论收敛结论，回复用户
```

每个 Agent 的模型独立配置：在 `agents.json` 中为 Agent 指定 `model` / `apiKey`（用 `${VAR}` 引用 `.env` 变量），未配置的字段自动继承默认值。`.env` 示例：

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

> 注：旧 `{mode}-agents.json` 与 `--agent.mode` 参数已废弃，不再生效。

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

MCP Server 配置独立在 `mcp-server.json`（与 Cursor / Claude 的 mcp.json 格式一致），**加载优先级：运行目录（user.dir）下的 `mcp-server.json`（命中即用，不再读取内置）> classpath 默认模板**。支持 stdio 与 streamable_http 两种传输：

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
| 命令白名单   | 允许的 Shell 命令，**按命令段逐段校验**（引号感知切分，防 `ls; rm -rf` / `&&` 拼接绕过）    |
| 命令黑名单   | 21 个危险模式：`rm -rf /`、`sudo`、`mkfs`、fork bomb、`chmod 777` 等 |
| 审批模式    | 三档：`auto`（自动执行）/ `ask`（命中规则弹 Y/N 确认，默认）/ `read-only`（拒绝）；50+ 高风险规则（`git push`、`rm`、`npm install`、`npm cache`、`curl -X` 等）；headless / 无审批器时安全拒绝 |
| 长时任务    | 前台超时**不再强杀**，转为后台任务返回 taskId；`shell_status` 工具可查询状态 / 取输出 / 终止 |
| 路径限制    | `FileTool` 和 `ShellTool` 仅允许在配置的 `workspace-dir` 内操作      |
| 超时控制    | 工具执行 30 秒超时，超时后转后台续跑                                   |
| 输出截断    | 工具输出限制 10000 字符，防止撑爆上下文（截断前先脱敏）                 |
| 敏感信息脱敏 | shell 输出与工具入参中的密钥（`sk-` / `api_key=` / `token:` / `Bearer` / `AKIA`）自动打码 |
| HTTP 限制 | 可配置允许的 host 列表，阻止 SSRF                                    |

所有安全违规均捕获为 `SecurityException`，返回 `ToolResult.error("安全拦截: ...")`，不会中断 ReAct 循环。

## 八、技术选型

| 维度       | 选型                                                  | 说明                               |
| -------- | --------------------------------------------------- | -------------------------------- |
| 框架       | Spring Boot 2.7 + COLA 5.0                          | DDD 分层架构                         |
| LLM 调用   | OkHttp + OpenAI 兼容 API                              | 统一 Chat Completions 接口           |
| 流式输出     | SSE (SseEmitter) + WebSocket (TextWebSocketHandler) | Token 级实时推送                      |
| 工具协议     | MCP (Model Context Protocol)                        | stdio / streamable\_http（SSE 兼容） |
| Shell 终端 | JLine 3.20                                          | ANSI 着色、命令历史、行编辑、多行输入、Tab 补全                 |
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

### 新增技能（Skill）

无需写代码：在技能根目录（默认 `${user.dir}/skills`，或 `agent.skills-dir` 指定）放一个目录，包含 `SKILL.md`（YAML frontmatter + Markdown 指令）：

```markdown
---
name: my-skill
description: 技能做什么 + 何时使用（触发词）。如：生成项目周报。当用户要求生成周报 / 日报时使用。
---

# 技能标题

## 工作流
1. ...
```

- `name` 必须与目录名一致（kebab-case），`description` 是触发信号（what + when + 触发词）；
- 正文建议 ≤ 500 行，长文/脚本/模板拆入 `resources/` 子目录，正文用 `$SKILL_DIR` 引用其绝对路径；
- 重启应用 → 日志输出「已加载技能 [n]」→ 对话中匹配 `description` 场景时，LLM 自动调用 `use_skill` 按需加载；
- 技能执行仍走工具沙箱（shell 白名单 / 路径限制 / 超时 / 截断），无特权提升。

### 新增 LLM Provider

实现 `LlmGateway` 接口的 `chat()` 和 `streamChat()` 方法，替换或扩展 `LlmGatewayImpl`。

### 测试

```bash
# 运行长期记忆测试
mvn test -pl mwb-ai-claw-infrastructure -Dtest=MemoryFilePersistenceTest
```

