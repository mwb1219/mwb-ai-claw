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
    <version>1.0.5</version>
</dependency>
```

## 2. 自动装配

- [ ] 引入依赖即自动装配（`ClawAutoConfiguration`），无需额外注解或配置类
- [ ] 默认装配：`infrastructure`（记忆 / LLM / 工具 / 编排 / 存储）、`agent`（应用层用例）、`web`（REST / SSE / WebSocket，`web` profile 激活）、`shell`（CLI，`shell` profile 激活）
- [ ] 覆盖默认实现：声明同名类型的 `@Bean` / `@Component` 即可（如自定义 `MemoryPageStore` / `LlmGateway`）
- [ ] 存储类型：`agent.storage.type`（`file` 本地 | `db` = **MySQL 权威存储 + Redis Stack 召回**），默认文件存储；`db` 形态写入时双写 Redis 召回索引，Redis 丢失可从 MySQL 重建

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

## 7. 示例项目：example-web

> 仓库 [`example-web/`](https://github.com/mwb1219/mwb-ai-claw/tree/master/example-web) 演示 Starter 使用方的完整接入：Spring Boot 服务端应用 + 用户系统（注册 / 登录 / 鉴权）+ 前端控制台。

- [ ] 覆盖场景：Starter 自动装配（REST / SSE / WebSocket）、多租户鉴权（`X-API-Key`）、存储切换（H2 联调用 / MySQL 生产）、Agent / 编排 / MCP 配置
- [ ] 关键代码：[WebApplication.java](https://github.com/mwb1219/mwb-ai-claw/blob/master/example-web/src/main/java/com/mwb/ai/claw/example/web/WebApplication.java)，配置见同目录 `src/main/resources/application.yml`
- [ ] 前端：`example-web-frontend/`（构建产物 `dist/`）
- [ ] 运行：

```bash
# 1. 准备密钥（.env 加载顺序：运行目录 → ~/.mwb-ai-claw）
cp .env.example .env        # 填入 DEFAULT_API_KEY

# 2. 构建并启动（默认 profile=web，端口 8080）
mvn -q -pl example-web -am package -DskipTests
java -jar example-web/target/example-web-*.jar

# 3. 浏览器访问 http://localhost:8080
```

- [ ] 切换存储：`--agent.storage.type=db`（H2 联调用），或设 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 接入 MySQL（先执行 `src/main/resources/schema.sql` 建表）；db 形态的召回（Memory / RAG）走 Redis Stack，需同时配置 `REDIS_URI` 并保证 Redis Stack（RediSearch）可达

---

相关：[嵌入式集成](embedding.md) ｜ [Web 模式使用](web-usage.md) ｜ [配置详解](configuration.md)
