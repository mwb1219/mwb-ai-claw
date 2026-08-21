# feature-PhaseA-服务端生产化技术方案（SUBMIT）

> 对应《mwb-ai-claw 框架化与双模式演进方案》路线图 **Phase A：服务端生产化（优先）**。
>
> 覆盖范围：**多租户/用户维度、JDBC/Redis 持久化、Session 并发锁、认证鉴权** 四项。
> 状态：SUBMIT（待评审）
>
> **修订记录（2026-08-19）**：按项目最新迭代（编排工具化与 delegate 编排、人工审批流、Shell 审批三档、MCP 动态注册、ReAct 步数扩展）重新走查并修订：
> - 现状基线更新：`OrchestrationSelector`/`PipelineOrchestrator` 已移除，新增 `TodoDelegateOrchestrator` + `OrchestratorRegistry` + 协作工具（`invoke_*`）；新增人工审批流（`ToolApproval` / `ApprovalService` / `ApprovalRegistry` / `ApprovalController`）。
> - 总体设计：编排链路改为「ChatCmdExe → OrchestratorRegistry → 编排器」，scope 经 `OrchestrationContext` 传播，嵌套编排透传。
> - 多租户：`AgentGateway` 修正为全局注册表（不加 scope），用户隔离落在 `LongTermMemoryGateway`；审批注册表与协作产物目录纳入 scope。
> - 并发锁：加锁点下沉至执行单元/编排器主会话粒度（原 ChatCmdExe 加锁描述作废）。
> - 鉴权：WS 协议新增 approve/reject/pending_tasks 消息；`ToolPermissionChecker`（静态授权）与 `ToolApproval`（人工审批门）层次互补说明。

---

## 1. 概述

### 1.1 目标

在现有单体应用形态下，优先补齐服务端生产化能力，让 mwb-ai-claw 可作为**多用户、多租户、高并发的服务端 Agent 应用**运行：

| 能力 | 现状 | 目标 |
| ---- | ---- | ---- |
| 租户/用户隔离 | 无，所有用户共享同一份会话与记忆 | 按 tenant+user 命名空间隔离存储与检索 |
| 持久化 | 仅文件系统，并发不安全 | 存储后端可切换：`file \| jdbc \| redis` |
| 并发安全 | 同会话并发写会损坏 | Session 级锁 + 乐观版本 |
| 认证鉴权 | REST/WS/SSE 无静态认证；已有交互式人工审批门（Shell `ask` / delegate `approvalGate`） | API Key 认证 + 租户级 + 工具级静态权限（与人工审批门分层互补） |

### 1.2 设计原则

1. **显式 Scope 贯穿**：租户/用户维度作为值对象 `AgentScope` 显式传入存储端口（依赖倒置友好、可测试），不依赖全局隐式状态。
2. **端口不变式**：domain 只新增/改造端口方法签名，不引入任何技术实现；JDBC/Redis 均为 infrastructure 层实现。
3. **向后兼容**：`agent.tenant.enabled=false`（默认）时保持现有 `.agent/` 目录布局与行为不变，存量数据不受影响。
4. **最小侵入**：认证鉴权默认关闭，关闭时行为与现状一致。
5. **Scope 单点传播**：编排链路（含嵌套编排、审批流）的 scope 收敛到 `OrchestrationContext` 与 `AgentScopeContext` 两个传播点，避免散落传参造成透传遗漏。

### 1.3 术语

| 术语 | 说明 |
| ---- | ---- |
| `AgentScope` | 租户/用户维度值对象：`tenantId` + `userId` |
| `ScopeResolver` | 从请求/认证上下文解析当前 `AgentScope` 的 SPI |
| `SessionLockManager` | 会话级锁端口（本地/Redis 两种实现） |
| `OrchestrationContext` | 编排上下文，新增 `scope` 字段——编排器与嵌套编排取 scope 的唯一入口 |
| `ToolPermissionChecker` | 工具级静态授权端口（本 Phase 新增）；与 `ToolApproval` 人工审批门分层 |
| 存储后端 | `agent.storage.type` 指定的持久化实现（file/jdbc/redis） |

---

## 2. 现状走查结论（改造基线，2026-08-19 按最新代码复核）

> 复核基线：最新迭代已落地「编排工具化 + delegate 编排 + 人工审批流 + Shell 审批三档 + MCP 动态注册」（见修订记录）。

