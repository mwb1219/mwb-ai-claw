---
title: Storage & Multi-Tenancy
parent: Design Overview (EN)
nav_order: 5
---

# Storage & Multi-Tenancy

> For readers who want to understand the principles: where data lives and how sessions and memories of different tenants/users are isolated.

## 1. Storage Backends (pluggable)

Storage is abstracted through three domain ports (Session / MemoryPage / LongTermMemory) and assembled by selecting one of two modes via `agent.storage.type`:

| Type | Implementation | Data location | Use cases |
| --- | --- | --- | --- |
| `file` (default) | `FileBasedSessionGateway` / `FileMemoryPageStore` / `FileBasedMemoryGateway` | `.agent/` directory (JSON / JSONL) | Local personal use, zero dependencies |
| `db` | `JdbcSessionGateway` / `JdbcMemoryPageStore` / `JdbcLongTermMemoryGateway` | JDBC (H2 by default, switchable to MySQL) | Server-side deployment, shared across instances |

- Switching method: `agent.storage.type` (or the environment variable `STORAGE_TYPE`), see [reference/config-full.md](../reference/config-full.md)
- The semantics of the three ports remain unchanged: switching backends requires no business-code changes
- In `db` mode, you must first run `start/src/main/resources/schema.sql` to create the tables (MySQL syntax; can be run directly in H2 compatibility mode)

## 2. Multi-Tenancy Isolation Model (AgentScope)

### 2.1 Identity Dimensions

`AgentScope` (tenantId, userId) is an explicit identity value object that runs through storage, asynchronous tasks, and nested orchestration:

```java
AgentScope.of("tenant-a", "user-1");   // tenant + user
AgentScope.of(null, null);             // default scope (legacy root directory, compatibility mode)
AgentScope.defaultScope();             // same as the line above
```

### 2.2 Isolation Approach

- `namespace()`: `tenantId + "/" + userId` — used as the storage subdirectory in file mode, and as the table prefix / key dimension in db mode
- `AgentScopeContext` (ThreadLocal): temporarily holds the current scope during the request chain; set at the entry point and cleared in `finally`
- Asynchronous tasks (SSE / WebSocket execution threads): ThreadLocal does not cross threads, so the scope must be explicitly captured and set via `AgentScopeContext.set(scope)`
- Session concurrency locks are fixed to `LocalSessionLockManager` (in-JVM ReentrantLock, isolated by the `scope.keyPrefix()` dimension)

## 3. How Each Entry Point Determines the Scope

| Entry point | Scope source |
| --- | --- |
| Shell mode | Fixed `("default", "default")`; persisted / serialized uniformly as default |
| REST / SSE | `AuthInterceptor` resolves (tenantId, userId) from the API key and writes it in; default when auth is disabled |
| WebSocket | Resolved during the handshake (`WsAuthHandshakeInterceptor`), written into session attributes |
| Embedded `ClawRuntime` | The caller explicitly passes an `AgentScope` (`withScope` helper method) |

## 4. Design Points

- **Compatibility first**: an empty scope means the default space, fully consistent with earlier versions
- **No global implicit state**: the scope is passed explicitly as a parameter / context to avoid cross-tenant data leakage
- **Auth is optional**: when `agent.auth.enabled` is off, everyone shares the default space; when on, data is isolated by key (see [design/security.md](security.md))

## 5. Multi-Tenancy Example: example-commerce (store isolation)

T2 has landed a runnable reference implementation: [example-commerce](https://github.com/mwb1219/mwb-ai-claw/tree/master/example-commerce) resolves API keys (`sk-store-a` / `sk-store-b`) into (tenantId, userId) via `CommerceTenantGateway`, so the products / orders / campaigns of the two stores are fully isolated; the frontend picks a store at the entry, and tools read through the scope propagated via `AgentScopeContext`. To integrate your own tenant table / SSO, implement `TenantGateway` and override the default bean with `@Bean` (see that module and "How Each Entry Point Determines the Scope" above).

---

See also: [Configuration Guide](../guide/configuration.md) | [Security Model](security.md)
