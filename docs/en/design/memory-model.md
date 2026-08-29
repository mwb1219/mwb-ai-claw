---
title: Layered Memory Model
parent: Design Overview (EN)
nav_order: 4
---

# Layered Memory Model

> For readers who want to understand the principles: how the agent breaks through the context window limit to achieve long-term memory.

## 1. Five-Layer Memory Model

| Layer | Content | Storage |
| --- | --- | --- |
| Instruction layer | AGENT.md system instructions | File |
| Working memory (Hot) | Recent raw messages | Within the session |
| Short-term | Full session history | Session JSON |
| Medium-term | Summary pages (history compression) | `.agent/memory/pages/{sessionId}/summary-*.json` |
| Long-term | Fact pages (LLM-distilled) | `.agent/memory/facts.jsonl` |

## 2. Dynamic Paging

- [ ] Token budget model: `context-window × budget-ratio`; System / Tools / Memory allocated proportionally
- [ ] Budget overflow or unsummarized messages above the threshold → oldest blocks are compressed into summary pages
- [ ] Pluggable paging strategy: `importance` (importance-driven, default) / `token` (budget-driven)

## 3. Retrieval and Recall

- [ ] Keyword search (Chinese bigram BM25)
- [ ] Vector search (Embedding + cosine similarity, three-level cache)
- [ ] Hybrid search (RRF fusion), automatically degrades when embedding fails
- [ ] The retrieval implementation follows the storage form: `file` (default) full load + in-memory scoring
  (strategies above); `db` uses Redis Stack retrieval (keyword FT.SEARCH + vector KNN, `RedisMemorySearchable`),
  with MySQL as the authoritative store from which the Redis index can be rebuilt

## 4. Fact Distillation and Merging

- [ ] LLM distills facts (key / content / importance); importance filtering + same-key merge and dedup
- [ ] Distillation is asynchronous (does not block the main conversation flow); results are cached (content-hash dedup)

## 5. Distributed consistency (multi-instance horizontal scaling)

Under a single instance, the "read existing → delete → append" fact merge, in-memory LRU cache, and in-process async synthesis all work fine; under multi-instance deployment (`storage=db`), all three introduce races and redundant cost. The framework solves this with SPI abstractions + database-native idempotency:

### 5.1 Synthesis task queue SPI (`MemorySynthesisDispatcher`)

Unifies async scheduling of afterTurn / afterSession with only two core methods; the three phases just replace internal strategy without changing the SPI:

| Phase | Implementation | Scheduling strategy | Status |
| --- | --- | --- | --- |
| Phase 1 (default) | `LockMemorySynthesisDispatcher` | Submit to in-process single-thread executor + acquire synthesis lock via `DistributedLock` tryLock; re-fetch snapshot inside lock → consume → release | ✅ Implemented |
| **Phase 2** | **`LockFreeMemorySynthesisDispatcher`** | **Lock-free direct submit; consume stage performs CAS claim on `claw_memory_boundary` table (`version` optimistic lock), LLM only executes on success, skip after retry exhaustion** | **✅ Implemented** |
| Phase 3 | `RocketMqMemorySynthesisDispatcher` (example-web extension) | RocketMQ CLUSTERING consumer + sessionId hash partitioning for per-session serialization; produce stages snapshot to `claw_memory_snapshot` table, MQ message carries only metadata | ✅ Implemented |

**Phase 3 RocketMQ MQ Implementation Highlights** (implemented, example-web extension):
- **Framework core stays lightweight**: Phase 3 is implemented as an example-web extension, not in the framework core; the core only retains the SPI + Phase 1/2 implementations;
- **Snapshot staging layer**: `SnapshotStaging` SPI + `JdbcSnapshotStaging` implementation using the `claw_memory_snapshot` table (unique key `(tenant_id, user_id, session_id, task_kind, version)`), avoiding oversized MQ messages;
- **Produce flow**: snapshot → staging.save() → construct `SynthTaskMessage` (only scope/sessionId/kind/version) → RocketMQ sendOneway with `MessageQueueSelector` hashing by sessionId;
- **Consume flow**: `@RocketMQMessageListener` CLUSTERING mode → staging.load() to fetch snapshot → refine() to execute synthesis (reuses Phase 2 CAS claim + LLM + DB idempotent write logic) → staging.delete() cleanup;
- **Dual-layer correctness guarantee**: MQ partition serialization (no concurrency on normal path) + DB idempotent UPSERT (rebalance edge cases cannot corrupt data);
- **Auto-configuration**: Conditional on `synthesis-queue-type=rocketmq` + RocketMQ producer + JdbcTemplate, `@Primary` overrides the framework default `MemorySynthesisDispatcher`;
- **Optional dependency**: `rocketmq-spring-boot-starter` is optional — Phase 3 classes are never scanned when RocketMQ is not present, zero intrusion.