| 模块 | 关键类 | 现状问题 |
| ---- | ------ | -------- |
| domain | `MemoryGateway` / `MemoryPageStore` / `LongTermMemoryGateway` / `MemoryRetriever` / `LayeredMemoryGateway` | 所有方法均**无租户/用户维度**；`facts` 全局共享、检索跨全部会话 |
| domain | `Session` 聚合根 | 无 `tenantId` / `userId` / 版本号字段 |
| domain | `AgentGateway` | `getAgent(String)` 无 scope；Agent 定义来自**全局 `agents.json` 注册表**（跨用户共享，不应 scope 化），用户隔离应落在 `LongTermMemoryGateway.loadAgentInstructions`（AGENT.md） |
| domain | `ToolGateway` / `ToolExecutor` | `execute` 无 scope 参数；`ToolGatewayImpl.execute(toolName, args, callback)` 为统一执行入口（权限校验注入点），新增了进度回调重载 |
| domain | `ToolApproval` | 已有**交互式人工审批门 SPI**（Shell ask 模式 / delegate `approvalGate`）；与静态授权（`ToolPermissionChecker`）层次不同，需在设计上区分 |
| infrastructure | `FileBasedSessionGateway` | 缓存 + 文件写入无锁，并发写会丢数据/损坏文件 |
| infrastructure | `FileMemoryPageStore` | `facts.jsonl` 单文件全局追加，无并发保护 |
| infrastructure | `LayeredMemoryGatewayImpl` | `doAfterTurn` / `doAfterSession` 异步任务只传 `sessionId`，未传 scope |
| infrastructure | `VectorMemoryRetriever` / `SynthesisCache` / `MemorySynthesisExecutor` | 向量磁盘缓存 `.agent/memory/vectors/` 全局共享；按内容哈希去重跨用户可能互相命中；`submit(taskId, task)` 无 scope |
| infrastructure | `OrchestrationContext` / `ChatCmdExe` / `OrchestratorRegistry` | 编排上下文**无 scope 字段**；协作工具 `invoke_discussion` / `invoke_delegate` 经 `ExecutionUnit.runOrchestration` 发起**嵌套编排**，链路全程不携带 scope |
| infrastructure | `ApprovalRegistry` / `PendingApproval` / `ApprovalService` | 审批注册 key = `{sessionId}/{layerKey}` **无 scope**，多租户下跨用户冲突；审批 API / WS 审批消息无鉴权 |
| infrastructure | `TodoDelegateOrchestrator` | 主会话经 `ExecutionUnit` 处理无 scope；编排产物落盘 `{workdir}/{sessionId}/{时间戳}` 为全局共享目录 |
| adapter | `AgentController` / `AgentWebSocketHandler` / `MemoryController` / `ApprovalController` | 无任何鉴权；WS 协议含 `chat` / `approve` / `reject` / `pending_tasks` 四类消息需统一身份解析；`MemoryController` 直接读全局 pageStore |
| app | `ChatCmdExe` / `ExecutionUnitImpl` / `AgentServiceImpl` | `getOrCreateSession` 无 scope，会话创建无归属；`runOrchestration` 嵌套编排无 scope 透传 |
| 测试 | `TodoDelegateOrchestratorTest` / `CollaborationToolTest` | 新增的存量测试直接 mock 存储端口，端口签名改造（T2）将波及，需同步适配 |

---

## 3. 总体设计

### 3.1 架构视图：Scope 贯穿各层

```
 请求入口（REST / SSE / WS / Shell，WS 含 chat / approve / reject / pending_tasks）
      │
      ▼
 AuthFilter / WS握手 / SSE参数  ──► 认证通过后解析身份
      │
      ▼
 AgentScopeResolver.resolve() ──► AgentScope(tenantId, userId)
      │（写入 AgentScopeContext，供工具执行器 / 协作工具 / 审批服务读取）
      ▼
 ChatCmdExe ──► OrchestratorRegistry.resolve(def) ──► orchestrator.orchestrate(ctx)
      │              │ OrchestrationContext 携带 AgentScope（新增字段，scope=ctx.getScope()）
      ▼              ▼
 ExecutionUnit.getOrCreateSession(scope, ...) / runSession / runOrchestration(scope, ...)
      │              │ Session 聚合根携带 tenantId/userId；嵌套编排（invoke_*）透传 scope
      ▼              ▼
 ReActLoopService ──► LayeredMemoryGateway.readContext(session, agent)
      │              │ session.getScope() 显式传给存储端口
      ▼              ▼
 MemoryGateway / MemoryPageStore / LongTermMemoryGateway / MemoryRetriever
      │              │ 全部方法签名增加 AgentScope
      ▼              ▼
 存储后端（file ｜ jdbc ｜ redis）+ SessionLockManager（并发保护，主会话粒度）
```

