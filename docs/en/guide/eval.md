---
title: Agent Evaluation System
parent: User Guide (EN)
nav_order: 11
---

# Agent Evaluation System

The evaluation system quantifies agent performance: **define a dataset → execute → judge → produce a report → compare for regressions**. Its core lives in the dedicated `mwb-ai-claw-eval` module and does not affect the main runtime. Interactive usage goes through the shell `/eval` commands; web-mode usage goes through the `example-web` REST endpoints; build-time usage goes through the Maven-plug-in regression gate.

## 1. Dataset Definition

A dataset is a JSON or YAML file containing a `task` (metadata + default judge config) and a list of `cases`:

```json
{
  "task": {
    "id": "qa-basic",
    "name": "Basic QA",
    "version": "1.0",
    "enabled": true,
    "defaultJudge": "rule",
    "mode": "boolean"
  },
  "cases": [
    {
      "id": "c1",
      "name": "addition",
      "prompt": "What is 1+1?",
      "expected": "answer contains 2",
      "rule": { "type": "contains", "value": "2" }
    },
    {
      "id": "c2",
      "name": "open question",
      "prompt": "Explain what RAG is",
      "expected": "should mention retrieval-augmented generation",
      "judge": "llm",
      "mode": "boolean"
    }
  ]
}
```

- **Judge strategy** (`judge`): `rule` (deterministic `exact`/`contains`/`regex`, low cost) | `llm` (LLM semantic judge with structured JSON output) | `both` (default: rule first, LLM fallback). A single `case` can override `task.defaultJudge` via `judge`.
- **LLM judge reply mode** (`mode`): `boolean` (default) | `number` (0-10) | `quote` (quote the expected criterion).
- **Validation**: `task.id` is required; each `case` must have a unique `id` and non-empty `prompt`/`expected`.

A YAML dataset is equivalent (same structure, indentation-based).

## 2. Interactive Usage (Shell /eval)

Inside the shell (`mwb-ai-claw`):

```
/eval run dataset/qa.json main rule
/eval report ./eval-report/qa-basic-<timestamp>.json
/eval diff ./eval-report/baseline.json ./eval-report/latest.json
/eval ls dataset
```

- `/eval run <datasetPath> [agentId] [judge] [judgeModel]`: execute and write JSON/Markdown reports, then print a pass-rate / latency / token / cost summary. The judge reuses the target agent's model config by default; override it with `judgeModel`.
- `/eval report <reportPath>`: print a summary of an existing report.
- `/eval diff <baseline> <current>`: regression compare (see below).
- `/eval ls [datasetDir]`: list usable datasets in a directory (including parse-failure annotations).

> See [shell-commands](../reference/shell-commands.md) for related commands and [config-full](../reference/config-full.md) for configuration.

## 3. Web Integration (example-web REST)

`example-web` shows how to expose the same evaluation capability over HTTP in web mode (`spring.profiles.active=web`), so front-end or CI can call it without entering the shell (protected by the framework `AuthInterceptor` by default; an API Key is required).

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/eval/dataset/generate` | Auto-generate an evaluation dataset by topic with LLM and persist it as a JSON file. Body is a `GenerateDatasetCommand` (`topic` required; optional `type`/`count`/`agentId`/`model`/`taskId`/`name`); returns `GenerateDatasetResult` (persisted path + dataset content) |
| `POST` | `/eval/run` | Run an evaluation. Body is an `EvalConfig` JSON (`datasetPath` required; optional `agentId`/`judge`/`judgeModel`/`output`/`concurrency`/`filters`/`recordTraces`); returns `EvalRunResult` (report + JSON/Markdown paths) |
| `GET` | `/eval/report?path=<json>` | Read back an existing JSON report summary |
| `GET` | `/eval/diff?baseline=<json>&current=<json>` | Regression-compare two reports |
| `GET` | `/eval/ls?dir=<dir>` | List usable datasets in a directory |

```bash
# Auto-generate a dataset with LLM (topic required; output is runnable via /eval/run)
curl -X POST http://localhost:8080/eval/dataset/generate \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: <your-api-key>' \
  -d '{"topic":"高可用架构","type":"qa","count":5,"agentId":"default"}'

# Run an evaluation (datasetPath is the returned datasetPath or any absolute dataset path)
curl -X POST http://localhost:8080/eval/run \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: <your-api-key>' \
  -d '{"datasetPath":"/abs/path/qa-basic.json","judge":"both"}'
```

**Visual page** (`example-web-frontend`, "评测" nav): provides a full loop — LLM auto-generate a dataset → select a dataset to run → view result report and regression diff. `EvalDatasetGenerator` reuses the `LlmGateway`/`AgentGateway` beans exposed by the framework, invoking the agent model by topic to produce dataset-conformant JSON (`task` + `cases[prompt/expected/rule]`, with `contains` rules), validates it, and persists it to `example.eval.dataset-dir` (default `./generated-datasets`). Output directory/model etc. can be configured via `application.yml`.

Implementation notes: `EvalController` reuses the same assembly logic as the shell `EvalCommandService` — inject the framework's exposed core beans (`ExecutionUnit`/`AgentGateway`/`LlmGateway`/`MetricsRecorder`/`TraceStore`) and build `EvalRunner → EvalService` without altering the core execution path. In `example-web`, `WebSecurityConfig` puts `/eval/**` under authentication and `CorsConfig` allows cross-origin access.

## 4. Build-time Regression Gate (Maven Plug-in)

`mwb-ai-claw-eval-maven-plugin` provides two goals that compare reports at build time without re-bootstrapping the agent runtime:

| goal | Description | Parameters |
| --- | --- | --- |
| `eval:diff` | Compare baseline/current reports; fail the build on regression | `baseline` (required), `current` (required), `failOnRegression` (default `true`) |
| `eval:report` | Print a summary of an existing report (for CI readability) | `report` (required) |

```bash
# Compare baseline with the latest report; BUILD FAILURE on regression
mvn io.github.mwb1219:mwb-ai-claw-eval-maven-plugin:1.0.6-SNAPSHOT:diff \
    -Deval.baseline=./eval-report/baseline.json \
    -Deval.current=./eval-report/latest.json \
    -Deval.failOnRegression=true
```

Judgement semantics: `regressed` = passed in baseline but failed in current; overall `regression` = any regressed case or a lower current pass rate. With `failOnRegression=false`, regression only warns.

## 5. CI Integration

`tools/ci.sh` reserves a third "evaluation regression gate" stage (opt-in): provide the `EVAL_BASELINE` and `EVAL_CURRENT` environment variables to make the script run the plug-in's `eval:diff`, failing CI on regression; the stage is skipped otherwise.

```bash
EVAL_BASELINE=./eval-report/baseline.json \
EVAL_CURRENT=./eval-report/latest.json \
EVAL_FAIL_ON_REGRESSION=true \
./ci.sh
```

## 6. Report & Regression Semantics

Each report (JSON/Markdown) contains `taskId`, `runAt`, `meta` (agentId/model/judgeModel/judge/datasetVersion/envHash), `summary` (pass rate / avg latency / avg tokens / total tokens / estimated cost), and a per-case `CaseResult` (passed/score/verdict/duration/tokens/error).

`eval:diff` labels each case when comparing two reports:

| Status | Meaning |
| --- | --- |
| `REGRESSED` | pass → fail (regression, needs alert) |
| `IMPROVED` | fail → pass (fix) |
| `UNCHANGED` | same status |
| `NEW` | only in current |
| `MISSING` | only in baseline (case removed/renamed) |