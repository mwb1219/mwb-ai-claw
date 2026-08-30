# mwb-ai-claw

> 一个基于 COLA 架构（DDD）的 **Java Agent Harness** 框架。开箱即用、低开发成本、易上手 —— 各子系统零组件均可扩展替换。

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.mwb1219/mwb-ai-claw-app?color=blue)](https://search.maven.org/artifact/io.github.mwb1219/mwb-ai-claw-app)
[![Docs](https://img.shields.io/badge/docs-online-blue)](https://mwb1219.github.io/mwb-ai-claw/)

> 🌐 English version: [README.en.md](README.en.md)

## 什么是 Agent Harness？

mwb-ai-claw **不是**一个模型集成库（同类的 LangChain4j / Spring AI 会给你 `ChatClient` 一级的积木，需要你自己去组装）。它是一台 **Agent Harness** —— 装配好的运行时框架，把「模型 + 执行循环 + 工具安全 + 记忆 + 会话 + 配置化 Agent 定义 + 可观测性」全部就绪。开发者拿来就能跑，同时每一个零组件都通过 SPI 暴露，可按需替换或增强。它负责模型之外的一切：

- **执行循环** —— ReAct（思考 → 行动 → 观察）循环，带自适应步数预算与 token 保护
- **工具执行与安全** —— 沙箱化的 Shell 命令（命令白/黑名单、路径限制、超时、输出截断 + 敏感信息脱敏）、审批门禁、HTTP 白名单
- **分层记忆** —— 五层记忆模型，支持动态换页、检索召回与长期记忆提炼
- **会话与状态** —— 以会话为优先的完整 CRUD，并用多租户 `AgentScope` 做数据隔离
- **配置化 Agent 定义** —— Agent 与编排在 `agents.json` / `orchestrations.json` 里声明，而非硬编码；内核保持稳定，能力在边缘生长
- **可观测性与韧性** —— 指标、JSONL 运行记录、重试/降级、步数与 token 预算保护
- **多入口** —— Shell、Web 控制台、REST API、WebSocket（SSE 流式），以及可嵌入的 `ClawRuntime`

如果说 LangChain4j / Spring AI 是「造 harness 的零件」，那么 mwb-ai-claw 就是「已经装好的 harness」—— 一个自包含、可部署的 Agent 运行时。

## 特性

- **多入口** —— 交互式 Shell、Web 控制台、REST API、WebSocket（SSE 流式）
- **ReAct 推理循环** —— 「思考 → 行动 → 观察」迭代执行，带自适应步数预算
- **工具调用** —— 文件 I/O、沙箱化 Shell 命令、MCP 工具集成（stdio / streamable_http）
- **分层记忆** —— 五层记忆模型，支持动态换页与检索召回
- **RAG 检索增强** —— 后台知识库：上传文档 → 解析 / 切块 / 嵌入 / 建索引 → 检索（可选重排）并注入上下文，与 Agent 记忆完全独立
- **多 Agent 编排** —— routing / conversational / delegate 三种协作模式
- **技能系统（Skills）** —— 遵循 `SKILL.md` 规范的可插拔技能，三级加载
- **多租户** —— 基于 AgentScope 的数据隔离
- **存储后端** —— file（零依赖），或 db = MySQL 存储 + Redis Stack 召回（关键词 + 向量 KNN）
- **可观测性与韧性** —— 指标、JSONL 运行记录、重试/降级
- **可嵌入** —— 在你自己的 Java 应用中嵌入 `ClawRuntime`（流式对话、多租户 scope）

## 为扩展而生

扩展性是一等设计目标：核心管线（ReAct 循环、会话、记忆提炼）保持稳定，能力在边缘生长 —— 通过配置或可插拔 SPI，**无需触碰内核**。

**零代码扩展（面向用户）**

往运行目录里丢一个同名文件，即可覆盖内置默认值，无需重新打包：

| 扩展 | 你能做什么 | 位置 |
| --- | --- | --- |
| `agents.json` | 新增 / 调优专家 Agent 以及每个 Agent 独立的模型 | 运行目录或安装目录 |
| `orchestrations.json` | 定义协作模式（routing / conversational / delegate） | 运行目录或安装目录 |
| `skills/<name>/SKILL.md` | 不写代码就能给 Agent 加一个可复用技能 | `skills/` 或 `agent.skills-dir` |
| `mcp-server.json` | 通过 MCP 协议接入外部工具 | 运行目录或安装目录 |
| `.env` | 配置模型与 API Key，支持 `${VAR:default}` 引用 | 运行目录或安装目录 |

**开发者扩展（SPI）**

每个子系统都暴露领域层的 SPI，并带有开箱即用的默认实现，通过 `@ConditionalOnMissingBean` 注册 —— 声明一个同接口的 Bean 来**替换**它，或把它**包装**起来（装饰器 / 重排器）来**增强**它：

- **LLM**：`LlmGateway` / `EmbeddingGateway`
- **工具**：`ToolGateway` / `ToolExecutor`
- **记忆**：`MemoryGateway` / `MemoryPageStore` / `PageEvictionPolicy` / `MemoryRetriever`
- **编排**：`AgentOrchestrator`（新增一种协作模式）/ `ExecutionUnit`
- **RAG**：`RagDocumentParser` / `RagChunker` / `RagEmbeddingGateway` / `RagIndexStore` / `RagReranker` / ...

嵌入场景的用户通过 `ClawRuntime.Builder.register(...)` 注册自己的组件。完整全景见 [扩展能力设计](docs/design/extensibility.md)。

## 快速开始

需要 JDK 8+ 与 Maven 3.6+。

```bash
# 1. 构建 start 模块（编译 + 打可执行 jar 包）
mvn package -pl start -am -DskipTests

# 2. 准备你的 LLM API Key
cp .env.example .env
#    编辑 .env，至少设置 DEFAULT_API_KEY=sk-xxx（默认模型：deepseek-chat）

# 3. 启动交互式 Shell（REPL）
java -jar start/target/start-*.jar --spring.profiles.active=shell
```

然后直接输入你的问题即可：

```text
> 你好，请介绍一下你自己
```

更喜欢用浏览器？启动 Web 模式并访问 `http://localhost:8080`：

```bash
java -jar start/target/start-*.jar --spring.profiles.active=web
```

Web 模式提供 REST 对话、SSE 流式、WebSocket、会话管理以及一个前端控制台。

## 作为 Maven 依赖使用

已发布到 Maven Central（`io.github.mwb1219`，要求 JDK 8+）。用核心模块把 `ClawRuntime` 嵌入你自己的 Java 应用：

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-app</artifactId>
    <version>1.0.4</version>
</dependency>
```

或者使用 Spring Boot Starter 获得完整的服务端栈（REST / WebSocket / Shell）：

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-spring-boot-starter</artifactId>
    <version>1.0.4</version>
</dependency>
```

使用细节见 [嵌入式集成](docs/guide/embedding.md) 与 [服务端集成](docs/guide/server-integration.md)。

## 文档

- 中文文档站：https://mwb1219.github.io/mwb-ai-claw/
- English site: https://mwb1219.github.io/mwb-ai-claw/en/

完整的文档（快速开始、配置详解、REST / WebSocket / Shell 参考、设计概要）也维护在 [docs/](docs/) 下。

## 许可协议

基于 [Apache License, Version 2.0](LICENSE) 授权。
