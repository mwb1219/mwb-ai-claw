---
title: 文档中心
---

# mwb-ai-claw 文档中心

> 面向开源用户的文档总览。按**使用指南 → 设计概要 → 速查参考**三层组织，
> 首次使用请从「快速开始」进入。
>
> mwb-ai-claw 是一个 **Java Agent Harness**：它提供执行循环、工具安全沙箱、分层记忆、会话与多租户、可观测性，以及配置化的 Agent/编排定义——是一台装好的 Agent 运行时，而非需自行组装的模型库。
>
> 🌐 English version: [English](en/index.md)

## 快速开始（3 分钟）

- [guide/quick-start.md](guide/quick-start.md) — 从零跑通首轮对话（安装 → 配置密钥 → Shell/Web 双模式）

---

## 🚀 示例项目（Examples） {#examples}

仓库内置三个可运行示例模块，覆盖从「单点能力演示」到「多扩展点联合」：

| 示例模块 | 演示重点 | 启动方式 |
| --- | --- | --- |
| [example-web](https://github.com/mwb1219/mwb-ai-claw/tree/master/example-web) | RAG 知识库管理 + Web 控制台前端；演示 RAG 扩展 SPI 的替换与增强（`ExampleRagChunker` / `ExampleRagReranker`） | 后端 `spring-boot:run` + `example-web-frontend` dev server |
| [example-embed](https://github.com/mwb1219/mwb-ai-claw/tree/master/example-embed) | 嵌入式集成：`ClawRuntime` 最小接入 | 直接运行 main |
| [example-commerce](https://github.com/mwb1219/mwb-ai-claw/tree/master/example-commerce)（推荐） | **多扩展点旗舰示例**：电商/营销运营助手，一个业务流联合演示自定义工具、自定义编排、多租户、RAG、审批门禁；含前端页面与操作截图 | 后端 `spring-boot:run` + `example-commerce-frontend`（端口 5174） |

> 💡 首次体验推荐 `example-commerce`：从「查商品 → 看活动 → 生成促销方案」串联起框架绝大部分扩展点。
> 各扩展点「默认实现 / SPI / 如何覆盖 / 如何增强」的对照说明见模块 README。

---

## 📘 使用指南（guide/）

面向「想用起来」的用户。按真实使用路径组织：装起来 → 配起来 → 用起来。

| 文档 | 内容 | 适合 |
| --- | --- | --- |
| [install.md](guide/install.md) | 安装与运行：源码 / 二进制包 / 安装脚本 / 双模式启动 | 首次安装 |
| [configuration.md](guide/configuration.md) | 配置详解：`.env`、`application.yml`、三级加载优先级、`STORAGE_TYPE` 等环境变量 | 需要调配置 |
| [shell-usage.md](guide/shell-usage.md) | Shell 模式：斜杠命令 / 启动参数 / 多模态图片 / headless | 终端用户 |
| [web-usage.md](guide/web-usage.md) | Web 模式：启动、REST / WebSocket / SSE 接口、鉴权、前端示例 | 服务端部署 |
| [embedding.md](guide/embedding.md) | 嵌入式集成：`ClawRuntime`（含流式 chat、多租户 scope） | Java 应用集成方 |
| [agents-config.md](guide/agents-config.md) | Agent 注册表 `agents.json` + 编排注册表 `orchestrations.json` | 扩展 Agent/编排 |
| [skills.md](guide/skills.md) | 技能系统：目录结构、`SKILL.md` 规范、三级加载 | 想加技能 |
| [mcp.md](guide/mcp.md) | MCP 工具接入：stdio / streamable_http、`mcp-server.json` | 接入外部工具 |

## 🏗️ 设计概要（design/）

面向「想理解原理」的用户。每篇讲清一个子系统的模型与关键决策，不展开实现细节。

| 文档 | 内容 |
| --- | --- |
| [architecture.md](design/architecture.md) | 总体架构：DDD 分层、模块依赖、Spring 装配流程 |
| [core-loop.md](design/core-loop.md) | ReAct 推理循环：思考 → 行动 → 观察的迭代执行 |
| [collaboration.md](design/collaboration.md) | 多 Agent 编排：routing / conversational / delegate 三种模式 |
| [memory-model.md](design/memory-model.md) | 分层记忆：五层模型、动态换页、检索召回 |
| [rag.md](design/rag.md) | RAG 检索增强：独立知识写入 / 索引 / 检索 / 上下文注入 |
| [storage-multitenancy.md](design/storage-multitenancy.md) | 存储与多租户：file/db 后端、AgentScope 数据隔离 |
| [security.md](design/security.md) | 安全模型：工具沙箱、审批、鉴权、防注入 |
| [observability.md](design/observability.md) | 可观测性与韧性：指标、运行记录、重试/降级 |

## 📑 速查参考（reference/）

| 文档 | 内容 |
| --- | --- |
| [rest-api.md](reference/rest-api.md) | REST API 一览（路径 / 参数 / 响应） |
| [websocket.md](reference/websocket.md) | WebSocket 事件协议（请求 / 事件流） |
| [config-full.md](reference/config-full.md) | 全部配置项速查表 |
| [shell-commands.md](reference/shell-commands.md) | Shell 斜杠命令速查 |

---

## 文档约定

- 语言：中文为第一语言；完整英文镜像见 [English](en/index.md)
- 所有配置项、命令、API 路径以**当前代码**为准，如发现不一致请提 Issue
- 贡献：欢迎补充/修正文档，遵循 [CONTRIBUTING](https://github.com/mwb1219/mwb-ai-claw/blob/master/CONTRIBUTING.md)（若已建立）
