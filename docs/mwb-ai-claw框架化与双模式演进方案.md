# mwb-ai-claw 框架化与双模式演进方案

> 本文档基于对项目现状的完整走查（模块结构、领域模型、基础设施实现、编译状态），梳理 mwb-ai-claw 从「单体应用」演进为「Java Agent Harness 框架」的目标、差距、架构方案与路线图。
>
> 状态：草案（供评审）

---

## 1. 项目定位与目标

### 1.1 愿景

提供一个 **Java 版的 Agent Harness 框架**，将以下核心能力全部**插件化**：

| 维度 | 插件化目标 |
| ---- | ---------- |
| 记忆（Memory） | 存储实现可替换（文件 / Redis / JDBC / 向量库），策略可插拔（换页 / 检索 / 提炼） |
| 编排（Orchestration） | `AgentOrchestrator` SPI，新增编排方式零主链路改动 |
| 工具（Tool） | `ToolExecutor` SPI + MCP 协议，业务方只写工具实现 |
| LLM（Model） | `LlmGateway` 端口，多 Provider 适配（OpenAI 兼容 / Anthropic / Gemini / 本地 Ollama） |

**核心诉求：让使用者尽可能少的开发即可适配其业务场景。**

### 1.2 双模式定位

框架同时支持两种运行形态，共享同一套领域核心：

- **服务端 AI 应用**：以 Spring Boot Starter 形式嵌入后端服务，提供 REST / SSE / WebSocket 多渠道接入，面向多用户、多租户、高并发场景。
- **客户端 AI 应用**：以嵌入式核心 + CLI 形式运行（CLI 或嵌入其他 JVM 应用），本地优先、单用户、本地文件持久化、支持本地工具与本地 LLM（Ollama）。

### 1.3 可行性结论

**可行。** 当前架构（DDD + 整洁架构 + 六边形端口适配器）已经为插件化奠定了正确的基础：

- domain 层**零框架依赖**（无 Spring / DB / Web 依赖），依赖倒置成立；
- 端口齐全：`LlmGateway`、`ToolGateway` / `ToolExecutor`、`LayeredMemoryGateway`、`AgentOrchestrator`、`OrchestrationSelector`、`PageEvictionPolicy`、`MemoryRetriever` 均为 SPI；
- 核心能力已实现并通过编译：ReAct 循环、流式输出、三种编排（routing / pipeline / conversational）、五层记忆 + RAG、MCP 客户端、多渠道接入。

真正的差距不在「能否插件化」，而在于 **它目前是一个单体应用而非可被引用的框架依赖**，以及 **服务端生产化能力缺失**。

> **关键决策**：客户端形态限定为「CLI / 嵌入其他 JVM 应用」，两种模式均允许使用 Spring 上下文（Spring 可嵌入式运行），因此**无需**将核心重构为纯 Java 无 Spring 的形态，工作量显著降低。

---

## 2. 现状盘点（已具备的架构资产）

### 2.1 架构骨架

```
adapter / app / infrastructure → client + domain（依赖方向严格外→内）
domain 零 Spring 依赖，infrastructure 实现端口（依赖倒置）
```

六模块：`client`（客户端 SDK） / `adapter`（输入适配器） / `app`（应用层用例） / `domain`（领域核心） / `infrastructure`（输出适配器实现） / `start`（启动应用）。

### 2.2 已实现能力

| 领域 | 能力 | 关键实现 |
| ---- | ---- | -------- |
| 核心域 | ReAct 推理循环（同步 / 流式）、Session 聚合根、多 Agent 路由 | `ReActLoopService` / `Session` / `AgentRouter` |
| 编排域 | 编排 SPI、意图选择、路由 / 流水线 / 对话式三种编排 | `AgentOrchestrator` / `RoutingOrchestrator` / `PipelineOrchestrator` / `ConversationalOrchestrator` |
| 记忆域 | 五层记忆、Token 预算、动态换页、事实提炼、混合检索 RAG、跨会话档案 | `LayeredMemoryGateway` / `HybridMemoryRetriever` / `VectorMemoryRetriever` |
| 工具域 | 内置工具（file/shell/http/memory）、MCP 协议（stdio / streamable_http）、安全沙箱 | `ToolExecutor` / `McpClientManager` / `ToolSecurity` |
| LLM 域 | OpenAI 兼容 API（同步 / 流式 SSE）、Embedding、独立模型配置 | `LlmGatewayImpl` / `OpenAiEmbeddingGateway` |
| 多渠道 | REST / SSE / WebSocket / Shell REPL / 前端控制台 | `AgentController` / `AgentWebSocketHandler` / `AgentShell` |
| 配置工程 | agents.json / orchestrations.json / mcp-server.json 外部化，`.env` 密钥注入 | `AgentRegistryLoader` / `OrchestrationConfigLoader` / `DotenvEnvironmentPostProcessor` |

