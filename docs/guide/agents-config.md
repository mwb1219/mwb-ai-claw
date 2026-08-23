---
title: Agent 注册表与编排配置
parent: 使用指南
nav_order: 7
---

# Agent 注册表与编排配置

> 面向扩展方：配置专家 Agent（`agents.json`）与协作编排（`orchestrations.json`）。
> 运行目录放同名文件即可覆盖内置默认，无需重新打包。

## 1. 加载机制

- [ ] 运行目录（user.dir）同名文件命中即用 → 安装目录 `~/.mwb-ai-claw/config/` 同名文件 → jar 内置 classpath 默认
- [ ] `${VAR:default}` 占位符引用 `.env` 变量

## 2. agents.json（Agent 注册表）

- [ ] 字段：`agentId` / `name` / `description` / `keywords` / `systemPrompt` / `tools` / `maxSteps` / `maxTokens` / `model` / `baseUrl` / `apiKey` / `temperature` / `provider`
- [ ] 工具绑定：缺省=全部已注册；显式 `tools` = 强制仅绑定声明
- [ ] 独立模型：每 Agent 可配 `model` / `baseUrl` / `apiKey` / `provider` / `temperature` / `maxTokens`；`model` / `baseUrl` / `apiKey` 均支持 `${VAR:default}` 引用 `.env`，如 `"baseUrl": "${CODER_BASE_URL:${DEFAULT_BASE_URL:https://api.deepseek.com}}"`（留空继承默认）

## 3. orchestrations.json（编排注册表）

`orchestrations.json` 定义可用的多 Agent 编排（单条编排 = 一种协作方式），每条含 `id` / `type` / `description` / `keywords` / `agents` / `config`。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 编排 id（全局唯一），供显式指定 / `invoke_*` 工具引用 |
| `type` | 是 | 编排插件类型：`routing` / `conversational` / `delegate`（须为已注册的插件类型） |
| `description` | 是 | 能力描述（供展示与 LLM 选择器语义判断） |
| `keywords` | 否 | 意图关键词（供规则选择器匹配，如「选型」「方案对比」） |
| `agents` | 否 | 引用的 agentId 列表（启动校验存在性，可选） |
| `config` | 按类型 | 编排参数（宽松 JSON，由对应插件自行解释） |

> `config` 是插件化的核心：注册中心与定义模型不感知具体编排结构，各插件自行解释自己的 `config`。

### 3.1 完整配置示例

内置默认模板（`start` / `example-web` 模块的 `orchestrations.json`），可直接复制到运行目录覆盖：

```json
{
  "orchestrations": [
    {
      "id": "routing",
      "type": "routing",
      "description": "单专家独立处理：适用于单一领域问题（写代码、查资料、答疑、修复等），由路由按意图选择最合适的 Agent。作为默认兜底编排；多 Agent 协作编排经 invoke_* 工具由主 Agent 自主发起，不再做消息前置意图路由",
      "agents": ["coder", "researcher", "architect", "reviewer"]
    },
    {
      "id": "team-discussion",
      "type": "conversational",
      "description": "多方专家对话式讨论：架构师 / 编码专家 / 审查专家围绕同一问题多轮讨论（首轮并行观点、讨论轮互相回应），最后由决策主持收敛为明确结论。适用于技术选型、方案对比、权衡决策类问题",
      "keywords": ["选型", "方案对比", "对比", "权衡", "决策", "哪个更好", "如何选择", "优缺点", "讨论", "评估", "怎么选"],
      "agents": ["architect", "coder", "reviewer", "moderator"],
      "config": {
        "conversation": {
          "rounds": 2,
          "moderator": "moderator",
          "participants": ["architect", "coder", "reviewer"],
          "minConsensus": 2,
          "convergence": "moderator",
          "visibleHistory": 1,
          "thinking": false
        }
      }
    },
    {
      "id": "todo-delegate",
      "type": "delegate",
      "description": "主 Agent 思考规划并拆解为 Todo 列表，委托子 Agent 执行；子 Agent 可递归再委托，逐层汇总。适用于复杂、多步骤、跨领域任务",
      "keywords": ["规划并实现", "复杂任务", "任务拆解", "分步骤", "分步完成", "多步骤", "拆解并执行", "团队协作", "分工完成", "整体规划"],
      "agents": ["architect", "coder", "researcher", "reviewer"],
      "config": {
        "delegate": {
          "plannerAgentId": "architect",
          "maxTodos": 8,
          "maxDepth": 2,
          "parallel": true,
          "concurrency": 4,
          "onFailure": "abort",
          "retries": 1,
          "thinking": false,
          "resultPass": "text",
          "approvalGate": "none",
          "approvalTimeoutMs": 0,
          "topK": 3,
          "replanRounds": 0
        }
      }
    }
  ]
}
```

