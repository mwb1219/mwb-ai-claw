# mwb-ai-claw Phase D：模型与生态 技术方案(SUBMIT)

> 状态：已实施完成（2026-08-20，T1-T6）
> 关联：[mwb-ai-claw框架化与双模式演进方案.md](./mwb-ai-claw框架化与双模式演进方案.md) §5 Phase D
> 范围约束（用户已确认）：**暂不实施技术栈升级（Java 17/21 + Spring Boot 3.x）**，本文仅做 D4 升级路径附录说明。

## 1. 背景与目标

Phase C（可观测性与韧性）完成后，框架已具备生产级稳定性。Phase D 补齐**模型生态**：当前只支持 OpenAI 兼容协议，无法直连 Anthropic / Gemini / 本地 Ollama；回复只能取纯文本，无结构化输出（JSON mode / 严格 schema）与多模态输入；自定义命令模板只有 `{args}`/`{1}` 简单替换。

目标（对应上级方案 Phase D 前 3 项，技术栈升级另行附录）：

| # | 目标 | 覆盖点 |
| ---- | ---- | ---- |
| D1 | 多 Provider 适配 | Anthropic（/v1/messages）、Gemini（generateContent）、本地 Ollama（OpenAI 兼容端点），可配置 provider 并自动路由 |
| D2 | 结构化输出与多模态 | JSON mode / 严格 schema（response_format）、文本中 JSON 容错提取、图片多模态输入 |
| D3 | 模板系统增强 | 模板变量 / 条件、产物结构化解析（output: json 时提取并格式化展示） |

## 2. 现状盘点

| 领域 | 现状 | 差距 |
| ---- | ---- | ---- |
| LLM 接入 | `LlmGatewayImpl` 仅实现 OpenAI 兼容 `/chat/completions`（同步 RestTemplate + 流式 HttpURLConnection/SSE） | 无 provider 概念，Anthropic/Gemini/Ollama 无法直连 |
| 模型配置 | `ModelConfig`（model/baseUrl/apiKey/temperature/maxTokens/thinking）+ `agent.model/baseUrl/apiKey` 顶层配置 + `agent.llm.*` 韧性配置 | 无 `provider` 类型字段 |
| 韧性 | `ResilientLlmGateway` 装饰器（重试/退避/fallback/预算），对协议无感知 | 可复用，新协议需保持同一响应/异常契约 |
| 结构化输出 | `LlmRequest` 无 `response_format` 概念；`LlmResponse` 仅 content/toolCalls/finishReason/usage | 无 JSON mode / json_schema；无文本 JSON 容错提取工具 |
| 多模态 | `LlmMessage` 仅 role/content 纯文本 | 无 image content 概念 |
| 模板系统 | `CustomCommandLoader` 解析 frontmatter(name/description)+正文；`AgentShell.handleCustomCommand` 仅 `replace("{args}", ...)` + `{1}`-`{9}` | 无变量/条件；无产物结构化解析 |

## 3. 总体设计：Provider 适配架构

保持 domain 层 `LlmGateway` 接口（chat / streamChat）与 `LlmResponse`/`LlmRequest` 值对象不变，在 infrastructure 层引入**协议网关 + 路由分发**：

```
ReActLoopService / 编排层
        │
        ▼
ResilientLlmGateway（装饰器：重试/退避/fallback/预算，Phase C 已实现，不变）
        │
        ▼
ProviderRoutingGateway（新增，@Primary LlmGateway Bean）
        │ 按 ModelConfig.provider 分派
        ├─► OpenAiProtocolGateway（改造复用现有 LlmGatewayImpl 逻辑）
        ├─► AnthropicProtocolGateway（新增 /v1/messages）
        ├─► GeminiProtocolGateway（新增 generateContent）
        └─► Ollama → OpenAiProtocolGateway（Ollama 提供 OpenAI 兼容端点 /v1/chat/completions）
```

关键约束：

- **统一响应契约**：各协议网关输出统一 `LlmResponse`（content / toolCalls / finishReason / promptTokens / completionTokens / errorCategory），错误分类沿用 Phase C：瞬时错误（网络/429/5xx）抛 `RetryableLlmException`，业务错误（4xx 非 429）返回 `ErrorCategory.BUSINESS` 错误响应。
- **统一流式契约**：`LlmStreamCallback` 不变；各协议网关负责把自身流式格式（SSE event / NDJSON）翻译为逐增量回调 + 聚合响应。
- **配置**：`ModelConfig` 增加 `provider` 字段；`AgentProperties` 增加 `agent.provider`（默认 `openai`），专家 Agent `AgentConfig` 可覆盖 provider。
- **token 估算**：各协议网关优先解析服务端 usage；缺失时内部用 `TokenEstimator` 兜底（与 OpenAI 实现一致）。