---

## 3. 差距分析

### 3.1 P0 结构性差距（决定「框架」vs「应用」）

1. **不是框架，是应用**
   - 现状：所有入口（Controller / WebSocket / Shell）与配置模板耦合在 `start` 模块，使用者只能 fork 仓库或复制 start。
   - 目标：拆出 `mwb-ai-claw-spring-boot-starter`（自动装配 + `spring.factories` + `@ConditionalOnMissingBean`）+ 客户端嵌入式核心，另建示例工程模板。
2. **默认实现不可方便替换**
   - 现状：基础设施类全部 `@Component` 直接注册，无条件装配兜底；`Application.java` 硬编码 `scanBasePackages = com.mwb.ai.claw`。
   - 目标：改为自动装配 + `@ConditionalOnMissingBean`，用户可覆盖默认实现（记忆 / LLM / 工具 / 编排）。
3. **无多租户 / 用户维度**
   - 现状：Session 与记忆（`facts.jsonl`、`pages/`）仅 sessionId 维度，多用户互相污染。
   - 目标：增加 userId / tenantId 命名空间（记忆目录、会话 key、检索隔离）。

### 3.2 P1 服务端生产化差距

| 项 | 现状 | 目标 |
| ---- | ---- | ---- |
| 持久化 | 仅文件系统，并发不安全（同 session 并发写损坏）、不可横向扩展 | 端口已存在（`SessionGateway` / `MemoryPageStore`），补 JDBC / Redis 实现 + session 级锁 |
| 可观测性 | 无指标、无 trace、无用量记录 | Micrometer（token / 延迟 / 成本指标）、结构化日志、每次运行用量与成本记录 |
| 流式可靠性 | SseEmitter 无超时 / 断连清理，无客户端取消 | 客户端取消 → 中断 LLM 调用、断连回收、重试 / 退避（429 / 5xx） |
| 认证鉴权 | REST API 无鉴权 | API Key / Token 认证、租户级权限、工具级权限（谁能用 shell） |
| 韧性 | LLM 失败无 fallback | 备用模型路由、token 预算保护、提示词注入防护 |
| 测试 | 全仓仅 1 个测试文件 | SPI 契约测试、集成测试、示例工程端到端测试、CI |

### 3.3 P2 能力增强（可后置）

- **LLM 抽象仅限 OpenAI 兼容**：需适配 Anthropic / Gemini / 本地 Ollama（工具调用协议各厂不同），是「多模型插件化」的关键落点；
- **结构化输出 / 多模态**：JSON mode、严格 schema、视觉输入；
- **模板系统**：pipeline `promptTemplate` 仅支持 `{input}` 占位符，业务场景需要更丰富的模板变量与条件；
- **技术栈老化**：Java 8 + Spring Boot 2.7，新框架建议 Java 17/21 + Spring Boot 3.x（移植工作量最大的单项）。

---

## 4. 双模式架构方案

### 4.1 总体视图：同一核心，两套运行时

```
                    ┌─────────────────────────────────────────────┐
                    │  共享核心（domain + app + core-infrastructure）│
                    │  ReAct / 编排 / 分层记忆 / 工具 / LLM / 配置    │
                    │  文件持久化（两模式通用）                       │
                    └─────────────────────────────────────────────┘
               ▲                                        ▲
   服务端适配器（starter）                       客户端适配器（embed）
   REST/SSE/WS Controller                 AgentShell（CLI，已有）
   多租户 / DB+Redis 持久化                  ClawRuntime Builder API（新增）
   鉴权 / 指标 / 流式韧性                   本地工具 + Ollama 本地推理
```

### 4.2 两种模式不是两套代码，是两套适配器集合

| 维度 | 服务端运行时 | 客户端运行时 |
| ---- | ------------ | ------------ |
| 存储 | Redis / JDBC 实现（新增） | 现有文件实现（`FileBasedSessionGateway` 等） |
| 传输 | REST / SSE / WebSocket | 本地回调（`LlmStreamCallback` / `ProgressCallback`，已有） |
| LLM | 云端 API + 沙箱工具 | 云端 API + 本地 Ollama（OpenAI 兼容，复用 `LlmGatewayImpl`） |
| 工具 | 沙箱化 + 审批流 | 本地 file / shell / 浏览器控制 |
| 鉴权 | API Key / 租户级权限 | 不需要 |
| 多用户 | 多租户隔离 | 单用户 |

