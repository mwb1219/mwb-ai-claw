# feature-env-config-multi-model 技术方案

## 1. 背景与目标

当前 `application.yml` 中硬编码了模型 API Key（`agent.api-key`），存在提交代码时泄露密钥的风险；同时各专家 Agent 均继承默认 Agent 的模型配置，无法按 Agent 差异化配置模型；且后续要支持多种 Agent 协作模式（专家路由、编排、流水线等），需要一种可扩展的配置组织方式。

本方案目标：

1. 将模型相关敏感配置（`api-key` 等）抽象到 `.env` 环境变量文件，避免 Git 提交泄露。
2. 将不同协作模式的 Agent 配置**按文件区分**（`xxx-agents.json`），运行时通过启动参数选择加载哪个文件。
3. 支持不同 Agent 配置不同的模型（model / base-url / api-key / temperature / max-tokens）。

## 2. 现状分析

### 2.1 现有配置（application.yml）

```yaml
agent:
  model: deepseek-v4-flash
  base-url: https://api.deepseek.com
  api-key: "sk-06223e..."      # 硬编码敏感信息，存在泄露风险
  temperature: 0.7
  max-tokens: 2048
  agents:
    - agent-id: coder
      # 无模型配置，继承默认模型
```

### 2.2 现有模型配置加载链路

- `AgentProperties`：`@ConfigurationProperties(prefix = "agent")`，持有 `model/baseUrl/apiKey/temperature/maxTokens` 及 `agents` 列表。
- `AgentGatewayImpl.buildDefaultAgent()` / `buildAgent()`：将上述字段组装为 `ModelConfig`。
- `AgentProperties.AgentConfig`（专家 Agent）：**当前没有模型字段**，因此专家 Agent 无条件继承默认模型配置。

### 2.3 已有可复用先例

项目已实现 `mcp-server.json` 的独立配置加载（`McpServerConfigLoader`），本方案可复用同一套「独立 JSON 文件 + 自定义加载器」思路。

## 3. 方案设计

### 3.1 .env 加载机制（敏感配置抽象）

Spring Boot 2.7 默认不读取 `.env` 文件，需引入加载机制。

**方案对比**：

| 方案 | 优点 | 缺点 |
|------|------|------|
| A. `spring-dotenv` 第三方库 | 成熟、开箱即用 | 引入额外依赖 |
| B. 自定义 `EnvironmentPostProcessor` | 无额外依赖、完全可控 | 需自行实现解析 |

**推荐方案 B**：自定义 `EnvironmentPostProcessor`，理由：

- `.env` 解析逻辑简单（`KEY=VALUE`），几十行即可实现。
- 避免为单一诉求引入第三方依赖。
- 可自定义优先级、注释、引号、空值等细节。

实现要点：

```java
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        // 1. 定位 .env：优先运行目录（user.dir），回退 classpath
        // 2. 解析 KEY=VALUE（忽略 # 注释、去除引号、跳过空行）
        // 3. 包装为 MapPropertySource，addLast 注入（保证系统环境变量优先级更高）
        environment.getPropertySources().addLast(new MapPropertySource("dotenv", map));
    }
}
```

注册文件 `META-INF/spring.factories`：

```
org.springframework.boot.env.EnvironmentPostProcessor=\
  com.mwb.ai.claw.config.DotenvEnvironmentPostProcessor
```

**环境变量优先级**（由高到低）：

```
命令行参数 > 系统环境变量(systemEnvironment) > .env 文件 > 配置文件中的默认值
```

即 `.env` 仅作为「开发环境默认值」，生产环境仍可用真实系统环境变量覆盖。

### 3.2 Agent 配置按协作模式分文件

不同协作模式的 Agent 定义放入独立的 JSON 文件，命名约定 `{mode}-agents.json`：

```
routing-agents.json        # 专家路由模式（当前已实现的 coder/researcher）
orchestration-agents.json  # 未来的编排模式（planner/coder/reviewer + 协作关系）
pipeline-agents.json       # 未来的流水线模式
```

**文件位置**：`xxx-agents.json` 放在**运行目录**（`user.dir`）下，不打包进 jar 包。使用者可随时新增、调整 Agent 配置，修改后重启即可生效，无需重新构建。同时保留一份 classpath 默认模板用于首次运行引导。

**职责划分**：

| 配置位置 | 职责 |
|----------|------|
| `application.yml` | 默认 Agent 基础配置 + 非敏感的默认模型参数 + 安全沙箱 |
| 运行目录 `xxx-agents.json` | 该协作模式的 Agent 定义（含各 Agent 独立模型），用户可自由编辑 |
| `.env` | 敏感信息（api-key 等），不提交 Git |

