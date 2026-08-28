---
title: Horizontal Scaling (EN)
nav_order: 7
parent: Documentation (English)
---

# Horizontal Scaling (Multi-Instance / Distributed)

> For operations and architecture readers who want to upgrade from single-instance to multi-instance horizontal scaling.
> This doc inventories the framework's current in-JVM state, clarifies what must be shared across instances versus what can run on a single instance, and gives concrete strategies for shared storage, session routing, and distributed locks (TODO T3).

## 1. Background & Goals

The framework is designed for single-instance deployment by default: session locks, approval todos, memory-synthesis queues, and RAG writes are all in-JVM state. Single-instance is simple and reliable, but it cannot scale horizontally and suffers from single points of failure.

Horizontal scaling goals:

- **Scale out**: N instances share the load; concurrent requests for the same session remain serialized.
- **Externalize state**: move session / memory / approval / RAG state out of the JVM into shared storage.
- **Routable sessions**: any instance can handle any session (not pinned to a fixed instance).

## 2. Architecture Overview

```
                    ┌────────────┐
  clients ──► LB ───►│ Instance 1 │─┐
        (round/hash) ├────────────┤ ├─► Shared Redis (retrieval index + distributed lock)
                    │ Instance 2 │─┤
                    ├────────────┤ ├─► Shared MySQL (sessions/memory/RAG text, agent.storage.type=db)
                    │ Instance N │─┘
                    └────────────┘
                            │
                            └─► Shared filesystem (optional: file backend, RAG docs, run logs)
```

Key point: **the application is stateless** (it never owns session/memory/approval state exclusively); all mutable state lives on shared components.

## 3. In-JVM State Inventory (Shared Across Instances vs. Single-Instance)

### 3.1 Session-level concurrency lock

| Component | Current implementation | Shared? | Notes |
| --- | --- | --- | --- |
| Session lock | `LocalSessionLockManager` (in-JVM ReentrantLock) | ❌ Not shared | **Done**: abstracted `SessionLockManager` SPI, added `RedisSessionLockManager` (reuses the unified `DistributedLock`, poll-wait + finally release). Set `agent.collaboration.lock.type=redis` for cross-instance sharing; default `local` is fully backward compatible. |

### 3.2 State that must be shared across instances (required for multi-instance)

| Component | Current implementation | State | Multi-instance strategy |
| --- | --- | --- | --- |
| Approval todos | `ApprovalRegistry` (in-JVM ConcurrentHashMap; approval API locates nodes in memory) | In-memory | Human-in-the-loop requires the request and the approval decision to hit the same instance. Multi-instance needs **sticky routing** (hash by sessionId to a fixed instance), or later externalize approval state to Redis/DB for cross-instance visibility (see §7 evolution). |
| Sessions / long-term memory / memory pages | `FileBased*` (local files) or `Jdbc*` (JDBC) | On-disk | File backend needs a **shared filesystem** (NFS / distributed storage / object-storage mount); DB backend is naturally shared. Prefer `agent.storage.type=db` for multi-instance. |
| RAG documents / index | `file`: `FileRagDocumentStore` + `LocalRagIndexStore` (local-file JSONL + in-memory scan); `db`: `JdbcRagDocumentStore` + `RedisRagIndexStore` (**MySQL text authoritative storage + Redis Stack retrieval index**) | On-disk (+ derived Redis index) | In `db` mode both the MySQL text and the Redis Stack retrieval index are naturally shared across instances; if Redis is lost it can be rebuilt from MySQL. `file` mode needs a **shared filesystem**; the index is an in-memory scan whose writes must be serialized across instances (RAG write lock, see §3.3). |
| Run usage logs | Local-file JSONL | On-disk | Needs shared filesystem or aggregation into a logging/observability platform (unified with full-trace TODO T5). |

### 3.3 Components that may run on a single instance (multi-instance = redundancy/contention)