- **显式传参为主**：存储端口、异步任务、嵌套编排均显式携带 `AgentScope`；
- **ThreadLocal 为辅**：`AgentScopeContext` 仅在请求入口设置/清理，供工具执行器（`read_memory`/`write_memory`）、协作工具（`invoke_*`）与审批服务读取；异步任务不依赖 ThreadLocal（显式传参）；
- **编排链路说明**：`ChatCmdExe` 不再有意图选择器，而是 `orchestratorRegistry.resolve(definition)` 分发到编排插件；`OrchestrationContext` 新增 `scope` 字段作为编排器（routing / conversational / delegate）取 scope 的唯一入口；协作工具发起的嵌套编排（`ExecutionUnit.runOrchestration`）从上层 ctx / `AgentScopeContext` 读取 scope 并透传进嵌套 ctx。

### 3.2 关键抽象：AgentScope（domain）

```java
/**
 * 租户/用户维度值对象。tenantId/userId 为空表示「默认空间」（legacy 根目录，兼容模式）。
 */
@Value
public class AgentScope {
    String tenantId;
    String userId;

    public static AgentScope of(String tenantId, String userId) { ... }
    public static AgentScope defaultScope() { return new AgentScope(null, null); }

    /** 是否启用租户隔离（tenantId 非空） */
    public boolean isTenanted() { return tenantId != null && !tenantId.isEmpty(); }

    /** 存储命名空间 key（文件目录 / 表前缀 / Redis key 前缀统一使用） */
    public String namespace() {
        return isTenanted() ? tenantId + "/" + userId : null;   // null → 使用 legacy 根
    }
}
```

### 3.3 配置模型（application.yml 新增）

```yaml
agent:
  # ===== 租户/用户隔离 =====
  tenant:
    enabled: false                 # false：单用户 legacy 模式（目录不变）；true：按 tenant/user 隔离

  # ===== 存储后端 =====
  storage:
    type: file                     # file（默认）| jdbc | redis
    # type=jdbc 时复用 spring.datasource（H2/MySQL/PG），建表脚本见 5.2
    # type=redis 时复用 spring.redis（Lettuce），key 设计见 5.3

  # ===== 认证鉴权 =====
  auth:
    enabled: false                 # 默认关闭（关闭时行为与现状一致）
    header: X-API-Key              # 认证 Header（也支持 Authorization: Bearer）
    api-keys:                      # 静态 API Key 映射（也可放 auth.json 外部化）
      "tenant-a":
        "user-1": "sk-tenant-a-user-1"
    default-user: default          # 未配置工具权限时的兜底 user
    tool-permissions:              # 工具级权限（缺省 = 全部允许）
      "user-1": ["file", "http"]   # 该用户仅可用这些工具
```

### 3.4 编排上下文 scope 传播（新迭代新增环节）

`OrchestrationContext`（domain.collaboration）新增 `scope` 字段，作为编排器与嵌套编排取 scope 的唯一入口：

```java
public class OrchestrationContext {
    private AgentScope scope;   // 新增：请求级租户/用户维度；嵌套编排由上层 ctx 透传
    // ... 其余字段不变
}
```

传播路径（覆盖编排工具化后的真实链路）：

1. `ChatCmdExe.execute` 装配 ctx 时 `ctx.setScope(scopeResolver.resolve())`（默认实现返回 `AgentScope.defaultScope()`）；
2. 编排器（routing / conversational / delegate）从 `ctx.getScope()` 读取，传给 `ExecutionUnit.getOrCreateSession(scope, ...)` 与 `runSession`；
3. **嵌套编排**：主 Agent 在 ReAct 内调用协作工具 `invoke_discussion` / `invoke_delegate` → `AbstractCollaborationTool.execute` → `ExecutionUnit.runOrchestration(message, orchestrationId, callback, scope)`（scope 从 `AgentScopeContext` / 上层 ctx 读取并透传），嵌套 ctx 继承同一 scope；
4. **临时会话**（`runAgent`，conversational 参与者 / delegate 叶子子 Agent，不入库）不落库无需 scope 持久化，但检索召回与工具执行仍以 ctx/ThreadLocal scope 隔离；
5. delegate 编排内部 `approvalRegistry.register(scope, sessionId, layerKey, ...)` 与产物落盘 `{workdir}/{scope.namespace()}/{sessionId}/{时间戳}` 同样使用 scope。

---

## 4. 多租户与用户维度设计

### 4.1 存储端口改造（接口签名变更）

**MemoryGateway（会话）**：

```java
void saveSession(Session session);                      // scope 取自 session 内部字段
Session getSession(AgentScope scope, String sessionId);
List<Session> listSessions(AgentScope scope);
void deleteSession(AgentScope scope, String sessionId);
```

**MemoryPageStore（摘要/事实/档案）**：

