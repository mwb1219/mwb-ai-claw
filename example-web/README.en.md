# example-web — Web Console (Chat / Memory / RAG Knowledge Base)

> mwb-ai-claw "Web Console" example: a frontend/backend split project built on the Spring Boot Starter,
> demonstrating REST / SSE chat, layered-memory visualization, and standalone RAG knowledge base management,
> with a focus on **production-grade capabilities** — distributed session lock, vector knowledge base
> (pgvector / multi-format parsing / access control / capacity quotas) and memory persistence to database.
>
> 中文说明：[README.md](README.md)

## 1. Capabilities and Mechanisms (mapping table)

| Capability | Framework mechanism | This example |
| --- | --- | --- |
| Distributed session lock (multi-instance) | `SessionLockManager` SPI: `LocalSessionLockManager` (JVM `ReentrantLock`) / `RedisSessionLockManager` (`SET NX PX` + atomic Lua release) | `agent.collaboration.lock.type=redis`; Redis service in [docker-compose.yml](docker-compose.yml) |
| Redis as an optional dependency | redis dependency is `optional` in infra, gated by `@ConditionalOnClass` | [pom.xml](pom.xml) explicitly adds `spring-boot-starter-data-redis` |
| Pluggable vector store | `RagIndexStore` SPI: `LocalRagIndexStore` (files) / `PgVectorRagIndexStore` (PostgreSQL+pgvector) | `agent.rag.provider=pgvector`; pgvector in [docker-compose.yml](docker-compose.yml) (initdb runs `CREATE EXTENSION vector`) |
| Multi-format document parsing | `RagDocumentParser` SPI (composite detects by classpath) | PDFBox / POI-XWPF in [pom.xml](pom.xml); uploads support `.md/.txt/.pdf/.docx` |
| Knowledge-base API authorization | `RagAccessPolicy` SPI (active when `agent.rag.access.enabled=true`) | `rag/ExampleRagAccessPolicy`: READ globally shared; write/delete by `{tenant}-` prefix + `admin` superuser |
| Capacity & quotas | `agent.rag.capacity.*` | sample values in [application.yml](src/main/resources/application.yml) |
| Extension points (replace / enhance) | `RagChunker` / `RagReranker` (`@ConditionalOnMissingBean`) | `rag/ExampleRagChunker` (decorates default chunker, tags metadata), `rag/ExampleRagReranker` (re-rank & truncate) |
| Seed docs on startup | `ApplicationRunner` | `rag/ExampleRagSeedInitializer`: idempotent ingestion of MD/PDF/Word |
| Memory persistence to DB | `agent.storage.type`: `file` (local files) / `db` (JDBC store); `db` assembles `JdbcMemoryPageStore` / `JdbcSessionGateway` / `JdbcLongTermMemoryGateway` | `STORAGE_TYPE=db`; session/fact/memory-page/long-term four tables live in the pgvector database |

## 2. Middleware (Docker)

The vector knowledge base (pgvector) and the distributed session lock (Redis) depend on PostgreSQL and Redis. Bring both up with Docker:

```bash
cd example-web
docker compose up -d
docker compose ps   # both containers should be healthy
```

- **pgvector** (`localhost:5432`, default db/user/password `claw/claw/clawdb`)
  - On first init, [docker/initdb/01-pgvector.sql](docker/initdb/01-pgvector.sql) automatically runs
    `CREATE EXTENSION IF NOT EXISTS vector` and creates the app user table `claw_user` plus the **four memory
    tables** `claw_session` / `claw_fact` / `claw_memory_page` / `claw_long_term` (PostgreSQL flavor, backing
    the `example.web` user system and `agent.storage.type=db`).
  - If the data volume predates the extension and you hit `type "vector" does not exist`, run once manually:
    `docker exec example-web-pgvector psql -U claw -d clawdb -c "CREATE EXTENSION IF NOT EXISTS vector;"`
- **redis** (`localhost:6379`) used by `agent.collaboration.lock.type=redis`.

> Minimal zero-middleware demo: set `RAG_PROVIDER=local` (file-based vector index) and `LOCK_TYPE=local`
> (JVM lock). To experience the pgvector retrieval / distributed session lock you still need PostgreSQL + Redis.

## 3. Quick Start