## 4. D1 多 Provider 适配

### 4.1 代码结构

```
infrastructure/llm/
  ├─ provider/ProviderType.java        （OPENAI / ANTHROPIC / GEMINI / OLLAMA，含默认 baseUrl 推断）
  ├─ provider/ProtocolGateway.java     （协议网关接口：chat / streamChat，与 LlmGateway 同形）
  ├─ provider/ProviderRoutingGateway.java（新增分派网关，@Primary）
  ├─ provider/OpenAiProtocolGateway.java（由 LlmGatewayImpl 逻辑迁入/复用）
  ├─ provider/AnthropicProtocolGateway.java
  ├─ provider/GeminiProtocolGateway.java
  └─ provider/AbstractProtocolGateway.java（公共骨架：状态码分类、SSE 读取、usage 兜底估算）
```

### 4.2 协议差异要点

| 维度 | OpenAI | Anthropic | Gemini | Ollama |
| ---- | ---- | ---- | ---- | ---- |
| 端点 | POST /v1/chat/completions | POST /v1/messages | POST /v1beta/models/{model}:generateContent | POST /api/chat（原生）/ v1/chat/completions（兼容） |
| 认证 | Authorization: Bearer | x-api-key + anthropic-version | ?key= | 无 |
| 系统消息 | messages 内 role=system | 独立 system 字段 | system 字段 | messages 内 role=system |
| 工具定义 | tools: [{type:function,function:{name,description,parameters}}] | tools: [{name,description,input_schema}] | tools: [{functionDeclarations:[...]}] | tools: [{type:function,...}] |
| 工具调用回显 | assistant.tool_calls + role=tool 消息 | assistant.content[].tool_use + user 消息含 tool_result | content 内 functionCall + functionResponse | 同 OpenAI |
| 流式 | SSE data: chunks | SSE event: message_start/content_block_delta | SSE data: chunks（candidates） | NDJSON |
| 结束原因 | finish_reason: stop/tool_calls/length | stop_reason: end_turn/tool_use/max_tokens | finishReason: STOP | 同 OpenAI |
| usage | prompt_tokens/completion_tokens | input_tokens/output_tokens | promptTokenCount/candidatesTokenCount | prompt_eval_count/eval_count |

**实施策略**：
- Ollama：走 `OpenAiProtocolGateway`（OpenAI 兼容端点，默认 baseUrl `http://localhost:11434/v1`），零额外代码。
- Anthropic / Gemini：新增协议网关，同步 chat + tool_calls 完整支持；流式做基础适配（增量文本回调 + 聚合），工具调用流式（delta 拼装）同步实现（Anthropic content_block_delta / Gemini functionCall delta 均支持拼接）。
- `ProviderRoutingGateway`：`switch (provider)` 分派；默认 openai（向后兼容，未配置 provider 时行为与现状完全一致）。

### 4.3 配置

```yaml
agent:
  provider: openai            # openai | anthropic | gemini | ollama（默认 openai）
  model: gpt-4o               # 各 provider 的模型名
  baseUrl: ""                 # 空则按 provider 推断默认（如 anthropic=https://api.anthropic.com/v1）
  apiKey: ""
```

## 5. D2 结构化输出与多模态

### 5.1 JSON mode / 严格 schema

- `LlmRequest` 新增：
  - `String responseFormat`：`text`（默认）| `json_object` | `json_schema`
  - `JsonNode jsonSchema`：`json_schema` 时的严格 schema（Jackson JsonNode）
- 各协议网关翻译：
  - OpenAI：`response_format` = `{"type":"json_object"}` / `{"type":"json_schema","json_schema":{...}}`（json_object 时若 prompt 无 "json" 字样自动追加提示，满足 OpenAI 约束）
  - Gemini：`generationConfig.response_mime_type="application/json"` + `response_schema`（v1beta 原生支持）
  - Anthropic：无原生 response_format → 在 system 追加"仅输出合法 JSON，不要任何解释"约束段
- `JsonUtils` 新增容错提取：`JsonUtils.extractJson(String)` —— 从回复文本中提取首个 `{...}` / `[...]` 平衡括号块（容忍 markdown 代码围栏、前后缀文本），供产物解析与工具输出复用。

### 5.2 多模态输入

