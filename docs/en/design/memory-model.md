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

### 5.1 Synthesis task queue SPI (`SynthesisTaskQueue`)

Unifies async scheduling of afterTurn / afterSession with only two core methods; the three phases just replace internal strategy without changing the SPI:

| Phase | Implementation | Scheduling strategy |
| --- | --- | --- |
| Phase 1 (default) | `LockSynthesisTaskQueue` | Submit to in-process single-thread executor + acquire synthesis lock via `DistributedLock` tryLock; re-fetch snapshot inside lock → consume → release |
| Phase 2 | `LockFreeSynthesisTaskQueue` | CAS pre-claim interval on the boundary table (`version` optimistic lock) |
| Phase 3 | Production-grade MQ (RocketMQ, example-web extension) | MQ consumer callback executes |

- **Failure semantics**: lock busy = a newer task is already running → the current older task is dropped (keep latest, drop old), recording `synthLockAcquireFail` / `synthLlmSkip` metrics;
- **Independent lock key**: `claw:synth:{scope.keyPrefix}:{sessionId}:{kind}`, not mutually exclusive with the main session lock;
- **Delayed snapshot**: `snapshotSupplier` is called only after lock/claim success, guaranteeing snapshot ≥ lock acquisition time, avoiding the race where the snapshot is older than already-written pages;
- **Task dedup**: multiple submissions of the same session+type keep the latest task and drop older ones within the in-process executor;
- **Local fallback**: `LocalSynthesisTaskQueue` degrades to single-thread local execution when Redis is unavailable / `storage=file`.

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