```bash
# 1. Copy .env and fill in real keys (at least DEFAULT_API_KEY; for RAG write/search also RAG_EMBEDDING_*)
cp example-web/src/main/resources/.env.example example-web/.env

# 2. Build & run (two steps: install dependency modules, then run example-web alone)
# Do NOT use `mvn -pl example-web -am spring-boot:run` in one shot:
#   `-am` pulls the parent POM & dependency modules into the reactor and applies `spring-boot:run` to each,
#   and the parent has no main class — this fails with "Unable to find a suitable main class".
mvn -pl example-web -am install -DskipTests   # 1) compile & install dependency modules
mvn -pl example-web spring-boot:run           # 2) start example-web (port 8080)
```

### Key environment variables (see example-web/.env)

| Variable | Default | Description |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/clawdb` | datasource: shared by RAG vector store and memory persistence (`STORAGE_TYPE=db`) |
| `STORAGE_TYPE` | `db` | memory storage backend: `db` (session/fact/memory-page/long-term in PostgreSQL) \| `file` (local files, zero dependency) |
| `LOCK_TYPE` | `redis` | distributed session lock channel; use `local` without Redis |
| `REDIS_URI` | `redis://localhost:6379` | Redis address |
| `RAG_PROVIDER` | `pgvector` | vector index implementation; `local` is dependency-free |
| `RAG_EMBEDDING_MODEL/BASE_URL/API_KEY` | empty | standalone RAG embedding (OpenAI-compatible `/embeddings`) |
| `BOOTSTRAP_API_KEY` | `sk-admin-bootstrap` | bootstrap admin key (tenant=admin, superuser, can manage `admin-*` seed KBs) |

A successful startup prints:

```
[example-web] 种子文档摄入成功: admin-product-docs / 产品手册.md
[example-web] 种子文档摄入成功: admin-product-docs / 快速上手指南.pdf
[example-web] 种子文档摄入成功: admin-operations-manual / 运营规范.docx
```

i.e. multi-format parsing + pgvector write + seed ingestion is successful.

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

**③ Retrieval debug (`#/rag`)**: query "产品支持哪些部署方式" and search; returns PGVector hits carrying the custom
chunker metadata `extension=example-web-custom-chunker` (demonstrating the RagChunker replacement extension point),
along with score / source document / chunk.

![Knowledge base search](screenshots/03-rag-search.jpg)

**④ Chat with knowledge base injected (`#/chat`)**: the knowledge-base selector above the input bar has
`admin-product-docs` selected; during chat the backend injects hit content as a "knowledge base reference" into the
system prompt, showing the RAG context-injection chain independent of memory.

![Chat with KB injected](screenshots/04-chat-rag.jpg)

**⑤ Chat example (`#/chat`)**: enter "Hello, please introduce yourself and explain what you can do.", the assistant
returns a structured reply based on the configured model and orchestration (streaming output, automatic memory
consolidation). This is the most intuitive "dialog box" interaction: send a question → receive a reply.

![Chat example](screenshots/05-chat-reply.jpg)

## 6. REST Verification (optional)

```bash
# Search the seed KB (READ is globally shared)
curl -X POST http://localhost:8080/rag/search -H "X-API-Key: sk-admin-bootstrap" \
  -H "Content-Type: application/json" \
  -d '{"knowledgeBaseIds":["admin-product-docs"],"text":"产品支持哪些部署方式","topK":3,"minScore":0.0}'

# List documents
curl http://localhost:8080/rag/knowledge-bases/admin-product-docs/documents -H "X-API-Key: sk-admin-bootstrap"
```

## 7. Notes

- Knowledge bases are **globally shared** resources; retrieval (READ) is open to all tenants. Write/delete is
  authorized by `ExampleRagAccessPolicy` per tenant prefix, illustrating API-layer access control under
  «production-grade data isolation».
- RAG is **fully isolated** from the memory system (own domain model / store / embedding config / retrieval chain).
  When `agent.rag.enabled=false`, no RAG bean or `/rag` endpoint is assembled — behavior is identical to before RAG.
- Memory storage defaults to `STORAGE_TYPE=db`: sessions / long-term memory / memory pages / facts are all
  persisted to the same PostgreSQL (pgvector) datasource. Set `agent.storage.type=file` to fall back to local files
  (zero dependency); the two are switchable at any time.
- Table schemas: MySQL flavors of `claw_user` and the four memory tables (`claw_session` / `claw_fact` /
  `claw_memory_page` / `claw_long_term`) are in [schema.sql](src/main/resources/schema.sql); the PostgreSQL flavors
  are created together at first init by [docker/initdb/01-pgvector.sql](docker/initdb/01-pgvector.sql).