# Shell 模式对比分析与改进建议

> 文档目的：对照 Claude Code、OpenCode、Aider、Codex CLI 等终端工具，系统梳理 mwb-ai-claw Shell 模式的差距，输出可落地的改进建议清单与路线图
> 文档编号：shell-mode-analysis
> 关联文档：`feature-skill-support技术方案(SUBMIT).md`、`mwb-ai-claw框架化与双模式演进方案.md`
> 基准日期：2026-08-18（对标准则以各工具 2026 年中公开能力为准）

> **实施状态（2026-08-18）：阶段一已实施 ✅**
> - P0-1 shell 语义：ShellTool 改经 `bash -lc`（Windows 为 `cmd /c`）执行，支持管道/重定向/通配符/`&&`/变量；白名单升级为按命令段逐段校验（`ToolSecurity.splitShellSegments`）
> - P0-2 审批模式：新增 `agent.security.shell-approval-mode`（auto/ask/read-only）+ `shell-approval-patterns` 规则；`ToolApproval` 领域接口，AgentShell 实现终端 Y/N 审批，Web 场景无审批器时安全默认拒绝
> - P0-3 长时命令：前台超时不再强杀，转为后台任务（`ShellProcessManager`）返回 taskId；新增 `shell_status` 工具（status/output/kill）；shell 支持 `background=true` 参数
> - P0-4 流式回显：`ToolExecutor`/`ToolGateway` 新增回调版 execute，ShellTool 经 `ProgressCallback` 逐行推送 `[Stream]`，AgentShell 实时绿色回显

> **实施状态（2026-08-18）：阶段三已实施 ✅**
> - P2-15 计划模式：`/plan` 切换，先让 Agent 输出方案（不执行工具），用户 y/N 确认后再执行（AgentShell）
> - P2-16 自定义斜杠命令：`~/.claw/commands/*.md`（frontmatter name/description + 模板正文，占位符 `{args}`/`{1}`…），启动加载注册、Tab 补全、/help 展示（新增 `CustomCommand` / `CustomCommandLoader`）
> - P2-17 `/mcp` 管理：`/mcp`（list）、`/mcp connect <name>`（重连）、`/mcp disconnect <name>`（断开并注销工具）；`McpClientManager` 新增 disconnectServer/reconnectServer/工具名记录，`McpToolRegistrar` 新增 unregister
> - P2-18 会话导出/分叉：`/session export <id> [path]`（JSON，默认 `~/.claw/exports/<id>.json`）、`/fork [id]`（复制为独立新会话）
> - P2-20 后台 agent：启动参数 `--bg "任务"` + `/agent list` / `/agent attach <id>`（独立线程新会话执行）
> - P2-21 状态栏：prompt 行显示会话 ID + 上下文估算 token（`≈Ntk`，带缓存）+ plan 模式标记
> - P2-19 OS 级沙箱：**未实施**（macOS seatbelt / Linux landlock 依赖系统级能力，纯 Java 无法落地；现有进程级白名单 + 审批 + 路径限制已提供纵深，建议后续用容器包装脚本实现）