| Component | Current implementation | Multi-instance strategy |
| --- | --- | --- |
| Memory-synthesis queue | `MemorySynthesisExecutor` (single-thread pool + in-memory queue, per-session dedup) | Synthesis is **idempotent backfill**: boundaries (lastSummarized) are read from storage at execution time, so a task executed on any instance neither loses nor duplicates content. In multi-instance, the session lock already serializes the same session; occasional duplicate LLM calls are acceptable. |
| RAG writes | `DefaultRagIngestionService` (in-JVM ConcurrentHashMap write locks) | In-memory locks only work on a single instance. Concurrent writes to the same knowledge base across instances need a **shared write lock** (reuse the Redis distributed lock or serialize the RAG write API). Single-instance semantics are unaffected. |

> Conclusion: **shared across instances** = session lock, approval todos, session/memory/RAG storage; **single-instance OK** = memory synthesis, RAG writes (idempotent, can degrade).

## 4. Distributed lock (implemented)

### 4.1 Unified distributed lock SPI (`DistributedLock`)

All distributed mutual-exclusion primitives — session lock, synthesis lock, etc. — are uniformly encapsulated in the `DistributedLock` SPI (`infrastructure/lock`), wrapping "acquire → (optional) watchdog renew → execute task → finally release" so callers only care about `LockOptions` + the task:

```java
// Session lock: poll-wait, no renew
LockResult<T> r = lock.execute(key, LockOptions.wait(ttl, timeout, retry), task);

// Synthesis lock: tryLock no-wait + watchdog renew
LockResult<Void> r = lock.execute(key, LockOptions.tryLockWithRenew(ttl, renew), task);
```

- **Implementation**: `RedisDistributedLock` (based on Redis Hash structure, **reentrant by default**):
  - Lock structure `HSET claw:lock:xxx owner {token} count {N} EXPIRE {ttl}`; `owner` identifies the holder, `count` records the reentrant depth;
  - Three Lua scripts execute atomically: ACQUIRE (0=held by other / 1=newly acquired / 2=reentrant) / RELEASE (-1=not owner / ≥0=remaining depth) / RENEW (only owner renews);
  - **Reentrancy**: `ThreadLocal<Map<lockKey, ownerToken>>` caches the token of locks already held by the current thread; nested `execute` on the same key reuses the same token, making ACQUIRE recognize it as owner and increment count; the key is only truly DEL'd when count reaches zero;
  - **Watchdog starts only at the outermost layer**: avoids redundant renewal tasks on inner layers; the outer renewer spans all reentrant levels and is cancelled in finally when the outer lock is released; ThreadLocal is cleared only when the outermost release succeeds (count=0), avoiding memory leaks.
- Assembled by `ClawCoreAutoConfiguration` when a distributed lock is needed (either session lock or synthesis lock in Redis form), reused by `RedisSessionLockManager` and `LockSynthesisTaskQueue`.

### 4.2 Session lock (`SessionLockManager`)

The `SessionLockManager` is abstracted as an SPI (`infrastructure/collaboration/lock`) with two implementations:

- `LocalSessionLockManager`: JVM-internal ReentrantLock, isolated by `scope.keyPrefix()`, **default**;
- `RedisSessionLockManager`: reuses `DistributedLock`, acquires the session lock via `LockOptions.wait` poll-wait, throws "session lock acquire timeout" on timeout; release delegates to the unified lock's finally semantics.

Configuration (`agent.collaboration.lock.*`):

```yaml
agent:
  collaboration:
    lock:
      type: redis          # local (default, single-instance) | redis (multi-instance)
      redis-uri: redis://:password@redis.internal:6379/0
      key-prefix: claw:lock:
      lease-ms: 30000       # auto-expire, auto-released if the holder crashes
      timeout-ms: 30000     # acquisition wait timeout
      retry-interval-ms: 100
```

> Switching to redis requires adding `spring-boot-starter-data-redis` yourself (declared optional); if it is absent or type is not `redis`, it falls back to the local implementation — backward compatible.

## 5. Session Routing Strategy

In multi-instance, "which instance handles a request for a given session" determines whether approval todos / session state are reachable:

| Strategy | Approach | When to use |
| --- | --- | --- |
| **Sticky sessions (recommended)** | LB hashes by `sessionId` to a fixed instance; approval todos and the memory-synthesis queue hit within that instance | Human-in-the-loop approval + memory synthesis (current implementation); on instance failure the session drifts, so distributed locks and shared storage are still needed as a fallback |
| **Stateless + distributed lock** | Any instance can handle any session, serialized by `RedisSessionLockManager`; sessions/memory live in shared storage | Session lock is supported; before approval todos are externalized, approval requests must return to the originating instance (sticky) or use "externalized approval" (see evolution) |
| **External state center** | Sessions/approval state live in Redis/DB; instances fully stateless | Evolution target (infrastructure evolution beyond TODO T7 frontend / T6 rate limiting) |

Recommended combination (currently deployable):

```text
LB(sessionId sticky) + shared Redis(retrieval index + distributed lock) + shared MySQL(session/memory/RAG text) + shared filesystem(optional)
```

## 6. Configuration & Deployment Examples

### 6.1 Multi-instance (Nginx sticky routing + Redis lock + DB storage)

```bash
# identical startup params per instance
java -jar mwb-ai-claw.jar \
  --agent.storage.type=db \
  --agent.collaboration.lock.type=redis \
  --agent.collaboration.lock.redis-uri=redis://:password@redis.internal:6379/0 \
  --server.port=8080          # instance 2 uses 8081, etc.
```

Nginx sticky sessions (`ip_hash` or cookie hash to pin the same session to one instance):

```nginx
upstream claw_cluster {
    ip_hash;                 # same source IP pinned to one instance; use a third-party module for sessionId hashing
    server 10.0.0.1:8080;
    server 10.0.0.2:8081;
}
```

> Note: human-in-the-loop (SSE long connections / WebSocket) is naturally sticky (connections don't migrate after being established); combined with `ip_hash`, approval APIs and the initiating request hit the same instance.

### 6.2 Database and Redis readiness

With `agent.storage.type=db` the datasource is connected at startup, so MySQL must be reachable (see the `DB_*` variables in the [configuration guide](https://github.com/mwb1219/mwb-ai-claw/blob/master/CONFIG-GUIDE.md)); the retrieval index and the Redis distributed lock in `db` mode depend on Redis Stack (RediSearch), so Redis must also be reachable (`REDIS_URI`, default `redis://localhost:6379`).

## 7. Verification

- **Distributed lock**: with two instances, fire concurrent requests for the same session and observe no lock-contention errors other than "session lock acquisition timeout"; both instances' logs show same-session requests processed serially in turn.
- **Cross-instance approval**: under sticky routing, an approval task initiated on instance A is visible in the approval API listing on instance B (currently requires same-instance hits — verify the sticky configuration is effective).
- **Session state**: after a request drifts to another instance, history/session memory remains readable (DB backend).

Unit tests: `infrastructure/.../collaboration/lock/SessionLockManagerTest` (same-session serialization, cross-session parallelism, Redis acquire/release/timeout).

## 8. Known Limitations & Evolution

| Limitation | Evolution |
| --- | --- |
| Approval todos are in-JVM state; cross-instance visibility depends on sticky routing | Externalize approval state to Redis/DB (pending-approval table + decision events) so any instance can decide (later iteration) |
| Memory synthesis is a per-instance single-thread queue; multi-instance causes duplicate scheduling | **Implemented**: `SynthesisTaskQueue` SPI (see [Layered Memory Model](memory-model.md) §5); Phase 1 `LockSynthesisTaskQueue` uses the unified `DistributedLock` for cross-instance serialization + task dedup + UPSERT idempotent writes, so multi-instance no longer duplicates scheduling |
| RAG/memory retrieval index is an in-memory scan in `file` mode, one copy per instance | Already replaced by the Redis Stack retrieval index in `db` mode (shared across instances, rebuildable from MySQL); serialize `file`-mode writes with a distributed lock |
| Run logs land in local files | Full trace + aggregation to logging/OTel (TODO T5) |

---

Related: [Storage & Multi-Tenancy](storage-multitenancy.md) ｜ [Observability & Resilience](observability.md) ｜ [Configuration Guide](https://github.com/mwb1219/mwb-ai-claw/blob/master/CONFIG-GUIDE.md)