`xxx-agents.json` 中的模型字段用 `${VAR:default}` 占位符引用环境变量，加载时由加载器解析替换，因此 JSON 文件本身不含明文密钥，可安全提交。

### 3.3 启动参数切换协作模式

通过启动参数 `--agent.mode=xxx` 决定加载哪个配置文件：

```bash
# 专家路由模式（默认，向后兼容）
java -jar app.jar --agent.mode=routing

# 编排模式（未来）
java -jar app.jar --agent.mode=orchestration

# 流水线模式（未来）
java -jar app.jar --agent.mode=pipeline
```

加载器根据 `agent.mode` 拼接文件名 `{mode}-agents.json`，加载对应文件；未指定时默认 `routing`。

### 3.4 加载机制

新增 `AgentConfigLoader`（参考 `McpServerConfigLoader`）：

```java
@Component
public class AgentConfigLoader {
    // 1. 读取 agent.mode（默认 routing），拼接 {mode}-agents.json
    // 2. 定位文件：优先运行目录，回退 classpath
    // 3. 解析 JSON 为 AgentConfig 列表
    // 4. 对 model/apiKey 等字段做 ${VAR} 占位符替换（复用 Spring Environment）
}
```

`AgentGatewayImpl` 改为从 `AgentConfigLoader` 获取 Agent 列表，而非直接读 `AgentProperties.agents`。

### 3.5 多 Agent 多模型（与协作模式解耦）

模型配置是每个 Agent 的**通用基础属性**，与「专家路由」或「协作」等组织模式无关。将 Agent 配置分为两层：

```java
public static class AgentConfig {
    // ===== 通用基础配置（所有组织模式共用）=====
    private String agentId;
    private String name;
    private String systemPrompt;
    private List<String> tools;
    private Integer maxSteps;

    // 模型配置（可选，为空则继承默认；用包装类型区分「未配置」与「配置 0」）
    private String model;
    private String baseUrl;
    private String apiKey;
    private Double temperature;
    private Integer maxTokens;

    // ===== 组织模式元数据（按模式扩展）=====
    // 专家路由模式使用：
    private String description;      // LLM 路由用
    private List<String> keywords;   // 规则路由用

    // 未来协作模式预留（编排/流水线等，本次不实施）：
    // private String role;            // orchestrator / worker
    // private List<String> dependsOn; // 协作依赖关系
}
```

`AgentGatewayImpl.buildAgent()` 按「Agent 显式配置 > 默认配置」合并模型字段：

```java
String model = config.getModel() != null ? config.getModel() : agentProperties.getModel();
String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : agentProperties.getBaseUrl();
String apiKey = config.getApiKey() != null ? config.getApiKey() : agentProperties.getApiKey();
double temperature = config.getTemperature() != null
        ? config.getTemperature() : agentProperties.getTemperature();
int maxTokens = config.getMaxTokens() != null
        ? config.getMaxTokens() : agentProperties.getMaxTokens();
```

### 3.6 协作模式演进

由于 Agent 配置已按协作模式分文件，未来新增协作模式只需：

1. 新增一个 `{mode}-agents.json` 文件（定义该模式的 Agent + 协作关系）。
2. 新增对应的协作编排服务（类似 `ReActLoopService` 之于单 Agent）。
3. 通过 `--agent.mode={mode}` 启动参数切换。

每个参与的 Agent 复用现有「基础配置 + 模型配置」，可各自使用不同模型，无需改动配置加载框架。

## 4. 配置示例

### 4.1 .env 文件（不提交 Git）

```bash
# 默认模型
DEFAULT_MODEL=deepseek-chat
DEFAULT_BASE_URL=https://api.deepseek.com
DEFAULT_API_KEY=sk-default-xxx

# coder 专家（独立模型）
CODER_MODEL=deepseek-coder
CODER_API_KEY=sk-coder-xxx

# researcher 专家（key 留空则继承 DEFAULT_API_KEY）
RESEARCHER_MODEL=deepseek-chat
RESEARCHER_API_KEY=
```

### 4.2 .env.example 模板（提交 Git，供团队成员参考）

```bash
DEFAULT_MODEL=deepseek-chat
DEFAULT_BASE_URL=https://api.deepseek.com
DEFAULT_API_KEY=

CODER_MODEL=
CODER_API_KEY=

RESEARCHER_MODEL=
RESEARCHER_API_KEY=
```

### 4.3 application.yml（仅保留默认基础配置）

