---
title: RAG Retrieval Augmentation
parent: Design Overview (EN)
nav_order: 5
---

# RAG Retrieval Augmentation (Knowledge Base)

> For readers who want to understand the principles: a "knowledge ingest → index → retrieve → context injection"
> capability that is **fully independent** of Agent memory, providing business knowledge and citations for questions.

## 1. Positioning & Boundaries

RAG and Agent memory are two different capabilities — independent, and never shared:

| Aspect | Agent memory | RAG knowledge base |
| --- | --- | --- |
| Data source | Facts, summaries, archives produced during conversations | Business documents uploaded in the background |
| Lifecycle | Evolves with the user / session / agent | Maintained uniformly by administrators |
| Isolation | By `AgentScope` | Globally shared, organized by `knowledgeBaseId` |
| Write path | Auto-distilled after conversations | Explicit ingest, update, delete, rebuild |
| Retrieval target | Recover historical context | Provide business knowledge and citations |
| Data model | `MemoryPage` | `RagDocument` / `RagChunk` |

Key constraints:

- Never reuse / modify `MemoryRetriever`, `MemoryPageStore`, or the existing memory retrievers;
- The knowledge base is globally shared and does not accept `AgentScope`; `knowledgeBaseId` only denotes a business knowledge collection;
- The two retrieval results are only presented side by side at context-assembly time, never fused at the retrieval layer.

## 2. Capability Flow

Write path:

```text
RagIngestionService
  |-- RagDocumentParser   parse (text / Markdown / PDF / Word)
  |-- RagChunker          chunk (heading / blank-line / length / overlap)
  |-- RagEmbeddingGateway batch vectorization
  |-- RagIndexStore       write index (MySQL text authoritative storage + Redis Stack retrieval index, dual-write)
  `-- RagDocumentStore    record document status
```

Retrieval path:

```text
RagRetrievalService
  |-- RagEmbeddingGateway produce the query vector
  |-- RagIndexStore.search  vector retrieval (Redis KNN, score = 1 - cosine distance; keyword/full-text via FT.SEARCH)
  |-- RagReranker          optional reranking
  `-- List<RagSearchResult> (carrying knowledge-base / document / chunk citations)
```

Optional Agent integration: `RagContextProvider` injects retrieval results into the context as a separate
"knowledge-base reference" section; RAG failures degrade to an empty knowledge context by default and never
block the main Agent flow.

## 3. Key Design Decisions

- **SPI-based extension**: parsing, chunking, embedding, document storage, vector index, and reranking are
  independent SPIs whose default beans are registered with `@ConditionalOnMissingBean`, so consumers can replace
  them wholesale (e.g. with Milvus / PGVector / ES).
- **Multi-format parsing**: the default `MultiFormatRagDocumentParser` dispatches by content type / extension —
  text / Markdown are built in; PDF (PDFBox) and Word (POI) are optional dependencies — add them to enable, or
  fall back to plain-text parsing with a clear notice.
- **Default local implementation (=file)**: text / Markdown parsing + local-file vector index + cosine-similarity
  retrieval, zero dependency out of the box; stored under `${user.dir}/.agent/rag`, fully isolated from `.agent/memory`.
- **Redis Stack retrieval (built-in recommended form, =db)**: `RedisRagIndexStore` composes "write MySQL
  (`rag_index_entries` text + metadata, no vector column) → write Redis Stack index" / "delete both sides" /
  "search Redis KNN / full-text + fetch metadata from MySQL"; retrieval depends on Redis Stack
  (RediSearch, `FT.CREATE` / `FT.SEARCH`); if Redis is lost, rebuild (reindex) by re-vectorizing the MySQL text.
- **API-level access control (optional)**: the `RagAccessPolicy` SPI authorizes knowledge-base visibility per
  tenant / user at the REST layer when `agent.rag.access.enabled=true`; when disabled everything passes —
  **the globally-shared retrieval semantics are unchanged**.
- **Capacity & quotas (optional)**: `agent.rag.capacity.*` limits the max documents per knowledge base, max
  chunks per document, and max parsed characters per document; `0` means unlimited.
- **Index consistency**: index metadata records `modelId` and the vector dimension, avoiding dimension mismatch
  when the embedding model changes.
- **Embedding batch constraint**: models cap the batch size per request (e.g. Aliyun MaaS is 20); the gateway
  batches internally by `max-batch-size`, while the outer `embedding-batch-size` only groups throughput.
- **Idempotent & atomic writes**: content `checksum` skips duplicates; delete the old index before writing the
  new one, so failures never expose half-written results.

## 4. Package Structure (by capability)

```text
domain.rag            infrastructure.rag
├── model              ├── write
├── config             ├── embed
├── write              ├── store
├── embed              ├── retrieve
├── store              └── context
├── retrieve
└── context
```

The domain layer holds only models and SPIs; the infrastructure layer holds default implementations. New
capabilities / implementations land in the matching sub-package by responsibility.

## 5. Configuration & Enablement

`agent.rag.enabled=true` turns it on (off by default); the index implementation is chosen by `agent.rag.provider`:
`auto` (follows `agent.storage.type`: file→`local` zero-dependency local-file index, db→`redis` Redis Stack
retrieval — default) | `redis` (explicit, equivalent to auto + db).
It depends on an OpenAI-compatible `/embeddings` endpoint — configure `RAG_EMBEDDING_MODEL/BASE_URL/API_KEY` in `.env`.

- PDF / Word parsing: add the optional dependencies `org.apache.pdfbox:pdfbox`, `org.apache.poi:poi-ooxml` to enable automatically;
- Redis retrieval: with `provider=redis` (or `auto` + `STORAGE_TYPE=db`) keep Redis Stack reachable (start
  `redis/redis-stack-server` via docker compose); the index runs `FT.CREATE` automatically on first write;
- Access control: enable `agent.rag.access.enabled=true` and register a `RagAccessPolicy` bean to authorize per tenant / role;
- Capacity quotas: `agent.rag.capacity.*` (see [Config Quick Reference](../reference/config-full.md)).

Full configuration: [Config Quick Reference](../reference/config-full.md) and [Configuration](../guide/configuration.md).

## 6. Example: example-web

- Backend: `example-web` enables RAG and demonstrates SPI extension through `ExampleRagConfiguration` —
  a custom `ExampleRagChunker` (chunk metadata extension tags) and `ExampleRagReranker` (second-pass ranking + logging).
- Frontend: `example-web-frontend` provides a **RAG management page** (knowledge-base maintenance, file upload,
  rebuild / delete, retrieval debug) and a **knowledge-base picker on the chat page** (which knowledge bases are
  injected for this conversation, passed through SSE params).
- REST API: see the `/rag` section of [REST API Reference](../reference/rest-api.md).

---

See also: [Memory Model](memory-model.md) ｜ [Configuration](../guide/configuration.md) ｜ [REST Reference](../reference/rest-api.md)