```java
void saveSummary(AgentScope scope, MemoryPage page);
List<MemoryPage> loadSummaries(AgentScope scope, String sessionId);
List<MemoryPage> listAllSummaries(AgentScope scope);
void appendFact(AgentScope scope, MemoryPage fact);
List<MemoryPage> loadFacts(AgentScope scope);
void deleteFact(AgentScope scope, String key);
void deleteSessionPages(AgentScope scope, String sessionId);
void saveArchive(AgentScope scope, MemoryPage page);
List<MemoryPage> loadArchive(AgentScope scope, String sessionId);
List<MemoryPage> listAllArchive(AgentScope scope);
void deleteSessionArchive(AgentScope scope, String sessionId);
```

**LongTermMemoryGateway（AGENT.md / MEMORY.md）**：

```java
String loadAgentInstructions(AgentScope scope);
String loadMemory(AgentScope scope);
void saveMemory(AgentScope scope, String content);
```

**MemoryRetriever（检索隔离）**：

```java
List<MemoryPage> search(AgentScope scope, String query, int topK);
```

**LayeredMemoryGateway**：`readContext(session, agent)` / `afterTurn` / `afterSession` 签名不变，scope 取自 `session.getScope()`；内部异步任务（`doAfterTurn`/`doAfterSession`/`archiveMessages`）改为显式接收 scope。

**AgentGateway（Agent 构建）——新迭代修正：不加 scope**：

```java
Agent getAgent(String agentId);        // 不变：agents.json 为全局注册表，跨用户共享
List<Agent> listAgents();
```

> **设计修正（2026-08-19）**：初版方案曾建议 `getAgent(AgentScope, ...)`，复核最新实现后确认 **Agent 定义来自全局 `agents.json`（`AgentRegistryLoader`），天然跨用户共享，不应按用户隔离**；真正按用户隔离的是 AGENT.md 系统指令（`LongTermMemoryGateway.loadAgentInstructions(scope)`，见上），该端口需要加 scope。`AgentGateway` 保持签名不变可减少一处无谓改动面。

### 4.2 Session 聚合根改造

```java
public class Session {
    private String sessionId;
    private String tenantId;      // 新增：归属租户（可空）
    private String userId;        // 新增：归属用户（可空）
    private long version;         // 新增：乐观版本号，save 时 +1
    // ... 其余字段不变

    public AgentScope getScope() { return AgentScope.of(tenantId, userId); }
}
```

`ExecutionUnitImpl.getOrCreateSession(scope, sessionId, agent)` 创建时写入 `tenantId`/`userId`。

### 4.3 文件模式目录布局（含兼容策略）

- `agent.tenant.enabled=false`（默认）：**保持现有 `.agent/` 布局不变**，存量数据零迁移；
- `agent.tenant.enabled=true`：

```
.agent/
└── {tenant}/{user}/
    ├── AGENT.md
    ├── MEMORY.md
    ├── sessions/{sessionId}.json
    ├── workdir/{sessionId}/{时间戳}/      # delegate 编排产物（plan.json/result.txt），按 scope 分目录
    └── memory/
        ├── facts.jsonl
        ├── pages/{sessionId}/summary-*.json
        ├── archive/{sessionId}/archive-*.json
        └── vectors/{encodedPageId}.json     # pageId 编码前缀含 scope，杜绝跨用户冲突
```

实现：文件实现内部以 `scope.namespace()` 计算根路径；`null` 时回落现有根路径（`Path` 拼接天然兼容）。

### 4.4 检索隔离与缓存隔离

| 缓存 | 现状 | 改造 |
| ---- | ---- | ---- |
| `VectorMemoryRetriever.cache` | 内存 map 全局共享 | pageId 前缀加入 scope；磁盘路径按 scope 分目录 |
| `SynthesisCache` | 按内容哈希全局去重 | 缓存 key = `scope + contentHash`，杜绝跨用户命中 |
| `MemorySynthesisExecutor` | `submit(taskId, task)` | 签名改为 `submit(scope, taskId, task)`，任务内显式使用 scope |
| delegate 产物目录 | `{workdir}/{sessionId}/{时间戳}` 全局共享 | `TodoDelegateOrchestrator` 落盘前先拼 `scope.namespace()`（多用户产物互不可见） |

### 4.5 身份解析与传播（ScopeResolver）

```java
/** 请求 → AgentScope 解析 SPI */
public interface AgentScopeResolver {
    AgentScope resolve();
}

// 服务端实现：读 AgentScopeContext（Auth 拦截器设置）
// 客户端/默认实现：AgentScope.defaultScope()
```

流程：请求进入 → `AuthFilter`（或 WS 握手/SSE 参数）完成认证 → 写 `AgentScopeContext` → 应用层 `scopeResolver.resolve()` 取 scope → 显式传给各端口。

