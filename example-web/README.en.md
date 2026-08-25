# example-web — Web Console (Chat / Memory / RAG Knowledge Base)

> mwb-ai-claw "Web Console" example: a frontend/backend split project built on the Spring Boot Starter,
> demonstrating REST / SSE chat, layered-memory visualization, and standalone RAG knowledge base management,
> with a focus on **production-grade capabilities** — distributed session lock, Redis Stack retrieval knowledge
> base (vector + full-text / multi-format parsing / access control / capacity quotas) and memory persistence to database.
>
> 中文说明：[README.md](README.md)

## 1. Capabilities and Mechanisms (mapping table)

| Capability | Framework mechanism | This example |
| --- | --- | --- |
| Distributed session lock (multi-instance) | `SessionLockManager` SPI: `LocalSessionLockManager` (JVM `ReentrantLock`) / `RedisSessionLockManager` (`SET NX PX` + atomic Lua release) | `agent.collaboration.lock.type=redis`; Redis service in [docker-compose.yml](docker-compose.yml) |
| Redis as an optional dependency | redis dependency is `optional` in infra, gated by `@ConditionalOnClass` | [pom.xml](pom.xml) explicitly adds `spring-boot-starter-data-redis` |
| Pluggable vector store | `RagIndexStore` SPI: `LocalRagIndexStore` (files, `auto+file`) / `RedisRagIndexStore` (text in MySQL + retrieval via Redis Stack, `auto+db` or explicit `redis`) | `agent.rag.provider=auto` + `STORAGE_TYPE=db`; mysql + redis-stack-server in [docker-compose.yml](docker-compose.yml) (initdb creates tables) |
| Multi-format document parsing | `RagDocumentParser` SPI (composite detects by classpath) | PDFBox / POI-XWPF in [pom.xml](pom.xml); uploads support `.md/.txt/.pdf/.docx` |
| Knowledge-base API authorization | `RagAccessPolicy` SPI (active when `agent.rag.access.enabled=true`) | `rag/ExampleRagAccessPolicy`: READ globally shared; write/delete by `{tenant}-` prefix + `admin` superuser |
| Capacity & quotas | `agent.rag.capacity.*` | sample values in [application.yml](src/main/resources/application.yml) |
| Extension points (replace / enhance) | `RagChunker` / `RagReranker` (`@ConditionalOnMissingBean`) | `rag/ExampleRagChunker` (decorates default chunker, tags metadata), `rag/ExampleRagReranker` (re-rank & truncate) |
| Seed docs on startup | `ApplicationRunner` | `rag/ExampleRagSeedInitializer`: idempotent ingestion of MD/PDF/Word |
| Memory persistence to DB | `agent.storage.type`: `file` (local files, default) / `db` (MySQL authoritative storage + Redis Stack retrieval); `db` assembles `JdbcMemoryPageStore` (dual-writes Redis index) / `RedisMemorySearchable` / `JdbcSessionGateway` / `JdbcLongTermMemoryGateway` | `STORAGE_TYPE=db`; session/fact/memory-page/long-term persisted to MySQL, keyword & vector retrieval via Redis |

## 2. Middleware (Docker)

Storage / retrieval (MySQL + Redis Stack) and the distributed session lock (Redis) are brought up with Docker:

```bash
cd example-web
docker compose up -d
docker compose ps   # mysql / redis middleware containers should both be healthy
```

- **mysql** (`localhost:3306`, default db/user/password `clawdb/claw/claw`)
  - On first init, [db/mysql/framework-schema.sql](db/mysql/framework-schema.sql) (framework tables:
    session / fact / memory-page / long-term, RAG documents & index-entry text, observability
    `claw_trace` / `claw_run_usage`) and [db/mysql/example-web-schema.sql](db/mysql/example-web-schema.sql)
    (app user table `claw_user`) run automatically.
  - Acts as the **authoritative store** for `agent.storage.type=db` (memory / knowledge-base text); retrieval
    never queries MySQL, it goes through the Redis index.
- **redis** (`localhost:6379`) runs the `redis/redis-stack-server` image with built-in **RediSearch**:
  - For `agent.storage.type=db`, hosts the **retrieval index** for memory and RAG (full-text inverted index +
    vector KNN), dual-written after a successful MySQL write;
  - Also backs the `agent.collaboration.lock.type=redis` distributed session lock.

> Minimal zero-middleware demo: default `STORAGE_TYPE=file` (local files) with `LOCK_TYPE=local` (JVM lock) and
> `RAG_PROVIDER=auto` (follows storage → `local` index); no middleware is needed for chat and local RAG. Start
> Docker only when you want MySQL persistence / Redis Stack retrieval / distributed session lock.

