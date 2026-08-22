---
title: 快速开始
parent: 使用指南
nav_order: 1
---

# 快速开始

> 面向首次使用者：3 分钟跑通「安装 → 配置 → 与 Agent 首轮对话」。
> 完整配置见 [configuration.md](configuration.md)，安装细节见 [install.md](install.md)。

## 1. 环境要求

| 模式 | 要求 | 对应方式 |
| --- | --- | --- |
| 源码构建 | JDK 8+、Maven 3.6+ | 方式一 / 三 |
| 二进制分发包 | 仅 JDK 8+（无需 Maven / 源码） | 方式二 |

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

### 方式二：下载二进制分发包（推荐新手 / 无源码环境）

```bash
# 1. 从 GitHub Releases 下载最新安装包（无需 Maven / 源码，仅需 JDK 8+）
#    下载页：https://github.com/mwb1219/mwb-ai-claw/releases
#    资产：mwb-ai-claw-<version>-bin.tar.gz（当前 v1.0.0，约 26MB）

# 2. 解压并安装为全局命令（包内已含预构建 jar，install.sh 直接安装，无需 mvn）
tar -xzf mwb-ai-claw-1.0.0-bin.tar.gz
cd mwb-ai-claw-1.0.0-bin
./install.sh

# 3. 编辑 ~/.mwb-ai-claw/.env 填入 DEFAULT_API_KEY，然后启动 Agent Shell
mwb-ai-claw
```

> 需要自行重新打包时：`./tools/package.sh` → `dist/mwb-ai-claw-<version>-bin.tar.gz`。

### 方式三：源码构建 + 安装为全局命令（类 `claude`）

```bash
./tools/install.sh          # 源码模式下自动执行 mvn package 构建，并安装到 ~/.mwb-ai-claw（软链到 PATH）
mwb-ai-claw                 # 任意目录直接进入 Agent Shell
```

> 需要源码与 JDK 8+、Maven 3.6+ 环境。
> 首次安装后编辑 `~/.mwb-ai-claw/.env` 填入 `DEFAULT_API_KEY`。
> 支持参数透传，如 `mwb-ai-claw --agent.orchestration=todo-delegate`。
> 卸载：`./tools/install.sh --uninstall`；安装根目录可用环境变量 `MWB_AI_CLAW_HOME` 覆盖（默认 `~/.mwb-ai-claw`）。

#### tools/ 脚本速查（源码环境常用）

| 脚本 | 用途 |
| --- | --- |
| `install.sh` / `install.ps1` | 安装为全局命令。源码模式自动 `mvn package` 构建；解压二进制包后的 `install.sh` 直接用包内 jar，无需 mvn |
| `package.sh` / `package.ps1` | 打二进制分发包：`dist/mwb-ai-claw-<version>-bin.tar.gz`（`--skip-build` 可复用已构建 jar） |
| `setup.sh` / `setup.ps1` | 维护者一键「构建 + 打包 + 安装」全流程验证（`--skip-build` 跳过 Maven 构建） |
| `smoke.sh` | E2E 冒烟测试：5 个场景（echo / fail / tool / anthropic / gemini），自动启动 mock LLM 校验 |
| `mock_llm.py` | smoke.sh 配套的 mock LLM 服务器（`python3 tools/mock_llm.py --port 19996 --mode echo`） |
| `ci.sh` | 全量 CI：`mvn clean test` + 打包（与 GitHub Actions 流程一致） |

> 完整配置说明见 [CONFIG-GUIDE.md](https://github.com/mwb1219/mwb-ai-claw/blob/master/CONFIG-GUIDE.md)（密钥 / Agent / 编排 / MCP / 技能）。

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
