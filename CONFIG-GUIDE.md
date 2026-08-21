# mwb-ai-claw 配置指南

本文件说明安装包中各配置文件的用途与配置方法。所有配置均可在**不改代码、不重新打包**的情况下调整。

## 1. 配置文件总览

```
安装包 /
├── .env.example            密钥与模型配置模板（复制为 .env 填写）
├── skills/                 内置技能模板（SKILL.md，可增删自定义技能）
└── config/
    ├── agents.json         专家 Agent 定义（身份、模型、可用工具）
    ├── orchestrations.json 协作编排注册表（路由 / 讨论 / 委托）
    └── mcp-server.json.example  MCP Server 配置模板（复制为 mcp-server.json 生效）
```

安装（install.sh / install.ps1）后，安装目录 `~/.mwb-ai-claw/` 布局：

```
~/.mwb-ai-claw/
├── lib/start.jar           运行产物
├── config/                 配置模板（直接修改即可覆盖内置默认）
├── skills/                 技能模板（增删技能目录即自定义技能集）
├── .env.example            密钥模板副本（参考/重置用）
├── .env                    全局密钥配置（安装时已从模板创建，填入密钥即可）
└── bin/mwb-ai-claw         启动器
```

## 2. 加载优先级（如何生效）

配置按**三级加载**，高优先级命中即生效：

```
运行目录（user.dir）→ 安装目录 ~/.mwb-ai-claw（config/ 子目录 或 根目录 .env）→ classpath 内置默认
```

- 安装后**直接修改 `~/.mwb-ai-claw/config/` 下的文件**即可覆盖内置默认，重启生效；
- **密钥写入 `~/.mwb-ai-claw/.env`**（已在安装时创建，直接编辑即可），同样会被加载；
- 也可把配置文件复制到**运行目录**（执行命令时所在目录）做单次运行覆盖，优先级最高；
- `mcp-server.json.example` 需**复制为 `mcp-server.json`** 才会被加载。

> 提示：配置加载器只读目标文件，不向上级目录搜索；高优先级命中即不再读取低优先级来源。

## 3. 密钥与模型（.env）

安装后直接编辑 `~/.mwb-ai-claw/.env`（已从模板创建）；手动运行（源码/`java -jar`）时复制 `.env.example` 为运行目录下 `.env` 并填写：

| 变量 | 说明 |
| --- | --- |
| `DEFAULT_MODEL` / `DEFAULT_BASE_URL` / `DEFAULT_API_KEY` | 默认模型（OpenAI 兼容接口） |
| `CODER_MODEL` / `CODER_API_KEY` 等 | 各专家 Agent 独立模型，留空继承默认 |
| `EMBEDDING_MODEL` / `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` | 向量检索专用模型（DeepSeek 不支持 embeddings，建议配 OpenAI 兼容的 text-embedding-3-small） |
| `SYNTHESIS_MODEL` / `SYNTHESIS_BASE_URL` / `SYNTHESIS_API_KEY` | 摘要/事实提炼的小模型（成本优化） |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `DB_DRIVER` | 数据库连接（`agent.storage.type=db` 时生效；默认嵌入式 H2，生产配 MySQL 连接串） |
| `SQL_INIT_MODE` | SQL 初始化模式：`embedded`（默认，仅 H2）| `never`（关闭）|

`.env` 支持 `${VAR}` 占位符引用，例如 `agents.json` 中的 `"model": "${CODER_MODEL:${DEFAULT_MODEL:deepseek-chat}}"`。

> **存储后端 `agent.storage.type`**：`file`（本地文件，默认，零依赖）| `db`（JDBC 持久化：会话 / 长期记忆 / 记忆页落库）。
> 设置位置：`application.yml` 的 `agent.storage.type`，环境变量 `STORAGE_TYPE`（写入 `.env`），或命令行覆盖 `mwb-ai-claw --agent.storage.type=db`（优先级递增）。
> 注意：`db` 时数据源为启动即连（见上表 DB_* 变量），需先确保数据库可达。
> 会话并发锁固定本地 JVM 实现（`LocalSessionLockManager`，单实例部署），**无需任何额外配置**（Redis 分布式锁已移除）。