### 3.2 routing 类型（单专家路由）

单专家独立处理，由路由按意图选择一个专家 Agent（显式指定 agentId 优先）。无 `config` 参数，`agents` 列出可被路由选中的专家即可。适合单一领域问题，作为默认兜底编排。

### 3.3 conversational 类型（对话式讨论）

多方专家围绕同一问题多轮讨论后收敛为结论。`config.conversation` 参数：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `participants` | -（必填） | 参与讨论的 Agent id 列表（至少 2 个） |
| `rounds` | `2` | 讨论轮数（1 = 仅首轮观点，2 起为互相回应的讨论轮；建议 1-4） |
| `moderator` | - | 收敛 Agent id（`convergence=moderator` 时必填，引用 agents.json 注册表） |
| `convergence` | `moderator` | 收敛策略：`consensus`（观点多数一致）/ `moderator`（仲裁汇总）/ `best`（置信度最高） |
| `minConsensus` | `2` | 共识阈值：某观点被 >= 该数量参与者支持即提前收敛（仅 `consensus` 生效） |
| `visibleHistory` | `1` | 讨论轮可见历史轮数（控制上下文占用） |
| `thinking` | 不覆盖 | 思考模式开关（null = 不覆盖 Agent 默认配置） |

### 3.4 delegate 类型（任务拆解委派）

主 Agent 规划拆解为 Todo 列表，委托子 Agent 并行/递归执行后逐层汇总。`config.delegate` 参数：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `plannerAgentId` | `architect` | 根节点规划 Agent id（子节点规划者 = 上一层 todo 的 agentId） |
| `maxTodos` | `8` | 单层 Todo 数量上限（超出截断并告警） |
| `maxDepth` | `2` | 递归委托深度（1 = 仅主 Agent 拆解一层，子 Agent 直接执行；2 = 允许子 Agent 再拆解一层） |
| `parallel` | `true` | 无依赖 Todo 是否并行执行 |
| `concurrency` | `4` | 并行度（并行 Wave 工作线程数） |
| `onFailure` | `abort` | Todo 失败策略：`abort`（终止整个编排）/ `skip`（标记失败继续，汇总时注明） |
| `retries` | `1` | Todo 失败重试次数（空回复同样触发重试） |
| `thinking` | 不覆盖 | 规划 / 汇总阶段思考模式开关 |
| `resultPass` | `text` | 汇总结果传递：`text`（直接拼入汇总 prompt）/ `file`（落盘传文件路径） |
| `workdir` | `orchestration-artifacts` | 产物落盘目录（`resultPass=file` 时使用） |
| `approvalGate` | `none` | 人工审批门禁：`none`（不暂停）/ `root`（仅根规划完成暂停）/ `all`（每层规划完成暂停） |
| `approvalTimeoutMs` | `0` | 审批等待超时（毫秒，0 = 无限等待；超时后该层降级直执行） |
| `topK` | `3` | 汇总阶段子结果相关性 top-k 注入数量（子结果数 <= topK 时全量注入） |
| `replanRounds` | `0` | 动态规划（Plan-Do-Reflect）re-plan 轮次（0 = 不启用；>0 时每执行完一个 Wave 结合已得结果调整剩余 Todo） |

## 4. 协作工具（多 Agent 自主发起）

多 Agent 协作编排通常不由消息前置路由触发，而是主 Agent 在 ReAct 循环中通过全局工具自主发起：

- [ ] `invoke_discussion` → team-discussion 编排（多方专家讨论收敛）
- [ ] `invoke_delegate` → todo-delegate 编排（Todo 拆解委派）
- [ ] 全局注册（global=true），无需在配置中声明
- [ ] 编排嵌套组合：delegate 的 Todo 可指定 `orchestrationId` 调起其他编排（如 conversational），带防环检测

## 5. 校验与排错

- [ ] 启动校验（fail-fast）：编排 `id` 唯一、`type` 已注册、引用的 `agentId` 存在、插件级配置合法（如 conversational 的 `participants >= 2`、delegate 的 `maxTodos >= 1`）
- [ ] 常见错误与解决：
  - `编排 id 重复: xxx` → 检查 orchestrations.json 中 `id` 是否唯一
  - `编排 'xxx' 引用了未注册的类型 'yyy'` → 该类型未实现 / 未注册为 Spring Bean
  - `编排 'xxx' 引用了不存在的 Agent: zzz` → agents.json 中缺少该 agentId
  - `对话式编排 'xxx' 的 participants 至少需要 2 个参与者` → 检查 `conversation.participants`
  - `委托编排 'xxx' 的 onFailure 不合法` → 枚举值仅 `abort` / `skip`

---

相关：[配置详解](configuration.md) ｜ [多 Agent 编排设计](../design/collaboration.md) ｜ [技能系统](skills.md)
