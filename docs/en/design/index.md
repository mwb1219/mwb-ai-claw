---
title: Design Overview (EN)
has_children: true
nav_order: 5
parent: Documentation (English)
---

# Design Overview

> For readers who want to understand the principles. Each doc explains the model and key decisions of one subsystem.
>
> One-line positioning: mwb-ai-claw is a **Java Agent Harness** — an out-of-the-box, low-cost, easy-to-get-started Agent runtime framework. Every subsystem below (loop / orchestration / memory / RAG / storage / security / observability) together forms the runtime scaffolding around the model; LangChain4j / Spring AI supply the parts, this is the assembled machine. Every component is exposed through SPI so you can replace or enhance it as needed.

| Doc | Content |
| --- | --- |
| [Architecture](architecture.md) | DDD layers / module dependencies / Spring assembly |
| [ReAct Loop](core-loop.md) | Thought → Action → Observation |
| [Multi-Agent Orchestration](collaboration.md) | routing / conversational / delegate |
| [Layered Memory](memory-model.md) | Five-layer model / dynamic paging |
| [RAG Retrieval](rag.md) | Independent knowledge ingest / index / retrieve / context injection |
| [Storage & Multi-Tenancy](storage-multitenancy.md) | file / db backends / AgentScope |
| [Security Model](security.md) | Tool sandbox / approval / injection defense |
| [Observability & Resilience](observability.md) | Metrics / run logs / retry & degradation |
| [Horizontal Scaling](horizontal-scaling.md) | Multi-instance / shared storage / distributed lock / session routing |
| [Extensibility Design](extensibility.md) | Design intent / user-facing extension / SPI extension points |
