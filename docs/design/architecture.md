---
title: 总体架构
parent: 设计概要
nav_order: 1
---

# 总体架构

> 面向想理解原理的读者：本项目如何分层、模块如何组织、运行时如何装配。
> 涉及代码实现的功能细节见各设计文档（core-loop / collaboration / memory-model 等）。

## 1. 分层模型（COLA / DDD）

```
┌─────────────────────────────────────────────┐
│  adapter（适配层）                            │
│  AgentController(REST/SSE) / WebSocket /    │
│  AgentShell —— 协议转换，转发到 app 层          │
└──────────────────────┬──────────────────────┘
                       ▼
┌─────────────────────────────────────────────┐
│  app（应用层）                                │
│  AgentServiceImpl / ChatCmdExe（编排选择+分发）│
│  ClawRuntime（嵌入式入口，无 Web 容器）          │
└──────────────────────┬──────────────────────┘
                       ▼
┌─────────────────────────────────────────────┐
│  domain（领域层）                             │
│  聚合：Session / Agent / Message              │
│  领域服务：ReActLoopService                   │
│  Gateway 接口：Llm / Tool / Memory / Agent    │
│  collaboration：编排 SPI + ExecutionUnit      │
│  回调：ProgressCallback / LlmStreamCallback   │
└──────────────────────┬──────────────────────┘
                       ▼
┌─────────────────────────────────────────────┐
│  infrastructure（基础设施）                   │
│  LlmGatewayImpl / ToolGatewayImpl / MCP      │
│  记忆实现 / 编排实现 / Agent 配置加载           │
└─────────────────────────────────────────────┘
```

**依赖方向**：`adapter / app / infrastructure` → `client + domain`；`domain` 不依赖任何下层。

## 2. 模块结构（Maven 多模块）

| 模块 | 职责 |
| --- | --- |
| `mwb-ai-claw-client` | 客户端 SDK：`AgentServiceI` 接口、DTO（ChatCmd / SessionDTO / SingleResponse） |
| `mwb-ai-claw-domain` | 领域层：聚合、领域服务、Gateway 接口、值对象 |
| `mwb-ai-claw-infrastructure` | 基础设施：LLM / 工具 / MCP / 记忆 / 编排 / 配置加载实现 |
| `mwb-ai-claw-adapter` | 适配层：REST / SSE / WebSocket / Shell 终端 |
| `mwb-ai-claw-app` | 应用层：用例执行器（ChatCmdExe 等）、`ClawRuntime` 嵌入式入口 |
| `mwb-ai-claw-spring-boot-starter` | Starter 自动装配入口（`ClawAutoConfiguration`） |
| `start` | 服务端示例：Web / Shell 双模式可执行应用 |
| `example-embed` / `example-web` | 嵌入式 / Web 独立示例 |

## 3. 运行时装配流程

1. 启动类引入 `ClawAutoConfiguration`（starter），其 `@ComponentScan` 扫描
   `infrastructure`、`app`、`adapter` 包；
2. `infrastructure` 的 `ClawCoreAutoConfiguration` 按条件装配各基础设施 Bean
   （如 `agent.storage.type=db` 时装配 JDBC 存储）；
3. `AgentConfiguration` 加载 `agents.json` / `orchestrations.json`，注册 Agent 注册表与编排注册表；
4. 应用层执行器（ChatCmdExe 等）按请求选择编排，驱动 ReAct 循环。

## 4. 核心扩展点（依赖倒置）

| 扩展点 | 接口 | 默认实现 | 覆盖方式 |
| --- | --- | --- | --- |
| LLM 调用 | `LlmGateway` | `LlmGatewayImpl`（OpenAI 兼容 + 多 Provider） | `@Bean` 覆盖 / `ClawRuntime.register()` |
| 工具 | `ToolGateway` / `ToolExecutor` | `ToolGatewayImpl` + 内置工具 | 新增 `@Component` 工具自动收集 |
| 记忆 | `MemoryGateway` / `LongTermMemoryGateway` | 文件 / JDBC 双实现 | 条件装配切换 |
| Agent 配置 | `AgentGateway` | `AgentGatewayImpl` | agents.json 配置 |
| 编排 | `AgentOrchestrator`（SPI） | routing / conversational / delegate | 注册 `type` 插件 |

## 5. 双模式与嵌入式

- **Web 模式**（`spring.profiles.active=web`）：完整服务端，REST / SSE / WebSocket；
- **Shell 模式**（`shell`）：JLine 交互终端，可一键安装为全局命令 `mwb-ai-claw`；
- **嵌入式**（`ClawRuntime`）：无 Web 容器，复用同一套 Bean 装配，供其他 JVM 应用集成。

> 三种入口复用完全相同的 domain / infrastructure 核心，仅适配层不同。

---

相关：README.md ｜ [ReAct 推理循环](core-loop.md) ｜ [多 Agent 编排](collaboration.md)