## 4. 专家 Agent（config/agents.json）

`agents` 数组每个元素定义一个专家 Agent：

| 字段 | 说明 |
| --- | --- |
| `agentId` | 唯一标识（路由 / 编排引用） |
| `name` / `description` | 展示名与职责描述（路由依据） |
| `keywords` | 意图匹配关键词，路由按 description+keywords 打分 |
| `systemPrompt` | 系统提示词 |
| `maxSteps` / `maxTokens` | ReAct 最大步数 / 回复 token 上限 |
| `model` / `apiKey` | 该 Agent 的模型（支持 `${VAR}` 占位符） |
| `tools` | **可选**：可用工具列表 |

### 工具绑定规则

- **不配置 `tools`（缺省）**：绑定**全部已注册工具**（内置 + 全局/MCP）；
- **配置了 `tools`**：**强制仅绑定**声明的工具（`use_skill` / MCP / `invoke_*` 等全局工具需显式加入）。

内置工具名：`echo`、`http`、`file`、`shell`、`shell_status`、`read_memory`、`write_memory`；
全局工具：`use_skill`、MCP 工具、`invoke_delegate` / `invoke_discussion`（协作编排）。

## 5. 协作编排（config/orchestrations.json）

`orchestrations` 数组定义可用编排，`id` 被 `application.yml` 的 `agent.orchestration` 引用：

| 类型 | 用途 |
| --- | --- |
| `routing` | 单专家独立处理，按意图路由（默认兜底） |
| `conversational` | 多方专家对话式讨论（如方案对比、技术选型），`config.conversation` 控制轮数/主持/收敛 |
| `delegate` | 主 Agent 拆解 Todo 委托子 Agent 执行，`config.delegate` 控制深度/并行/失败策略 |

可增删编排，或调整 `keywords` 改变自动触发条件。

## 6. MCP Server（config/mcp-server.json）

复制 `mcp-server.json.example` 为 `mcp-server.json` 并按需增删：

```json
{
  "mcpServers": {
    "tavily-mcp": {
      "command": "npx",
      "args": ["-y", "tavily-mcp@0.1.2"],
      "env": { "TAVILY_API_KEY": "你的密钥" }
    }
  }
}
```

MCP 工具注册为全局工具，对所有未显式绑定工具的 Agent 可见。

## 7. 运行时覆盖（无需改文件）

启动命令追加 Spring 参数即可临时覆盖：

```bash
mwb-ai-claw --agent.orchestration=todo-delegate     # 切换编排
mwb-ai-claw --agent.model=deepseek-chat             # 切换默认模型
mwb-ai-claw --agent.security.shell-approval-mode=auto  # 工具审批自动放行
```

## 8. 数据与运行目录

- 会话 / 记忆数据落在**运行目录** `.agent/` 下（按项目隔离）；
- 运行用量记录写入 `.agent/runs/YYYY-MM-DD.jsonl`；
- 退出清理：删除运行目录 `.agent/` 即可重置全部会话记忆。

## 9. 技能（skills/）

技能是可复用工作流 / 领域知识包，格式为 `skills/<name>/SKILL.md`（YAML frontmatter `name` / `description` + Markdown 指令正文）。**加载顺序（三级，任一外部目录非空即完全接管技能集）**：

1. **运行目录**：`${user.dir}/skills`（或 `agent.skills-dir` 指定）
2. **安装目录**：`$MWB_AI_CLAW_HOME/skills/`（install 时随包复制，安装模式下直接在此增删技能目录，重启生效）
3. **classpath 内置**：jar 内 `skills/` 模板（10 个内置技能）兜底

新增技能 = 放入上述任一技能目录，重启应用后日志输出「已加载技能 [n]」，对话中 LLM 会自动调用 `use_skill` 按需加载。