## 3. Quick Start

```bash
# 1. Copy .env and fill in real keys (at least DEFAULT_API_KEY; for RAG write/search also RAG_EMBEDDING_*)
cp src/main/resources/.env.example .env

# 2. Build & run (example-web is a standalone project with its own version, not part of the repo reactor)
#    Framework dep mwb-ai-claw-spring-boot-starter:1.0.3 (downloaded from Maven Central, no local repo needed)
mvn clean package -DskipTests   # 1) compile & package locally (framework 1.0.3 pulled from Maven Central)
mvn spring-boot:run             # 2) start example-web (port 8080)
```

> One-shot containerized build (backend + frontend + middleware): `docker compose up -d --build` (see [docker-compose.yml](docker-compose.yml)).

### Key environment variables (see .env in this directory)

| Variable | Default | Description |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/clawdb` | MySQL datasource: memory persistence + RAG document/index-entry text (active with `STORAGE_TYPE=db`) |
| `STORAGE_TYPE` | `file` | storage form: `db` (MySQL authoritative storage + Redis Stack retrieval) \| `file` (local files, zero dependency) |
| `RAG_PROVIDER` | `auto` | RAG index implementation: `auto` (follows storage: file→local, db→redis) \| `redis` (explicit) |
| `REDIS_INDEX_PREFIX` | `claw` | Redis retrieval-index key prefix (namespace isolation when sharing Redis across environments/tenants) |
| `LOCK_TYPE` | `redis` | distributed session lock channel; use `local` without Redis |
| `REDIS_URI` | `redis://localhost:6379` | Redis address (retrieval index + distributed lock; `agent.redis` reuses `spring.data.redis.*`, falls back to this when unset) |
| `RAG_EMBEDDING_MODEL/BASE_URL/API_KEY` | empty | standalone RAG embedding (OpenAI-compatible `/embeddings`) |
| `BOOTSTRAP_API_KEY` | `sk-admin-bootstrap` | bootstrap admin key (tenant=admin, superuser, can manage `admin-*` seed KBs) |

A successful startup prints:

```
[example-web] 种子文档摄入成功: admin-product-docs / 产品手册.md
[example-web] 种子文档摄入成功: admin-product-docs / 快速上手指南.pdf
[example-web] 种子文档摄入成功: admin-operations-manual / 运营规范.docx
```

i.e. multi-format parsing + Redis Stack index write + seed ingestion is successful.

## 4. Frontend Console

Frontend lives in [example-web-frontend](../example-web-frontend):

```bash
cd example-web-frontend
npm ci
npm run dev        # http://localhost:5173 (Vite dev proxy → 8080, no CORS needed)
npm run typecheck  # tsc type check
npm run build      # production build → dist/
```

Pages: login / register, chat (inject a selected knowledge base as reference), memory visualization,
**knowledge base RAG management**, and human approval.

> On the knowledge base page, use the bootstrap admin key (`X-API-Key: sk-admin-bootstrap`) to manage the
> `admin-product-docs` / `admin-operations-manual` seed KBs as an `admin` superuser (regular registered users
> get a tenant id equal to their username and can manage `{username}-*` KBs).

## 5. Page Walkthrough (screenshots)

Screenshots below are actual operations on `http://localhost:5173` (backend + docker already running as above):

**① Login (`#/login`)**: username/password login or register; a session API key is issued as the credential (`X-API-Key`).

![Login](screenshots/01-login.jpg)

**② Knowledge base RAG management (`#/rag`)**: entered as admin; left-side chips list seed KBs `admin-product-docs`,
`admin-operations-manual`. The document list shows the three formats — `产品手册.md`, `快速上手指南.pdf` (PDFBox),
`运营规范.docx` (POI-Word) — all READY, with version / chunk count / update time; supports upload, reindex, delete.

![Knowledge base management](screenshots/02-rag.jpg)

**③ Retrieval debug (`#/rag`)**: query "产品支持哪些部署方式" and search; returns Redis Stack vector hits
carrying the custom chunker metadata `extension=example-web-custom-chunker` (demonstrating the RagChunker
replacement extension point), along with score / source document / chunk.

![Knowledge base search](screenshots/03-rag-search.jpg)

**④ Chat with knowledge base injected (`#/chat`)**: the knowledge-base selector above the input bar has
`admin-product-docs` selected; during chat the backend injects hit content as a "knowledge base reference" into the
system prompt, showing the RAG context-injection chain independent of memory.