```yaml
agent:
  agent-id: default
  name: mwb-ai-claw
  system-prompt: 你是 mwb-ai-claw 智能助手...
  mode: routing                        # 协作模式，默认 routing
  model: ${DEFAULT_MODEL:deepseek-chat}
  base-url: ${DEFAULT_BASE_URL:https://api.deepseek.com}
  api-key: ${DEFAULT_API_KEY:}
  temperature: 0.7
  max-tokens: 2048
  max-steps: 8
  tools:
    - echo
    - http
    - file
    - shell
    - read_memory
    - write_memory
  security:
    # ... 安全沙箱配置保持不变 ...
```

### 4.4 routing-agents.json（专家路由模式）

```json
{
  "mode": "routing",
  "agents": [
    {
      "agentId": "coder",
      "name": "编码专家",
      "description": "擅长编写代码、调试 bug、代码审查与技术实现",
      "keywords": ["代码", "bug", "实现", "开发", "调试"],
      "systemPrompt": "你是资深软件工程师，擅长编码、调试与问题排查。",
      "tools": ["file", "shell", "http", "read_memory", "write_memory"],
      "maxSteps": 10,
      "model": {
        "model": "${CODER_MODEL:${DEFAULT_MODEL:deepseek-chat}}",
        "baseUrl": "${DEFAULT_BASE_URL:}",
        "apiKey": "${CODER_API_KEY:${DEFAULT_API_KEY:}}"
      }
    },
    {
      "agentId": "researcher",
      "name": "信息检索专家",
      "description": "擅长信息检索、资料查询与知识整理",
      "keywords": ["搜索", "查询", "资料", "调研"],
      "systemPrompt": "你是信息检索专家，擅长高效检索网络信息并归纳整理。",
      "tools": ["http", "read_memory"],
      "maxSteps": 5,
      "model": {
        "model": "${RESEARCHER_MODEL:${DEFAULT_MODEL:deepseek-chat}}",
        "apiKey": "${RESEARCHER_API_KEY:${DEFAULT_API_KEY:}}"
      }
    }
  ]
}
```

### 4.5 orchestration-agents.json（编排模式，未来示意）

```json
{
  "mode": "orchestration",
  "orchestrator": "planner",
  "workers": ["coder", "reviewer"],
  "agents": [
    {
      "agentId": "planner",
      "name": "任务规划者",
      "role": "orchestrator",
      "systemPrompt": "你是任务规划者，负责拆解任务并分派给执行者。",
      "model": {
        "model": "${PLANNER_MODEL:${DEFAULT_MODEL:}}",
        "apiKey": "${PLANNER_API_KEY:${DEFAULT_API_KEY:}}"
      }
    }
  ]
}
```

## 5. 实施步骤

1. 新增 `DotenvEnvironmentPostProcessor`，实现 `.env` 解析与注入。
2. 新增 `META-INF/spring.factories` 注册该 Processor。
3. 新增 `AgentConfigLoader`，根据 `agent.mode` 加载 `{mode}-agents.json` 并解析占位符。
4. `application.yml` 移除 `agents` 列表与硬编码 key，改为 `${}` 引用 + `agent.mode`。
5. 新增 `routing-agents.json`（迁移现有 coder/researcher 配置）。
6. `AgentProperties.AgentConfig` 增加 `model/baseUrl/apiKey/temperature/maxTokens` 字段。
7. `AgentGatewayImpl` 改为从 `AgentConfigLoader` 获取 Agent 列表，并实现模型字段合并。
8. `.gitignore` 增加 `.env`，新增 `.env.example` 模板并提交。

## 6. 影响范围

- **新增**：`DotenvEnvironmentPostProcessor`、`AgentConfigLoader`、`META-INF/spring.factories`、`.env`、`.env.example`、`routing-agents.json`
- **修改**：`AgentProperties`、`AgentGatewayImpl`、`application.yml`、`.gitignore`
- **不影响**：ReAct 循环、路由策略、工具调用、MCP 配置等

## 7. 风险与注意点

- `.env` 必须加入 `.gitignore`，历史提交中已泄露的 key 建议轮换。
- `.env` 优先级低于系统环境变量，生产部署推荐直接注入系统环境变量。
- `xxx-agents.json` 放在运行目录，不打包进 jar；首次运行需依赖 classpath 默认模板引导（或手动创建）。
- `xxx-agents.json` 中的 `${VAR}` 占位符需由加载器解析（不能依赖 Spring 自动解析，因为 JSON 非 Spring 配置体系）。
- `MCP` 的密钥（`mcp-server.json` 中的 `TAVILY_API_KEY` 等）属于另一配置体系，可后续按同样思路处理，本方案暂不覆盖。