### 4.3 模块调整方案

| 模块 | 现状 | 调整 |
| ---- | ---- | ---- |
| `domain` / `app` / `client` | 已有 | **不变**，作为共享核心 |
| `infrastructure` | 已有 | 拆分出**两模式通用核心**（llm / tool / memory / 编排 / 文件存储），不依赖 Web 容器 |
| `adapter` | Controller 与 Shell 混在一起 | 按运行时归位：Web 适配器 → 服务端；`AgentShell` → 客户端 |
| `spring-boot-starter` | 无 | **新增**：服务端自动装配（`@ConditionalOnMissingBean` 可替换实现） |
| `start` | 服务端应用 | 保留为服务端示例 + shell profile 保留为 CLI 示例 |
| 客户端示例 | 无 | **新增**：嵌入式调用示例（其他 JVM 应用如何接入 `ClawRuntime`） |

### 4.4 各模式关键交付物

**服务端（Spring Boot Starter）——「让使用者尽量少开发」**：
1. 一行依赖引入，`agents.json` / `orchestrations.json` / `mcp-server.json` 外部化配置；
2. 业务方只需编写自己的 `ToolExecutor` 与编排定义；
3. 生产化能力：多租户命名空间、DB / Redis 持久化实现、鉴权、指标、流式取消。

**客户端（嵌入式 + CLI）**：
1. `ClawRuntime` 编程入口（核心新增工作）：无 Web 容器、可嵌入式启动，供其他 JVM 应用直接调用：
   ```java
   ClawRuntime runtime = ClawRuntime.builder()
           .withLlm(openAiGateway)
           .withMemory(fileMemory)
           .withTools(fileTool, shellTool, myBusinessTool)
           .build();
   String reply = runtime.chat("你好");
   runtime.streamChat("你好", streamCallback);
   ```
2. CLI（现有 `AgentShell`）作为客户端运行时的参考实现；
3. 客户端自有能力：本地文件记忆（已有）、本地工具 file/shell（已有）、可选 Ollama 本地推理（复用 `LlmGatewayImpl` 的 OpenAI 兼容能力）。

---

## 5. 路线图

### Phase A：框架化改造（结构性）

- [ ] 拆出 `mwb-ai-claw-spring-boot-starter`（自动装配 + `spring.factories` + `@ConditionalOnMissingBean`）
- [ ] 新增 `ClawRuntime` 客户端嵌入式入口（无 Web 容器启动）
- [ ] 示例工程模板：服务端示例 + 客户端嵌入式示例
- [ ] Maven 发布准备（坐标、版本、源码/javadoc 插件）

### Phase B：服务端生产化

- [ ] 多租户 / 用户维度（记忆命名空间、会话 key、检索隔离）
- [ ] JDBC / Redis 持久化实现（Session / MemoryPageStore / Facts）
- [ ] session 级并发锁（文件模式并发安全）
- [ ] 认证鉴权（API Key / Token、租户级权限、工具级权限）

### Phase C：可观测与韧性

- [ ] Micrometer 指标（token / 延迟 / 成本）+ 结构化日志 + 每次运行用量记录
- [ ] 流式取消 / 断连回收、LLM 重试与退避（429 / 5xx）
- [ ] LLM 备用模型 fallback、token 预算保护、提示词注入防护
- [ ] 测试补齐（SPI 契约测试、集成测试、端到端测试）与 CI

### Phase D：模型与生态

- [ ] 多 Provider 适配（Anthropic / Gemini / 本地 Ollama）
- [ ] 结构化输出（JSON mode / 严格 schema）、多模态输入
- [ ] 模板系统增强（模板变量 / 条件、产物结构化解析）
- [ ] 技术栈升级：Java 17/21 + Spring Boot 3.x

---

## 6. 决策点与假设记录

| 决策点 | 结论 | 说明 |
| ---- | ---- | ---- |
| 客户端形态 | CLI / 嵌入其他 JVM 应用 | 已确认；两者均允许 Spring 上下文，无需纯 Java 无 Spring 重构 |
| 客户端是否需要移动端 | 暂不包含 | 若未来支持 Android/iOS，需将客户端核心重构为纯 Java（P2+ 级工作） |
| 默认技术栈 | Java 8 + Spring Boot 2.7（现状） | 新框架对外推广建议升级 Java 17/21 + SB 3.x，列入 Phase D |
| 多租户必要性 | 服务端必做 | 客户端单用户不需要 |
| 纯 Java 非 Spring 宿主 | 后置 | 仅当遇到非 Spring 宿主时才需要手动装配模块 |