![Chat with KB injected](screenshots/04-chat-rag.jpg)

**⑤ Chat example (`#/chat`)**: enter "Hello, please introduce yourself and explain what you can do.", the assistant
returns a structured reply based on the configured model and orchestration (streaming output, automatic memory
consolidation). This is the most intuitive "dialog box" interaction: send a question → receive a reply.

![Chat example](screenshots/05-chat-reply.jpg)

**⑥ Observability (`#/observability`)**: every agent execution persists a run-usage summary (into the
`claw_run_usage` table, controlled by `agent.observability.run-usage-store=db`). The top aggregates today's
run count / success / failure / average duration, while the list below shows each run's traceId, session, agent,
orchestration, model, step count and duration.

![Observability - run records](screenshots/06-observability-runs.jpg)

Click "查看 trace" on a run record (or type a traceId directly) to call `GET /trace/{traceId}` and reconstruct the
step-by-step details (Thought / Action / Observation) of that execution, stored in the `claw_trace` table (controlled
by `agent.observability.trace.store=db`), shared across instances and isolated per tenant/user.

![Observability - full chain trace](screenshots/07-observability-trace.jpg)

**⑦ Human approval (`#/approval`)**: when the orchestration enables an approval gate (`approvalGate: "root"` in
`orchestrations.json`, i.e. the `todo-delegate` escalation/planning must be manually confirmed before continuing),
the backend generates pending-approval nodes (`PendingApproval`). The approval page pulls the list via
`GET /agent/pending-tasks`, showing the original task and the todo titles to execute, with "approve / reject" actions:

- **Pending**: the page lists the pending root node with the original task "请规划并实现一个用户积分与等级体系…"
  and its 8 todo titles.

![Approval - pending](screenshots/05-approval-pending.png)

- **Approved**: clicking "approve" marks the node APPROVED and wakes it up; the card disappears and the page shows
  "暂无待审批任务", while `/agent/pending-tasks` returns an empty list.

![Approval - approved](screenshots/06-approval-approved.png)

- **Rejected**: clicking "reject" and confirming marks the node REJECTED, so that layer degrades to direct execution
  (backend logs: `审批门禁: 节点已决策 REJECTED`, `审批已拒绝，该层降级直执行`); the list likewise becomes empty.

![Approval - before reject](screenshots/07-approval-reject-pending.jpg)

![Approval - rejected](screenshots/08-approval-rejected.jpg)

## 6. REST Verification (optional)

```bash
# Search the seed KB (READ is globally shared)
curl -X POST http://localhost:8080/rag/search -H "X-API-Key: sk-admin-bootstrap" \
  -H "Content-Type: application/json" \
  -d '{"knowledgeBaseIds":["admin-product-docs"],"text":"产品支持哪些部署方式","topK":3,"minScore":0.0}'

# List documents
curl http://localhost:8080/rag/knowledge-bases/admin-product-docs/documents -H "X-API-Key: sk-admin-bootstrap"

# List today's run records (run usage stored in claw_run_usage)
curl http://localhost:8080/runs -H "X-API-Key: sk-admin-bootstrap"

# Reconstruct a full-chain trace by traceId (stored in claw_trace)
curl http://localhost:8080/trace/<traceId> -H "X-API-Key: sk-admin-bootstrap"
```

## 7. Notes

- Knowledge bases are **globally shared** resources; retrieval (READ) is open to all tenants. Write/delete is
  authorized by `ExampleRagAccessPolicy` per tenant prefix, illustrating API-layer access control under
  «production-grade data isolation».
- RAG is **fully isolated** from the memory system (own domain model / store / embedding config / retrieval chain).
  When `agent.rag.enabled=false`, no RAG bean or `/rag` endpoint is assembled — behavior is identical to before RAG.
- Memory storage defaults to `STORAGE_TYPE=file` (local files, zero dependency). Switching to `db` persists
  sessions / long-term memory / memory pages / facts to MySQL, with keyword & vector retrieval served by the
  Redis Stack (RediSearch) index (dual-written after a successful MySQL write; the index can be rebuilt).
  The two forms are switchable at any time.
- Table schemas: MySQL flavors of `claw_user` and the framework tables (`claw_session` / `claw_fact` /
  `claw_memory_page` / `claw_long_term`, `claw_rag_document` / `rag_index_entries`, `claw_trace` /
  `claw_run_usage`) are in [db/mysql/framework-schema.sql](db/mysql/framework-schema.sql) and
  [db/mysql/example-web-schema.sql](db/mysql/example-web-schema.sql), executed automatically on the MySQL
  container's first init.