**WS 四类消息统一解析**（新迭代协议：`chat` / `approve` / `reject` / `pending_tasks`）：握手阶段鉴权后将 scope 写入 WS session attributes；业务线程处理任一消息类型前，先解析 scope 写 `AgentScopeContext`——`chat` 进入 `ChatCmdExe`，`approve`/`reject`/`pending_tasks` 进入 `ApprovalService`（按 `(scope, sessionId, layerKey)` 定位待审批节点）。

---

## 5. 持久化抽象设计

### 5.1 存储后端选择与装配

- 端口不变（`MemoryGateway` / `MemoryPageStore` / `LongTermMemoryGateway`），按 `agent.storage.type` 装配不同实现：
  - `file`（默认）：现有 `FileBasedSessionGateway` / `FileMemoryPageStore` / `FileBasedMemoryGateway`（加 scope + 锁）
  - `jdbc`：新增 `JdbcSessionGateway` / `JdbcMemoryPageStore` / `JdbcLongTermMemoryGateway`
  - `redis`：新增 `RedisSessionGateway` / `RedisMemoryPageStore` / `RedisLongTermMemoryGateway`
- 装配方式：实现类标注 `@ConditionalOnProperty(name = "agent.storage.type", havingValue = "...")`，保证同一端口只有一个实现生效；
- 依赖：infrastructure 增加 `spring-boot-starter-jdbc` 与 `spring-boot-starter-data-redis`（**Phase B 框架化时拆为独立存储模块**，避免客户端运行时承载）。

### 5.2 JDBC 实现

表结构（`spring.sql.init` 或 Flyway 初始化）：

```sql
-- 会话表：messages 以 JSON CLOB 存储（与现有 Session 序列化一致）
CREATE TABLE claw_session (
    tenant_id   VARCHAR(64)  NULL,
    user_id     VARCHAR(64)  NULL,
    session_id  VARCHAR(64)  NOT NULL,
    agent_id    VARCHAR(64),
    title       VARCHAR(128),
    status      VARCHAR(16),
    version     BIGINT       DEFAULT 0,
    create_time BIGINT       NOT NULL,
    update_time BIGINT       NOT NULL,
    messages    CLOB,
    PRIMARY KEY (tenant_id, user_id, session_id)
);
CREATE INDEX idx_session_update ON claw_session(tenant_id, user_id, update_time DESC);

-- 事实表（同 key 合并去重落在 DB 层）
CREATE TABLE claw_fact (
    tenant_id   VARCHAR(64)  NULL,
    user_id     VARCHAR(64)  NULL,
    fact_key    VARCHAR(128) NOT NULL,
    content     CLOB         NOT NULL,
    importance  DOUBLE       NOT NULL,
    session_id  VARCHAR(64),
    version     INT          DEFAULT 1,
    token_count INT,
    create_time BIGINT,
    update_time BIGINT,
    PRIMARY KEY (tenant_id, user_id, fact_key)
);

-- 记忆页表（SUMMARY / ARCHIVE 统一存储）
CREATE TABLE claw_memory_page (
    tenant_id   VARCHAR(64)  NULL,
    user_id     VARCHAR(64)  NULL,
    page_id     VARCHAR(128) NOT NULL,
    page_type   VARCHAR(16)  NOT NULL,   -- SUMMARY | ARCHIVE
    session_id  VARCHAR(64),
    block_start INT,
    block_end   INT,
    content     CLOB,
    token_count INT,
    create_time BIGINT,
    PRIMARY KEY (tenant_id, user_id, page_id)
);
```

要点：
- `(tenant_id, user_id)` 允许 NULL = legacy 默认空间，与文件模式语义对齐；
- 事实写入使用 `INSERT ... ON DUPLICATE KEY UPDATE`（MySQL）/ `MERGE`（PG/H2），替换现有「先 delete 再 append」；
- 会话消息暂以 JSON CLOB 存储（一期），消息表规范化列入后续优化。

### 5.3 Redis 实现

Key 设计（统一前缀 `claw:`）：

```
claw:{tenant}:{user}:session:{sessionId}        → Hash（session 元数据 + messages JSON 字段）
claw:{tenant}:{user}:sessions:index             → ZSet（sessionId, score=updateTime，列表倒序）
claw:{tenant}:{user}:facts                      → Hash（fact_key → JSON）
claw:{tenant}:{user}:pages:{sessionId}:summary  → Hash（blockStart → JSON）
claw:{tenant}:{user}:pages:{sessionId}:archive  → Hash（blockStart → JSON）
claw:{tenant}:{user}:lock:{sessionId}           → 分布式锁（见第 6 节）
```

