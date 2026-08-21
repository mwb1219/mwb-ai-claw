# 快速开始

> 面向首次使用者：3 分钟跑通「安装 → 配置 → 与 Agent 首轮对话」。
> 完整配置见 [configuration.md](configuration.md)，安装细节见 [install.md](install.md)。

## 1. 环境要求

| 模式 | 要求 |
| --- | --- |
| 源码运行 | JDK 8+、Maven 3.6+ |
| 二进制分发包 | 仅 JDK 8+（无需 Maven / 源码） |

## 2. 选择一种方式运行

### 方式一：源码构建 + Shell 模式（推荐先试）

```bash
# 1. 构建（编译 + 打包 start 可执行 jar）
mvn package -pl start -am -DskipTests

# 2. 准备密钥：复制模板并填入你的 LLM API Key
cp .env.example .env
#    编辑 .env，至少填写 DEFAULT_API_KEY=sk-xxx（默认模型 deepseek-chat）

# 3. 启动 Shell 终端（交互式 REPL）
java -jar start/target/start-*.jar --spring.profiles.active=shell
```

进入交互界面后直接输入问题即可对话，例如：

```text
> 你好，介绍一下你自己
```

### 方式二：安装为全局命令（类 `claude`）

```bash
./tools/install.sh          # 构建并安装到 ~/.mwb-ai-claw，软链到 PATH
mwb-ai-claw                 # 任意目录直接进入 Agent Shell
```

> 首次安装后编辑 `~/.mwb-ai-claw/.env` 填入 `DEFAULT_API_KEY`。
> 支持参数透传，如 `mwb-ai-claw --agent.orchestration=todo-delegate`。

### 方式三：二进制分发包（给无源码环境）

```bash
./tools/package.sh          # 产出 dist/mwb-ai-claw-<version>-bin.tar.gz
# 分发后，解压 → cd mwb-ai-claw-<version>-bin → ./install.sh → 编辑 ~/.mwb-ai-claw/.env → mwb-ai-claw
```

## 3. 体验 Web 模式（可选）

```bash
java -jar start/target/start-*.jar --spring.profiles.active=web
# 浏览器访问 http://localhost:8080
```

Web 模式提供：REST 对话、SSE 流式、WebSocket、会话管理、前端控制台。

## 4. 首轮对话做了什么

1. 读取配置：`.env`（运行目录 → 安装目录）→ 环境变量 → 内置默认
2. 启动 Spring 上下文，装配：Agent 注册表、编排、工具（内置 + MCP）、记忆、技能
3. 请求按默认编排 `routing` 交给 Agent，执行 ReAct 循环调用 LLM

## 5. 常见问题

| 现象 | 处理 |
| --- | --- |
| 报 `401 / Unauthorized` | `.env` 未填或填错 `DEFAULT_API_KEY` |
| 报 `Connection refused`（3306） | `.env` 中配置了 `DB_*` 变量但 MySQL 未启动；默认 H2 无需数据库，注释掉 `DB_*` 即可 |
| 端口占用 | Web 模式换端口：`java -jar start-*.jar --server.port=9090` |
| 想切换模型 | `.env` 设 `DEFAULT_MODEL`、`DEFAULT_BASE_URL`（任意 OpenAI 兼容服务） |

---

下一步：[安装与运行](install.md) ｜ [配置详解](configuration.md) ｜ [Shell 模式](shell-usage.md)