- `LlmMessage` 新增 `List<ContentPart> parts`（可选；非空时优先于 content 文本）。
- `ContentPart`：`type`（text / image_url / image_base64）+ `text` + `imageUrl` + `base64Data` + `mimeType`。
- 翻译：
  - OpenAI：`content` 数组化 `[{type:text},{type:image_url,image_url:{url}}]`
  - Gemini：`parts` = `[{text},{inline_data:{mime_type,data}}]`
  - Anthropic：`content` blocks = `[{type:text},{type:image,source:{type:base64,media_type,data}}]`
- 使用点：`DefaultContextAssembler` 保持文本组装（会话历史含纯文本），多模态通过 `LlmRequest.parts` 由上层显式注入（如未来图像问答场景）。本方案交付协议能力 + 单测，不做 UI 层多模态入口。

## 6. D3 模板系统增强

### 6.1 模板变量与条件（TemplateEngine）

- 新建 `TemplateEngine`（adapter 模块 util 包），用于自定义命令模板渲染，**兼容**现有 `{args}` / `{1}`-`{9}` 占位符：
  - 变量：`{{args}}`（全量参数）、`{{1}}`-`{{9}}`（第 N 个参数）、`{{date}}`（yyyy-MM-dd）、`{{time}}`（HH:mm:ss）、`{{env:NAME}}`（环境变量）
  - 条件：`{{#if 1}}有参数内容{{else}}无参数提示{{/if}}`——变量非空即真，支持 `else`，支持嵌套
- 语法说明：`{{...}}` 与旧 `{args}` 并存，旧占位符按原逻辑替换；模板无 `{{` 时行为与现状完全一致（向后兼容）。

### 6.2 产物结构化解析

- `CustomCommand` frontmatter 新增 `output: text|json`（默认 text）。
- `AgentShell.handleCustomCommand`：`output=json` 时，将 LLM 回复经 `JsonUtils.extractJson` 提取并缩进格式化展示；提取失败时告警并保留原文（不中断会话）。

## 7. 附录：技术栈升级路径（D4，本次暂不实施）

- 目标：Java 17/21 + Spring Boot 3.x（javax→jakarta、Spring Security 6 配置变化、`RestTemplate` 可平滑、HttpURLConnection 流式不受影响）。
- 影响面：`javax.annotation`→`jakarta.annotation`、`spring.factories`→`AutoConfiguration.imports`、logback 版本兼容、`SimpleClientHttpRequestFactory` 语义微调。
- 建议：独立分支迁移，用 `spring-boot-3.2.x` 基线逐模块编译；Domain 层零依赖 Java 8 API，改动集中在 infrastructure/adapter/start。

## 8. 实施任务清单与验证标准

| # | 任务 | 验证标准 |
| ---- | ---- | ---- |
| T1 | D1 多 Provider 适配：ProviderType / ModelConfig.provider / ProviderRoutingGateway / Anthropic 网关 / Gemini 网关 / Ollama 复用 | 编译通过；mock Anthropic/Gemini 协议服务器冒烟：chat + tool_calls 正常；openai 默认行为不变 |
| T2 | D2 结构化输出与多模态：responseFormat/jsonSchema 各协议翻译 + ContentPart + JsonUtils.extractJson | 单测：response_format 请求体生成、extractJson 容错（围栏/前后缀）；Gemini mock 验证 response_schema |
| T3 | D3 模板系统增强：TemplateEngine（变量/条件）+ CustomCommand.output + 结构化展示 | 模板引擎单测（变量/条件/嵌套/兼容旧占位符）；shell 冒烟：/命令 output=json 格式化展示 |
| T4 | 测试补齐 | 协议翻译单测、extractJson 单测、TemplateEngine 单测、Anthropic/Gemini mock 集成测试 |
| T5 | 全量构建 + 冒烟验证 | mvn clean test 全绿；tools/smoke.sh 扩展 provider 场景；shell 双模式冒烟 |
| T6 | 文档同步 | README（D1/D2/D3 能力与配置示例）+ 上层方案勾选 Phase D（除技术栈升级） |

## 9. 决策点

| 决策点 | 结论 |
| ---- | ---- |
| Provider 路由粒度 | 按 `ModelConfig.provider` 分派，未配置默认 openai（完全向后兼容） |
| Ollama 接入方式 | 复用 OpenAI 兼容端点，不实现原生 /api/chat（减少维护面） |
| 流式支持范围 | 四类均支持增量文本流式；工具调用流式 OpenAI/Anthropic/Gemini 均实现 delta 拼装 |
| 多模态入口 | 仅交付协议能力与单测，UI 层不新增入口（范围控制） |
| 模板语法 | `{{...}}` 新语法与 `{args}` 旧占位符并存，向后兼容 |