要点：
- `tenant`/`user` 为空时 key 退化为 `claw:default:default:...`（与文件模式 legacy 语义一致）；
- `facts` 用 Hash 天然支持同 key 合并（`HSET` 覆盖）；
- TTL：会话 key 无 TTL（持久数据）；仅锁 key 带 TTL。

---

## 6. Session 并发锁设计

### 6.1 端口（domain 或 infrastructure？——放 infrastructure 层，锁是技术关注点）

```java
/** 会话级锁管理器：保证同一会话的 ReAct 执行串行化 */
public interface SessionLockManager {
    <T> T executeWithLock(AgentScope scope, String sessionId, Supplier<T> task);
    void executeWithLock(AgentScope scope, String sessionId, Runnable task);
}
```

### 6.2 本地实现（默认，JVM 内）

- `Guava Striped<Lock>`（64 stripes）或 `ConcurrentHashMap<LockKey, ReentrantLock>` + 引用计数清理；
- 锁 key = `scope.namespace() + ":" + sessionId`；
- 适用于单实例部署（当前形态）。

### 6.3 Redis 实现（分布式）

- `SET key token NX PX 30000` 获取，Lua 脚本（`KEYS[1]==token` 才 `DEL`）释放；
- 锁 key：`claw:{tenant}:{user}:lock:{sessionId}`，TTL 30s，看门狗续期（一期可简化为固定 TTL + 任务内重入保护）；
- 适用于多实例部署。

### 6.4 加锁范围（新迭代后更新）

> 新迭代后 `ChatCmdExe` 变为编排分发器（不再直接持有会话生命周期），原「ChatCmdExe 加锁」描述作废。锁下沉到**执行单元 / 编排器主会话粒度**：

```
ChatCmd 到达 → ChatCmdExe.resolve(编排) → 编排器 orchestrate(ctx)
   routing:    getOrCreateSession(scope, sessionId) → ReAct 全程 → saveSession → afterSession
   delegate:   主 Agent 会话（规划 → 委派 → 汇总）全程
   conversational / 嵌套编排：主会话同样经 ExecutionUnit 处理
   └────────────────── SessionLockManager.executeWithLock(scope, sessionId) ──────────────────┘
```

- 加锁 API 收敛到 `ExecutionUnit`（如 `executionUnit.executeWithSessionLock(scope, sessionId, task)`），编排器统一调用，避免各编排插件自行加锁造成遗漏；
- 锁只覆盖**主会话**「读 → 追加 → 推理 → 保存」全程，保证同会话消息顺序与持久化一致；不同会话 / 不同用户完全并行；
- **不加锁**的场景：`runAgent` 临时会话（conversational 参与者 / delegate 叶子子 Agent，不入库，无持久化竞争）；异步提炼（afterTurn/afterSession，写记忆页由原子写保证）；
- 新增配置 `agent.storage.lock-type: local | redis`（默认 local；`storage.type=redis` 时默认 redis）。

---

## 7. 认证鉴权设计

### 7.1 配置模型（见 3.3）

### 7.2 认证流程

**REST（同步/SSE）**：
- 同步接口：`AuthInterceptor`（HandlerInterceptor）校验 `X-API-Key` / `Authorization: Bearer` → 映射 `tenantId/userId` → 写 `AgentScopeContext`；
- SSE：EventSource 无法自定义 Header → 支持 `?apiKey=` 查询参数校验（一期），并提示生产环境前置网关鉴权；
- 认证关闭（`enabled=false`）时拦截器直接放行，scope = default。

**WebSocket**：
- `HandshakeInterceptor` 在握手阶段校验（URL query `apiKey` 或 Header），校验通过将 scope 写入 WS session attributes；
- 业务线程从 attributes 解析 scope 写 `AgentScopeContext`；
- **协议覆盖全部四类消息**：`chat`（进入 `ChatCmdExe`）+ `approve` / `reject` / `pending_tasks`（进入 `ApprovalService`，按 `(scope, sessionId, layerKey)` 定位，防止跨用户审批他人节点）。

### 7.3 工具级权限（静态授权）与人工审批门（动态确认）分层

> 新迭代已存在 `ToolApproval`（domain 人工审批门 SPI）。静态授权与人工审批是**两个层次，互补而非替代**：

| 层次 | 端口 | 职责 | 接入点 |
| ---- | ---- | ---- | ---- |
| 静态授权 | `ToolPermissionChecker`（本 Phase 新增） | API Key → 用户 → 可用工具白名单；无权直接拒绝 | `ToolGatewayImpl.execute`（含进度回调重载）统一入口 |
| 人工审批门 | `ToolApproval`（已有） | 高危命令（Shell `ask` 模式 / delegate `approvalGate`）在执行前征求用户确认 | `ShellTool` / `TodoDelegateOrchestrator` |