**Phase 2 Lock-free CAS Implementation Highlights** (implemmented):
- **SPI Extension**: `MemoryPageStore` adds default methods `claimSummaryBlock(scope, sessionId, desiredStart, blockSize, snapshotSize)` / `claimArchiveBlock(...)`; JDBC implementation atomically advances cursor via `UPDATE claw_memory_boundary SET summary_end=?, version=version+1 WHERE tenant_id=? AND session_id=? AND version=? AND summary_end=?`;
- **Multi-block Loop**: when session messages exceed `blockSize × N`, CAS claim runs in a loop — claiming one block at a time until snapshot is exhausted, avoiding the "idle run" problem of claiming too many intervals in a single CAS;
- **Retry Strategy**: on CAS failure (concurrent pre-emption), retry up to `synthesis-claim-max-retries` times (default 3); if exhausted, skip LLM and record `synthClaimFail` metric;
- **No Redis Dependency**: Phase 2 works with MySQL only — CAS on boundary table guarantees cross-instance mutual exclusion; enabled automatically with `agent.memory.synthesis-queue-type=lockfree` and `agent.storage.type=db`;
- **Metrics**: `synthClaimCasRetry` (retry count), `synthClaimFail` (final failure count), `synthClaimSuccess` (success count) — isomorphic with Phase 1 metrics.

- **Failure semantics**: lock busy = a newer task is already running → the current older task is dropped (keep latest, drop old), recording `synthLockAcquireFail` / `synthLlmSkip` metrics;
- **Independent lock key**: `claw:synth:{scope.keyPrefix}:{sessionId}:{kind}`, not mutually exclusive with the main session lock;
- **Delayed snapshot**: `snapshotSupplier` is called only after lock/claim success, guaranteeing snapshot ≥ lock acquisition time, avoiding the race where the snapshot is older than already-written pages;
- **Task dedup**: multiple submissions of the same session+type keep the latest task and drop older ones within the in-process executor;
- **Local fallback**: `LocalMemorySynthesisDispatcher` degrades to single-thread local execution when Redis is unavailable / `storage=file`.

### 5.2 Synthesis cache SPI (`SynthesisCache`)

Caches summarize/extract results by "operation type + input content hash"; repeated triggers on the same block do not re-invoke the LLM. Switches automatically by storage form:

| Backend | Implementation | Suitable for |
| --- | --- | --- |
| `local` (default for `storage=file`) | `LocalSynthesisCache` | JVM LinkedHashMap LRU, thread-safe, single instance |
| `redis` (automatic for `storage=db`) | `RedisSynthesisCache` | String + JSON + TTL, shared across instances, disabled when size<=0 |

- Cache key automatically carries the `scope.keyPrefix`, preventing cross-tenant hits;
- Redis impl swallows read/write exceptions and degrades to cache miss without blocking the main conversation flow; `size()` returns -1 to avoid `keys *`.

### 5.3 Idempotent writes (UPSERT) and boundary cursor

- **Fact UPSERT**: `JdbcMemoryPageStore.upsertFactAtomic` uses `ON DUPLICATE KEY UPDATE`, with `importance=GREATEST(importance, VALUES(importance))` to prevent importance regression, `version=version+1`, eliminating the RMW race; the fact table primary key `(tenant_id, user_id, fact_key)` supports atomic UPSERT;
- **Memory page UPSERT**: summary/archive page writes use `ON DUPLICATE KEY UPDATE`, relying on the unique key `uk_scope_session_type_start (tenant_id, user_id, session_id, page_type, block_start)` to prevent block overlap; under extreme concurrency, unique-key conflicts are only warned and skipped, correctness does not regress;
- **Boundary cursor table** `claw_memory_boundary` (one row per session): records `summary_end` / `archive_end` advanced boundaries + `version` optimistic lock, serving as Phase 1 row-lock fallback and Phase 2 CAS pre-claim, guaranteeing non-overlapping, non-duplicate block intervals `[start, end)` under distribution.

## 6. Memory Tools

- [ ] `read_memory` / `write_memory` (called from the LLM side)
- [ ] Shell `/memory` and the REST memory panel

---

See also: [Configuration Guide](../guide/configuration.md)
