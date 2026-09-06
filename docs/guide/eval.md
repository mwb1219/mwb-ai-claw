---
title: Agent 评测系统
parent: 使用指南
nav_order: 11
---

# Agent 评测系统

评测系统量化 Agent 表现：**定义数据集 → 执行 → 判定 → 产出报告 → 回归对比**。核心在独立模块
`mwb-ai-claw-eval`，不影响主运行时；交互式触发走 Shell `/eval` 命令，web 模式走 example-web REST 接口，
构建期走 Maven 插件回归门。

## 1. 数据集定义（Dataset）

数据集是一个 JSON 或 YAML 文件，包含 `task`（元信息 + 默认判定配置）与 `cases`（用例列表）。
`mwb-ai-claw-eval/dataset` 示例：

```json
{
  "task": {
    "id": "qa-basic",
    "name": "基础问答",
    "version": "1.0",
    "enabled": true,
    "defaultJudge": "rule",
    "mode": "boolean"
  },
  "cases": [
    {
      "id": "c1",
      "name": "加法",
      "prompt": "1+1=?",
      "expected": "答案含 2",
      "rule": { "type": "contains", "value": "2" }
    },
    {
      "id": "c2",
      "name": "开放题",
      "prompt": "请解释什么是 RAG",
      "expected": "应提及检索增强生成",
      "judge": "llm",
      "mode": "boolean"
    }
  ]
}
```

- **判定（judge）策略**：`rule`（确定性，`exact`/`contains`/`regex`，低成本）｜ `llm`（LLM 语义裁判，
  结构化 JSON 输出）｜ `both`（默认，规则先过、LLM 兜底）。单个 `case` 可用 `judge` 覆盖 `task.defaultJudge`。
- **LLM 裁判返回格式（mode）**：`boolean`（默认）｜ `number`（0-10）｜ `quote`（引用期望判据）。
- **校验要求**：`task.id` 必有；每个 `case` 的 `id` 唯一、`prompt` 与 `expected` 必填。

YAML 写法等价（结构相同，用缩进替代括号）。

## 2. 交互式运行（Shell /eval）

进入 Shell（`mwb-ai-claw`）后：

```
/eval run dataset/qa.json main rule
/eval report ./eval-report/qa-basic-<时间戳>.json
/eval diff ./eval-report/baseline.json ./eval-report/latest.json
/eval ls dataset
```

- `/eval run <datasetPath> [agentId] [judge] [judgeModel]`：执行并落盘 JSON/Markdown 报告，输出通过率/耗时/token/cost 摘要。
  裁判默认复用目标 Agent 的模型配置，可用 `judgeModel` 覆盖。
- `/eval report <reportPath>`：查看既有报告摘要。
- `/eval diff <baseline> <current>`：回归对比（见下）。
- `/eval ls [datasetDir]`：列举数据集目录中的可用数据集（含解析失败标注）。

> 相关 Shell 命令详见 [shell-commands](../reference/shell-commands.md)；配置项见 [config-full](../reference/config-full.md)。

## 3. Web 集成（example-web REST）

`example-web` 演示如何在 web 模式（`spring.profiles.active=web`）下把同一套评测能力暴露为 HTTP 接口，
无需进入 Shell，前端 / CI 可直接调用（默认受框架 `AuthInterceptor` 保护，需携带有效 API Key）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/eval/dataset/generate` | 用 LLM 按主题自动生成评测数据集并落盘为 JSON 文件。Body 为 `GenerateDatasetCommand`（`topic` 必填，可选 `type`/`count`/`agentId`/`model`/`taskId`/`name`）；返回 `GenerateDatasetResult`（含落盘路径 + 数据集内容） |
| `POST` | `/eval/run` | 运行评测。Body 为 `EvalConfig` JSON（`datasetPath` 必填，可选 `agentId`/`judge`/`judgeModel`/`output`/`concurrency`/`filters`/`recordTraces`）；返回 `EvalRunResult`（含报告 + JSON/Markdown 路径） |
| `GET` | `/eval/report?path=<json>` | 回读既有 JSON 报告摘要 |
| `GET` | `/eval/diff?baseline=<json>&current=<json>` | 回归对比两份报告 |
| `GET` | `/eval/ls?dir=<dir>` | 列举数据集目录中的可用数据集 |

```bash
# 用 LLM 自动生成数据集（topic 必填，输出可运行 /eval/run）
curl -X POST http://localhost:8080/eval/dataset/generate \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: <your-api-key>' \
  -d '{"topic":"高可用架构","type":"qa","count":5,"agentId":"default"}'