执行判定顺序：**静态授权拒绝 → 直接返回 `ToolResult.error("无权限调用工具: xxx")`（不中断 ReAct）**；静态授权通过但命中审批规则 → 转人工审批门。

```java
public interface ToolPermissionChecker {
    /** 当前 scope 是否允许调用该工具；未启用鉴权/无配置时返回 true */
    boolean isAllowed(AgentScope scope, String toolName);
}
```

- `ToolGatewayImpl.execute(toolName, args, callback)` 入口调用 `checker.isAllowed`（该重载是新迭代后的统一执行入口）；
- 实现读 `agent.auth.tool-permissions`，按 `userId`（可扩展 tenant 级）匹配；
- scope 来源：`AgentScopeContext`（请求线程内执行，主 Agent 与协作工具调用同线程可读）。

### 7.4 身份 → Scope 解析

```
API Key（sk-xxx）──► 查 agent.auth.api-keys 映射 ──► (tenantId, userId) ──► AgentScope
```

- 未配置的 key：401；
- key 存在但用户未配置工具权限：视为「全部允许」。

### 7.5 安全边界声明（本 Phase 边界）

- API Key 以明文存于配置/`auth.json`（`.env` 注入，建议哈希存储列入后续优化）；
- 限流（rate limiting）与防注入列入 Phase C，不在本 Phase 范围。

---

## 8. 改造影响面与兼容性

### 8.1 改动清单（按模块）

| 模块 | 改动 |
| ---- | ---- |
| domain | 新增 `AgentScope`；`Session` 增加 tenantId/userId/version；`MemoryGateway`/`MemoryPageStore`/`LongTermMemoryGateway`/`MemoryRetriever` 方法签名增加 scope；`OrchestrationContext` 增加 scope 字段；新增 `AgentScopeResolver` 端口（`AgentGateway` 保持全局注册表不加 scope，见 4.1 修正说明） |
| infrastructure | `FileBasedSessionGateway`/`FileMemoryPageStore`/`FileBasedMemoryGateway` 增加 scope 路径与并发保护；`LayeredMemoryGatewayImpl` 异步任务传 scope；`VectorMemoryRetriever`/`SynthesisCache`/`MemorySynthesisExecutor` scope 化；`TodoDelegateOrchestrator` 主会话加锁 + 产物目录 scope 化；`ApprovalRegistry` key 增加 scope；协作工具 `AbstractCollaborationTool` scope 透传；新增 `storage/jdbc/*`、`storage/redis/*`、`lock/*`（local/redis）、`auth/*`（拦截器、ToolPermissionChecker） |
| app | `ChatCmdExe` 解析 scope 写入 `OrchestrationContext`；`ExecutionUnitImpl.getOrCreateSession` 接收 scope 并写入 Session、`runOrchestration` 透传 scope、新增 `executeWithSessionLock`；`ApprovalService` 按 `(scope, sessionId, layerKey)` 定位待审批节点 |
| adapter | `AgentController`/`AgentWebSocketHandler`/`MemoryController`/`ApprovalController` 接入认证与 scope 解析；WS 四类消息（chat/approve/reject/pending_tasks）统一身份解析；`MemoryController` 全部接口按当前 scope 读写 |
| client | 无需改动（scope 由服务端解析，`ChatCmd` / `ApprovalCmd` 不变） |
| start | `application.yml` 增加 3.3 节配置；JDBC 时提供 `schema.sql` |

### 8.2 向后兼容策略

| 场景 | 行为 |
| ---- | ---- |
| 默认配置（tenant.enabled=false, auth.enabled=false, storage=file） | 行为与现状完全一致，存量 `.agent/` 数据零影响 |
| 只开 storage=jdbc/redis | 无鉴权，scope=default（legacy），数据落 DB/Redis 的 default 空间 |
| 开 tenant.enabled=true | 新数据按 tenant/user 落目录；存量 legacy 数据不迁移（可提供迁移工具，一期不做） |

### 8.3 风险与回滚

| 风险 | 缓解 |
| ---- | ---- |
| 端口签名大面积改动 | 改动集中在 domain 接口 + 3 个文件实现 + 编排链路（`OrchestrationContext`/`ExecutionUnit`/协作工具）；`TodoDelegateOrchestratorTest`/`CollaborationToolTest` 存量测试同步适配；编译期兜底 |
| 嵌套编排 scope 透传遗漏 | scope 收敛到 `OrchestrationContext` 单点传播（3.4）；协作工具统一从 `AgentScopeContext` 取，杜绝散落传参 |
| 审批注册表跨用户冲突 | `ApprovalRegistry` key 增加 scope 前缀，审批 API/WS 按 `(scope, sessionId, layerKey)` 定位 |
| JDBC/Redis 依赖膨胀基础设施 | Phase B 拆独立存储模块时瘦身（见 5.1） |
| ThreadLocal 泄露 | 拦截器 finally 清理；异步任务显式传参不依赖 ThreadLocal |
| 锁粒度过大影响并发 | 锁仅覆盖同会话主会话串行；不同会话/不同用户/临时会话（runAgent）完全并行 |

