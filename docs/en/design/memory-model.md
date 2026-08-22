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

## 4. Fact Distillation and Merging

- [ ] LLM distills facts (key / content / importance); importance filtering + same-key merge and dedup
- [ ] Distillation is asynchronous (does not block the main conversation flow); results are cached (content-hash dedup)

## 5. Memory Tools

- [ ] `read_memory` / `write_memory` (called from the LLM side)
- [ ] Shell `/memory` and the REST memory panel

---

See also: [Configuration Guide](../guide/configuration.md)
