---
title: 服务端集成（Spring Boot Starter）
parent: 使用指南
nav_order: 10
---

# 服务端集成（Spring Boot Starter）

> 面向服务端开发者：在自己的 Spring Boot 应用中引入 Starter，一行依赖获得 Agent 全部能力
> （REST / SSE / WebSocket / Shell、记忆、工具调用、多 Agent 编排）。
> 无 Web 容器的 JVM 应用场景请见 [嵌入式集成（ClawRuntime）](embedding.md)。

## 1. 引入依赖

> 已发布至 Maven Central（`io.github.mwb1219`，要求 JDK 8+），版本可在 [search.maven.org](https://search.maven.org/search?q=g:io.github.mwb1219) 查询。

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 2. 自动装配

- [ ] 引入依赖即自动装配（`ClawAutoConfiguration`），无需额外注解或配置类
- [ ] 默认装配：`infrastructure`（记忆 / LLM / 工具 / 编排 / 存储）、`agent`（应用层用例）、`web`（REST / SSE / WebSocket，`web` profile 激活）、`shell`（CLI，`shell` profile 激活）
- [ ] 覆盖默认实现：声明同名类型的 `@Bean` / `@Component` 即可（如自定义 `MemoryPageStore` / `LlmGateway`）
- [ ] 存储类型：`agent.storage.type`（`file` | `db`），默认文件存储

## 3. 能力端点

- [ ] REST：`POST /agent/chat`（同步）/ `GET /agent/chat/stream`（SSE 流式）
- [ ] WebSocket：`/ws/agent`（事件协议见 [WebSocket 速查](../reference/websocket.md)）
- [ ] Shell：CLI 斜杠命令（`shell` profile 进入 REPL）

## 4. 配置

- [ ] `.env`（运行目录 → 安装目录）或 Spring 配置（`agent.*` 前缀）
- [ ] 至少配置 LLM 密钥 `DEFAULT_API_KEY`
- [ ] 完整配置项见 [配置详解](configuration.md)

## 5. 版本兼容

- [ ] Spring Boot 2.7.x、JDK 8+
- [ ] 默认端口 8080（`server.port` 可调整）

## 6. 与嵌入式集成对比

| 维度 | 服务端集成（Starter） | 嵌入式集成（ClawRuntime） |
| --- | --- | --- |
| 场景 | 已有 Spring Boot 服务端应用 | 任意 JVM 应用（无需 Web 容器） |
| 引入 | `mwb-ai-claw-spring-boot-starter` | `mwb-ai-claw-app` |
| 能力 | 全部（含 REST / WebSocket / Shell 端点） | 编程式调用（无 HTTP 端点） |
| 装配方式 | `ClawAutoConfiguration` 自动装配 | `ClawRuntime.builder()` 手动构建 |

---

相关：[嵌入式集成](embedding.md) ｜ [Web 模式使用](web-usage.md) ｜ [配置详解](configuration.md)