---

## 9. 测试计划

| 级别 | 用例 |
| ---- | ---- |
| 单元 | `AgentScope` 命名空间计算；`SessionLockManager`（本地锁重入/并发互斥）；`ToolPermissionChecker` 规则匹配；文件实现 scope 路径生成 |
| 集成 | 文件模式：双用户会话/事实/摘要/档案互不可见；同会话并发 chat 不丢消息；鉴权开启后未授权 401、工具越权返回错误 |
| 集成 | 编排链路：嵌套编排（`invoke_delegate`/`invoke_discussion`）scope 透传，子任务会话/产物/审批节点归属正确用户；审批流双用户隔离（用户 A 无法审批用户 B 的节点） |
| 集成 | JDBC 模式：H2 全链路（会话 CRUD、事实合并、检索隔离）；Redis 模式：本地 Redis（可 Docker）全链路 + 分布式锁互斥 |
| 回归 | 默认配置下原有 REST/SSE/WS/Shell 全流程不变；记忆面板各接口仍可用；**存量测试适配**（`TodoDelegateOrchestratorTest`/`CollaborationToolTest` mock 端口签名同步更新后全绿） |

---

## 10. 落地任务清单（建议实施顺序）

- [ ] **T1 基础抽象**：`AgentScope` 值对象 + `AgentScopeResolver` 端口 + `Session` 增加 tenantId/userId/version + `AgentScopeContext` + `OrchestrationContext` 增加 scope 字段
- [ ] **T2 端口签名改造**：domain 四个存储端口（`MemoryGateway`/`MemoryPageStore`/`LongTermMemoryGateway`/`MemoryRetriever`）方法签名增加 scope；`ExecutionUnit` 相关方法（`getOrCreateSession`/`runOrchestration`/新增 `executeWithSessionLock`）scope 化（编译期全量修正实现与调用点，含协作工具与存量测试）
- [ ] **T3 文件模式 scope 化**：三个文件实现 + `VectorMemoryRetriever`/`SynthesisCache`/`MemorySynthesisExecutor` scope 化；`ExecutionUnitImpl` 写入 Session 归属；`TodoDelegateOrchestrator` 产物目录 scope 化；协作工具 `AbstractCollaborationTool` scope 透传
- [ ] **T4 Session 并发锁**：`SessionLockManager`（local 实现）+ `ExecutionUnit.executeWithSessionLock` 接入（routing / delegate 主会话路径，嵌套编排与临时会话除外）
- [ ] **T5 认证鉴权**：`AuthInterceptor` + WS 握手（覆盖 chat/approve/reject/pending_tasks 四类消息）+ SSE 参数 + `ToolPermissionChecker`（与 `ToolApproval` 人工审批门分层）+ `AuthProperties` 配置
- [ ] **T6 JDBC 存储**：`JdbcSessionGateway`/`JdbcMemoryPageStore`/`JdbcLongTermMemoryGateway` + `schema.sql` + `@ConditionalOnProperty` 装配
- [ ] **T7 Redis 存储**：`RedisSessionGateway`/`RedisMemoryPageStore`/`RedisLongTermMemoryGateway` + Redis 分布式锁
- [ ] **T8 记忆面板与审批 scope 化**：`MemoryController` 按当前 scope 读写；`ApprovalRegistry`/`ApprovalService`/`ApprovalController` 按 `(scope, sessionId, layerKey)` 定位
- [ ] **T9 测试与回归**：第 9 节用例 + 默认配置回归 + 存量测试（`TodoDelegateOrchestratorTest`/`CollaborationToolTest`）适配
- [ ] **T10 文档**：README 增补租户/存储/鉴权配置说明

---

## 附：与路线图的关系

- 本 Phase 产出的 scope 化端口、存储抽象、锁、鉴权组件将在 **Phase B（框架化）** 中原样进入 `spring-boot-starter` 与共享核心；
- `ToolPermissionChecker`、`AgentScopeResolver`、`SessionLockManager` 为新增 SPI，符合插件化目标；
- 多租户与鉴权默认关闭的设计，保证 Phase B 拆出的客户端嵌入式核心（单用户）不受影响。