# 运行评测（datasetPath 为生成返回的 datasetPath 或任意数据集文件绝对路径）
curl -X POST http://localhost:8080/eval/run \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: <your-api-key>' \
  -d '{"datasetPath":"/abs/path/qa-basic.json","judge":"both"}'
```

**前端可视化页面**（`example-web-frontend`，导航「评测」）：提供 LLM 自动生成数据集 → 选择数据集运行 →
查看结果报告与回归对比的完整闭环。`EvalDatasetGenerator` 复用 `LlmGateway`/`AgentGateway` 已暴露的 Bean，
按主题调用 Agent 模型产出符合数据集规范的 JSON（task + cases[prompt/expected/rule]，自带 contains 规则），
校验后落盘到 `example.eval.dataset-dir`（默认 `./generated-datasets`），生成目录/模型等可通过 `application.yml` 配置。

实现要点：`EvalController` 与 shell 版 `EvalCommandService` 复用同一装配逻辑——注入框架已暴露的核心 Bean
（`ExecutionUnit`/`AgentGateway`/`LlmGateway`/`MetricsRecorder`/`TraceStore`），构造 `EvalRunner → EvalService`，
不改动核心执行链路。`example-web` 的 `WebSecurityConfig` 已把 `/eval/**` 纳入鉴权，`CorsConfig` 已放行跨域。

## 4. 构建期回归门（Maven 插件）

`mwb-ai-claw-eval-maven-plugin` 提供两个 goal，在**构建期**对比报告做质量门，无需重复装配 Agent 运行时：

| goal | 说明 | 参数 |
| --- | --- | --- |
| `eval:diff` | 对比 baseline/current 报告，回归即构建失败 | `baseline`（必填）、`current`（必填）、`failOnRegression`（默认 `true`） |
| `eval:report` | 打印既有报告摘要（CI 可读） | `report`（必填） |

```bash
# 对比 baseline 与最新报告；检测到回归（通过率下降或存在回归用例）即 BUILD FAILURE
mvn io.github.mwb1219:mwb-ai-claw-eval-maven-plugin:1.0.6-SNAPSHOT:diff \
    -Deval.baseline=./eval-report/baseline.json \
    -Deval.current=./eval-report/latest.json \
    -Deval.failOnRegression=true
```

插件判定口径：`regressed` = baseline 通过且 current 失败；整体 `regression` = 存在回归用例或 current 通过率低于
baseline。`failOnRegression=false` 时回归仅告警不中断。

## 5. CI 集成

`tools/ci.sh` 预留第三阶段「评测回归门」（opt-in）：提供 `EVAL_BASELINE` 与 `EVAL_CURRENT`
环境变量后，脚本自动执行插件 `eval:diff`，回归即让 CI 失败；未配置则跳过。

```bash
EVAL_BASELINE=./eval-report/baseline.json \
EVAL_CURRENT=./eval-report/latest.json \
EVAL_FAIL_ON_REGRESSION=true \
./ci.sh
```

## 6. 报告与回归对比语义

每份报告（JSON/Markdown）含 `taskId`、`runAt`、`meta`（agentId/model/judgeModel/judge/datasetVersion/envHash）、
`summary`（通过率/平均耗时/平均 token/累计 token/估算成本）与逐 `case` 的 `CaseResult`
（passed/score/verdict/duration/tokens/error）。

`eval:diff` 对比两张报告时，每个用例标记为：

| 状态 | 含义 |
| --- | --- |
| `REGRESSED` | 通过 → 失败（回归，需告警） |
| `IMPROVED` | 失败 → 通过（修复） |
| `UNCHANGED` | 状态一致 |
| `NEW` | 仅出现在 current |
| `MISSING` | 仅出现在 baseline（用例已移除/改名） |