> **实施状态（2026-08-18）：阶段二已实施 ✅**
> - P0-5 headless：启动参数 `--prompt/-p`（单轮非交互，纯文本输出）、`--resume <id>`、`--mode stream|sync`；stdin 为管道时自动进入 headless（`echo "问题" | mwb-ai-claw`）；非交互场景审批安全默认拒绝
> - P0-6 上下文管理：`/clear` 语义修正为「清屏 + 重置上下文（新建会话）」；新增 `/compact`（复用 `LlmMemorySynthesizer.summarizeBlock`，旧消息压缩为 system 摘要，保留最近 10 条）
> - P1-8 多行输入：``` ``` ```/`{` 开头或引号未闭合时自动续行，空行结束
> - P1-9 Tab 补全：斜杠命令 / 会话 ID（switch/delete/rename）/ 文件路径（`ShellCompleter`）
> - P1-11 `!` 快捷执行：`!npm test` 本地执行（复用 ShellTool 白名单/黑名单/审批沙箱），输出实时展示并注入上下文交 Agent 分析
> - P1-12 会话标题：首条用户消息自动生成标题（截断 20 字）；新增 `/session rename <id> <标题>`
> - P1-13 `/cost [id]`：Token 用量估算（合计/输入/输出/工具，基于 TokenEstimator）
> - P1-14 敏感信息脱敏：`ToolSecurity.maskSecrets`（sk-xxx / api_key= / token: / password= / Bearer / AKIA），应用于 shell 输出（含流式行、后台任务输出）与工具入参展示

## 1. 背景与目标

### 1.1 背景

mwb-ai-claw 已具备双模式接入：Web（REST/WebSocket）与 Shell 终端。Shell 模式由两层组成：

- **Shell 交互模式（REPL）**：`AgentShell`（JLine 终端 REPL），用于人机对话、会话管理与记忆可视化；
- **Shell 命令执行工具（ShellTool）**：LLM 调用的内置工具，在安全沙箱（白名单 + 黑名单 + 路径限制 + 超时 + 截断）内执行命令。

与 Claude Code、OpenCode、Aider、Codex CLI 等被广泛使用的终端 Agent 相比，本项目的 Shell 模式在**交互体验、上下文管理、执行语义、权限模型、自动化能力**五个维度存在明显差距。其中部分差距是"锦上添花"，但多数直接影响日常可用性与 LLM 执行效率。

### 1.2 目标

1. 客观梳理现状（现有能力清单）；
2. 以 Claude Code / OpenCode / Aider / Codex CLI 为基准，逐维度对比找出差距；
3. 输出**按优先级（P0/P1/P2）**组织的改进建议清单，标注涉及代码位置；
4. 给出分阶段落地路线图。

### 1.3 对比基准说明

| 工具 | 形态 | 开源 | 核心特色 |
|------|------|------|----------|
| Claude Code | 终端 Agent | 否（客户端未开源） | 子代理、Hook、Checkpoint、Plan Mode、CLAUDE.md、后台 agent、`-p` headless |
| OpenCode | 终端/桌面/IDE Agent | 是（MIT） | LSP 诊断反馈、多会话并行、自定义命令、Plan/Build 模式、可分享会话 |
| Aider | 终端 CLI | 是 | Git-first（每次改动自动 commit）、仓库地图、`/add` `/commit` 等 |
| Codex CLI | 终端 Agent | 是 | 三档审批模式、OS 级沙箱（Landlock/seatbelt）、`exec` 非交互、可复现 |

---

## 2. 现状梳理

### 2.1 Shell 交互模式（REPL）

核心实现：[AgentShell.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/shell/AgentShell.java)（JLine 3.x，`@Profile("shell")` 激活，配置见 [application-shell.yml](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/start/src/main/resources/application-shell.yml)）。

**已有能力清单：**

| 维度 | 能力 | 说明 |
|------|------|------|
| 对话 | 同步 / 流式双模式 | `/mode` 切换；流式按行增量渲染 Markdown |
| 会话 | new / list / switch / delete | 前缀模糊匹配；启动自动恢复上次会话 |
| 记忆 | stats / facts / summaries / archive / search | 分层记忆可视化与检索调试（`/memory`） |
| 展示 | `/trace` 观察结果完整/缩写 | 默认缩写 200 字符 |
| 渲染 | Markdown → ANSI | [MarkdownRenderer.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/shell/MarkdownRenderer.java)：标题/代码块/行内代码/加粗/引用/列表 |
| 基础 | 命令历史（`~/.mwb-ai-claw-history`）、清屏、退出 | JLine 默认能力 |

### 2.2 Shell 命令执行工具（ShellTool）

核心实现：[ShellTool.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/builtin/ShellTool.java)，安全沙箱见 [ToolSecurity.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/ToolSecurity.java)，配置见 `application.yml` 的 `agent.security.*`。

**已有能力清单：**

| 维度 | 能力 | 说明 |
|------|------|------|
| 执行 | `ProcessBuilder` 直接启动 | 自定义 `parseCommand` 仅支持双引号参数，**不经系统 shell** |
| 输出 | stdout/stderr 合并异步读取 | 守护线程逐行读取，防管道阻塞死锁；`join(2000)` 等缓冲刷完 |
| 超时 | 默认 30s，超时 `destroyForcibly()` | 可配置 |
| 安全 | 黑名单优先 + 白名单首 token 校验 | 白名单约 65 个命令、黑名单约 21 个危险片段 |
| 路径 | `resolveAndValidatePath` 限制在 workspace 内 | `toAbsolutePath().normalize()` 后 `startsWith` 校验 |
| 截断 | 输出超 10000 字符截断并标注 | 可配置 |
| 工作目录 | 可选 `workingDir` 参数 | 需在 workspace 范围内 |

---

## 3. 核心差距分析

### 3.1 REPL 交互体验

| 对比项 | Claude Code / OpenCode / Codex | mwb-ai-claw 现状 | 差距影响 |
|--------|-------------------------------|------------------|----------|
| 消息编辑 | 方向键调出上一条消息可编辑；Claude Code Esc Esc 可回退到任意历史点并重放 | 无（单行 `readLine`，历史仅"上一条再发送"） | 中：输错长 prompt 需重打 |
| 多行输入 | 支持多行粘贴/`{` 多行模式 | `readLine` 单行，多行粘贴会被截断处理 | 中：贴代码/JSON 困难 |
| Tab 补全 | 文件路径、斜杠命令、会话、@引用 | 未配置任何 `Completer` | 中：会话 ID 全靠手打或复制 |
| 思考中反馈 | spinner / 进度条 / 分层日志（Claude Code 自带展开式状态栏） | 仅"（同步等待中…）"一行 | 低 |
| 计划模式 | OpenCode Plan/Build（Tab 切换）、Claude Code Plan Mode——先出方案、确认后再改 | 无 | 高：高风险操作缺少"先看方案"环节 |
| 变更确认 | 文件编辑 diff 预览 + y/n 确认 | 无（文件由 LLM 直接改，shell 无审批） | 高 |
| 主题/配色 | OpenCode 主题、Claude Code 自定义 UI | 固定 ANSI 色 | 低 |
| 长输出分页 | 输出自动分页/可回滚 | 直接铺屏 | 低 |

### 3.2 上下文与会话管理

| 对比项 | Claude Code / Codex | mwb-ai-claw 现状 | 差距影响 |
|--------|---------------------|------------------|----------|
| 上下文压缩 | `/compact`（Claude Code）、`/repin`（Aider）自动摘要旧上下文 | 无；记忆系统只管长期记忆，不处理当前对话窗口压缩 | **高**：长会话 token 成本线性膨胀 |
| 成本/用量统计 | `/cost`、`/tokens` 显示 token 与费用 | 无 | 中：无法感知预算 |
| 上下文占用显示 | 实时显示 context 使用百分比 | 无 | 低 |
| 会话恢复 | `/resume`（按 ID/名称）、`claude -c`、`codex resume --last` | 启动自动恢复最近会话，但无手动 `/resume <name>` | 低-中 |
| 会话分叉 | `/fork` 从历史点分支新会话 | 无 | 低 |
| 会话标题/重命名 | 自动生成标题并可改 | 列表显示"（未命名）" | 中 |
| 会话导出/分享 | OpenCode 分享链接；Claude Code transcript 导出 | 无 | 低 |
| 上下文清理 | `/clear` 重置上下文开始新对话（Claude Code） | `/clear` 仅清屏，**不重置上下文**，语义名不副实 | 高：用户以为清了上下文实际没有 |

### 3.3 命令执行能力（ShellTool 语义）

| 对比项 | Claude Code / Codex | mwb-ai-claw 现状 | 差距影响 |
|--------|---------------------|------------------|----------|
| Shell 语义 | 走 `bash -lc`，完整支持管道/重定向/通配符/环境变量/`&&`/别名 | `parseCommand` 仅按空格 + 双引号切分，**无管道、重定向、通配符、变量展开** | **高**：LLM 无法表达 `grep xxx file | head`、`cd dir && mvn test`，必须拆多步或改用工具内参数，效率与成功率双降 |
| 环境继承 | 完整继承用户 shell 环境（PATH/别名/凭据/工具链） | `ProcessBuilder` 仅继承 JVM 进程环境，**不加载 shell rc**，别名/自定义脚本不可用 | 中：用户环境的 `nvm`、`alias`、自定义 PATH 丢失 |
| 实时流式输出 | 命令执行过程中逐步回显 | 全部执行完后一次性返回 | 中：长命令（构建/测试）期间无反馈，超时 30s 内像卡死 |
| 长时/后台任务 | 可超时续跑、后台执行（`--bg`）、并行 agent | 超时即 `destroyForcibly()` 强杀，无后台 | 高：编译/跑测试 >30s 必失败 |
| 交互式命令 | 支持部分交互式命令 | 无 stdin 通道（子进程 stdin 未接管） | 低：`ssh`/`vim`/`top` 不可用，但可接受 |
| 大输出 | 分页/截断 + 保存到文件 | 10000 字符截断丢弃 | 中 |
| Windows | 两套语义 | `ProcessBuilder` 原生参数数组在 Windows 下基本可用，但无 shell 语义一致化 | 低 |

### 3.4 安全与权限模型

| 对比项 | Claude Code / Codex | mwb-ai-claw 现状 | 差距影响 |
|--------|---------------------|------------------|----------|
| 审批模式 | Codex 三档（read-only / auto-approve / full-auto）+ Claude Code `--permission-mode`：按命令风险等级弹窗确认或自动放行 | 无交互审批：白名单内命令**全自动执行**，白名单外**直接拒绝**，用户无中间确认环节 | **高**：`git push`、`npm install` 等不可逆操作无确认，与"审核型"工作流不符 |
| 细粒度规则 | 按命令分类（读/写/网络/危险）配置 allow/deny 规则 | 只校验**首 token** 是否在白名单 | 中：`python3 -c` 可执行任意代码，`git` 首 token 放行但 `git reset --hard` 无法拦截 |
| 参数级校验 | 规则可匹配参数模式 | 无参数校验（黑名单仅子串匹配） | 中 |
| OS 级沙箱 | Codex Landlock/seatbelt；Claude Code 建议容器 | 仅进程级白名单 | 中：白名单绕过后无纵深防御 |
| 敏感信息脱敏 | 密钥/密码自动脱敏 | 无（命令与输出原样展示） | 中：`export TOKEN=xxx`、日志中的密钥会明文进上下文 |
| 审计日志 | 命令执行记录可查 | 仅 slf4j 日志 | 低 |
| 资源限制 | 内存/CPU/文件句柄限制（部分工具） | 仅超时 | 低 |

### 3.5 自动化与 CI 集成

| 对比项 | Claude Code / Codex | mwb-ai-claw 现状 | 差距影响 |
|--------|---------------------|------------------|----------|
| headless/打印模式 | `claude -p`、`codex exec`（管道输入、非交互、CI 可用） | 无；Shell 模式只能是交互 REPL | **高**：无法脚本化、管道化（`cat logs | claw -p "总结"`） |
| 启动参数 | 启动时直接传 prompt、`--continue`、`--resume` | 无启动参数，仅 `--spring.profiles.active=shell` | 中：每次都进交互循环 |
| `!命令` 快捷执行 | `!npm test` 直接跑命令并喂给 Claude | 无 | 中：常见调试流（看测试失败 → 问 AI）缺失 |
| 自定义命令 | OpenCode custom commands（Markdown 定义）、Claude Code slash commands | 仅 10 个硬编码内置命令 | 中：无法沉淀个人高频 prompt |
| Hook | Claude Code pre/post tool 钩子（自动 lint、阻止危险操作） | 无 | 中 |
| Git 工作流 | Aider 自动 commit；Claude Code 提交/PR | 依赖 LLM 手动调 shell | 中 |

### 3.6 扩展性与生态

| 对比项 | Claude Code / OpenCode | mwb-ai-claw 现状 | 差距影响 |
|--------|------------------------|------------------|----------|
| 子代理/并行 | 子代理并行任务、多会话并行 | 有 multi-agent routing/pipeline 编排，但 Shell 层无暴露 | 低（架构已有，缺入口） |
| MCP 管理 | `/mcp` 查看/连接/配置 | 支持 MCP 工具但 Shell 层无管理命令 | 低 |
| 技能入口 | 技能自动发现 | 支持 Skill，但 `/help` 中无技能相关命令，需靠对话触发 `use_skill` | 低 |
| 项目规则文件 | CLAUDE.md / AGENTS.md 分层加载 | 有 AGENT.md/技能体系，Shell 层无编辑/查看入口 | 低 |

---

## 4. 改进建议清单

按优先级分组。**P0 = 直接影响可用性与执行效率；P1 = 显著提升体验；P2 = 锦上添花。**

### P0：强烈建议优先实施

| # | 建议 | 现状问题 | 涉及位置 |
|---|------|----------|----------|
| 1 | **ShellTool 支持完整 shell 语义**：改为 `bash -lc`（或 `/bin/sh -c`）执行，让 LLM 能使用管道、重定向、`&&`、通配符、变量 | `parseCommand` 仅支持空格+双引号，`grep | head`、`cd && mvn` 无法表达 | [ShellTool.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/builtin/ShellTool.java) `parseCommand` |
| 2 | **审批模式**：`agent.security.shell-approval-mode` 三档（`auto` / `ask` / `read-only`），白名单命令命中高风险类别时向用户弹确认（Y/N），或在 REST 场景返回 `PENDING_APPROVAL` 状态供 Web 端确认 | 目前白名单内全自动执行，`git push` 等不可逆操作无确认 | [ShellTool.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/builtin/ShellTool.java) 执行前、[ToolSecurity.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/ToolSecurity.java) |
| 3 | **长时命令不超时强杀**：超时后降级为"后台任务"（记录进程句柄，后续轮询结果/可终止），或提供 `background=true` 参数 | 编译/测试 >30s 必失败，`destroyForcibly()` 丢弃结果 | [ShellTool.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/builtin/ShellTool.java) L102-106 |
| 4 | **命令执行实时流式回显**：执行区按行增量展示输出（对齐现有 LLM 流式渲染架构），而不是结束后一次性输出 | 长命令期间无任何反馈 | [ShellTool.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/builtin/ShellTool.java) 输出收集段、`ProgressCallback` |
| 5 | **headless / print 模式**：启动参数 `--print`（或 `-p`）+ 标准输入管道读取，支持 `echo "query" \| claw -p` 与 CI 场景 | 无法脚本化/管道化 | [AgentShell.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/shell/AgentShell.java) `run()` |
| 6 | **`/clear` 语义修正 + `/compact` 上下文压缩**：`/clear` 应重置当前对话上下文（新建会话或清空上下文）；`/compact` 用现有 `MemorySynthesisExecutor` 做会话内摘要压缩 | `/clear` 仅清屏；长会话 token 膨胀 | [AgentShell.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/shell/AgentShell.java) `handleCommand` |

### P1：显著提升体验

| # | 建议 | 涉及位置 |
|---|------|----------|
| 7 | **参数级安全校验**：白名单从"首 token"升级为"命令 + 参数规则"（如允许 `git diff`/`git status` 但拒绝 `git reset --hard`/`git push --force`），黑名单支持正则 | [ToolSecurity.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/ToolSecurity.java) `validateShellCommand` |
| 8 | **消息编辑与多行输入**：JLine 配置 multi-line（`disable-autosuggestion` + 多行 keymap），方向键调出上一条消息编辑；可选实现 Esc Esc 上下文回退 | [AgentShell.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/shell/AgentShell.java) `initTerminal` |
| 9 | **Tab 补全**：JLine `Completer` 补全斜杠命令 + 会话 ID（从 `listSessions` 拉取）+ 文件路径（`file` 补全器） | [AgentShell.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/shell/AgentShell.java) `initTerminal` |
| 10 | **启动参数**：支持 `--prompt "..."`（启动即执行单轮）、`--resume <id>`、`--mode stream|sync` | [AgentShell.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/shell/AgentShell.java) `run()` |
| 11 | **`!命令` 快捷执行**：输入以 `!` 开头时本地执行命令并把输出注入上下文（复用 `ShellTool` 沙箱校验） | [AgentShell.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/shell/AgentShell.java) `processInput` |
| 12 | **会话标题自动生成/重命名**：首条消息生成标题；`/session rename <id> <title>` | [AgentShell.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/shell/AgentShell.java) `handleSessionCommand` |
| 13 | **成本/Token 统计**：`/cost` 命令展示当前会话 token 用量（LLM 层已有用量回调可透出）与预估费用 | `AgentShell` + `LlmStreamCallback` |
| 14 | **敏感信息脱敏**：命令与输出中的常见密钥模式（`token`/`api_key`/`password=` 等）自动打码后再进上下文 | [ToolSecurity.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/ToolSecurity.java) |

### P2：锦上添花 / 生态

| # | 建议 | 说明 |
|---|------|------|
| 15 | 计划模式：对话输入前按 `plan` 模式先出方案，用户确认后再执行（可与审批模式结合） | OpenCode Plan/Build 对齐 |
| 16 | 自定义斜杠命令：`~/.claw/commands/*.md` + 占位符，启动加载注册 | 复用现有 Skill 加载器 |
| 17 | `/mcp` 管理命令：查看已连接 MCP 服务器、动态启停 | MCP 注册机制已存在 |
| 18 | 会话导出/分叉：`/session export <id>`（JSON）、`/fork` | |
| 19 | OS 级沙箱（可选）：macOS seatbelt / Linux landlock 或容器包装 | 纵深防御，成本高，优先级低 |
| 20 | 后台 agent：`--bg "任务"` + `/agent attach`（复用 multi-agent 编排） | 依赖 P0-3 的后台进程能力 |
| 21 | 主题与状态栏：JLine 状态栏显示模式/会话/上下文占比 | |

---

## 5. 分阶段路线图

### 阶段一：执行能力与安全（对应 P0-1 ~ P0-4）
1. `ShellTool` 切换到 `bash -lc` 语义（保留白名单校验），一次性补齐管道/重定向/变量/`&&`；
2. 审批模式三档落地（`auto`/`ask`/`read-only`），Shell REPL 内 Y/N 交互，REST 返回待审批状态；
3. 超时降级为后台任务 + 结果轮询；输出改为流式回调；
4. 参数级安全规则。

### 阶段二：交互体验（对应 P0-5、P0-6、P1-8 ~ P1-13）
5. `-p`/`--prompt`/`--resume` 启动参数与 headless 管道模式；
6. `/clear` 语义修正、`/compact` 上下文压缩（复用 `MemorySynthesisExecutor`）；
7. JLine 多行编辑、Tab 补全、`!` 快捷执行、会话重命名、`/cost` 统计、敏感信息脱敏。

### 阶段三：生态与高级能力（对应 P1-14 及 P2 项）
8. 自定义命令、`/mcp` 管理、会话导出/分叉、计划模式、后台 agent、OS 级沙箱。

---

## 6. 结论

最影响"能用、好用"的差距集中在 **P0 六项**，其中优先级排序建议：

1. **shell 语义**（P0-1）——不改则 LLM 执行效率长期受限；
2. **审批模式**（P0-2）——不改则无法进入可信执行场景；
3. **长时任务与流式回显**（P0-3/P0-4）——不改则编译/测试类任务必失败；
4. **headless 与上下文管理**（P0-5/P0-6）——不改则无法自动化、长会话成本失控。

这六项均为局部改造（集中在 [ShellTool.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/builtin/ShellTool.java)、[ToolSecurity.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-infrastructure/src/main/java/com/mwb/ai/claw/infrastructure/tool/ToolSecurity.java)、[AgentShell.java](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/shell/AgentShell.java) 三个文件及对应配置），不涉及领域层重构，落地成本可控，收益显著。

---

## 7. 参考资料

- Claude Code 官方文档（slash commands、checkpoint、permission mode、headless 模式）
- OpenAI Codex CLI 文档（approval modes、OS 沙箱、`exec` 子命令）
- OpenCode 文档（LSP、custom commands、Plan/Build、多会话）
- Aider 文档（git-first、`/add` `/commit` `/repin`）
- mwb-ai-claw [README.md](file:///Users/mawenbin/workspace/java/mwb_coding/mwb-ai-claw/README.md)（现状功能清单：白名单 65/黑名单 21、30s 超时、10000 字符截断、JLine REPL）
