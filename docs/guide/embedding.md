---
title: 嵌入式集成（ClawRuntime）
parent: 使用指南
nav_order: 6
---

# 嵌入式集成（ClawRuntime）

> 面向 Java 应用集成方：在无 Web 容器的 JVM 应用中直接调用 Agent 能力。
> 完整示例见 `example-embed` 模块。

## 1. 引入依赖

> 已发布至 Maven Central（`io.github.mwb1219`，要求 JDK 8+），版本可在 [search.maven.org](https://search.maven.org/search?q=g:io.github.mwb1219) 查询。

- [ ] **核心模块**（ClawRuntime 嵌入入口，无 Web 容器也可运行）：

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-app</artifactId>
    <version>1.0.0</version>
</dependency>
```

- [ ] **或使用 Spring Boot Starter**（服务端自动装配：REST / WebSocket / Shell 等全部能力）：

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

> 按需可单独引入 `mwb-ai-claw-client`（客户端 API）、`mwb-ai-claw-domain`（领域模型）等模块。
> 源码或本地仓库方式：`mvn install` 后可省去 `<version>`（由父 POM 统一管理）。

## 2. 构建运行时

- [ ] `ClawRuntime.builder()` 链式配置：`apiKey` / `model` / `baseUrl`
- [ ] `config(key, value)` 注入任意 `agent.*` 属性
- [ ] `register(Class)` 注册自定义组件覆盖默认实现
- [ ] `build()` 启动（内部启动嵌入式 Spring 上下文，不占端口）

## 3. 对话

- [ ] 同步：`chat(message)` / `chat(sessionId, message)` / `chat(ChatCmd)`
- [ ] 流式：`chatStream(..., LlmStreamCallback)`（onToken / onToolName / onToolArguments / onComplete / onError）
- [ ] 返回值：`SingleResponse<ChatResponseDTO>`，`isSuccess()` / `getData()`

## 4. 多租户

- [ ] 所有接口提供 `AgentScope` 重载：`chat(message, AgentScope.of("租户","用户"))`
- [ ] scope 隔离会话 / 记忆 / 缓存，调用线程内生效自动清理

## 5. 会话管理

- [ ] `createSession` / `getSession` / `listSessions` / `deleteSession`

## 6. 生命周期

- [ ] 用毕必须 `runtime.close()` 释放上下文（try-with-resources）

## 7. 配置加载

- [ ] `.env`（运行目录 → 安装目录）→ 系统环境变量 → 内置默认
- [ ] 复用 `ConfigFileLocator.readConfigFile(".env")`（见 example-embed）

---

相关：[快速开始](quick-start.md) ｜ [配置详解](configuration.md) ｜ 示例代码 `example-embed/src/main/java/.../EmbedDemo.java`
