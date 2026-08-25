# example-commerce — E-commerce / Marketing Assistant

> mwb-ai-claw's "real-business example" that demonstrates the framework's pluggable multi-extension-point design.
> An e-commerce / marketing assistant: operators use natural language to query products, orders and campaigns,
> and have the Agent generate promo plans (with optional human approval) backed by a business knowledge base.

[中文版](README.md)

## 1. Extension Points Covered (matrix)

| Extension point | SPI / mechanism | Example implementation | Extension type |
| --- | --- | --- | --- |
| Custom business tools | `ToolExecutor` + `@Component` (auto-collected by `ToolGatewayImpl`) | `tool/ListProductsTool`, `ListOrdersTool`, `ListCampaignsTool`, `CreateCampaignTool` | Add new tools, zero-code integration |
| Data isolation (T2) | `AgentScope` + `AgentScopeContext` | `AbstractCommerceTool.withCurrentStore()` reads `store/CommerceDataStore` by `tenantId` | Multi-store isolation |
| Real tenant system (T2) | `TenantGateway` (queried first in the auth chain) | `tenant/CommerceTenantGateway` (in-memory merchant registry: `sk-store-a`/`sk-store-b` → store/operator) | Replace / integrate own tenant store |
| RAG chunking (enhance) | `RagChunker` (wraps default `TextRagChunker`) | `rag/CommerceRagChunker` | Wrap the default implementation |
| RAG reranking (enhance) | `RagReranker` (optional injection) | `rag/CommerceRagReranker` | Enhancement |
| RAG assembly | `@ConditionalOnMissingBean` | `rag/CommerceRagConfiguration` | Replace / enhance |
| Custom orchestration | `AgentOrchestrator` SPI (`type()` declaration, auto-collected as a Bean) | `orchestration/MarketingOrchestrator` (type=marketing) | New orchestration type plugin |
| Multimodal content | `ContentPart` / tool output image URLs | `ListProductsTool` returns product images as markdown | Content layer |
| Human-in-the-loop approval | `ApprovalRegistry`/`PendingApproval` (inside orchestration) | `MarketingOrchestrator` optional approval after plan generation; `orchestrations.json` enables `approvalGate` for `todo-delegate` | In-orchestration approval / existing delegate gate |

## 2. Quick Start

### 2.1 Local run

```bash
# 1. Copy .env and fill in the keys (DEFAULT_API_KEY, optional RAG_EMBEDDING_*)
cp src/main/resources/.env.example .env

# 2. Start (web mode by default, port 8080)
# Do it in two steps: first install the dependency modules to the local repo, then run example-commerce alone
# (it is now an independent project and is not built as part of the repo reactor).
mvn -pl example-commerce -am install -DskipTests   # 1) compile & install dependency modules
mvn -pl example-commerce spring-boot:run           # 2) run example-commerce alone
# or build a jar: java -jar example-commerce/target/example-commerce-*.jar
```

### 2.2 One-click Docker build (recommended)

`docker-compose.yml` and `Dockerfile` live in the `example-commerce` directory; they build and start the
backend and frontend in one shot:

```bash
# 1. Copy .env and fill in the keys (DEFAULT_API_KEY, optional RAG_EMBEDDING_*)
cp src/main/resources/.env.example .env

# 2. Build and start (run inside the example-commerce directory)
#    Framework dep mwb-ai-claw-spring-boot-starter:1.0.3 (pulled from Maven Central, no local repo needed)
docker compose up -d --build

# 3. Verify
docker compose ps        # example-commerce / example-commerce-frontend both healthy

# 4. Access
# Frontend console: http://localhost:5174 (API proxied to the backend by Nginx)
# Backend REST:    http://localhost:8081 (host port; container port is 8080, coexists with example-web's 8080)
```

> Note: this example has zero middleware dependencies (in-memory H2 + file storage + local RAG), so MySQL / Redis
> are not needed. The Dockerfile copies `.env` (container-flavored) and `agents.json` / `orchestrations.json`
> into `/app` in the image; they are loaded with "run-directory-first" priority at startup
> (ConfigFileLocator / DotenvEnvironmentPostProcessor).

## 3. Try It (REST / SSE)

> Port note: the examples below use the local port `8080`; under Docker the host port is `8081`
> (container port is still 8080), just replace it.

Auth is enabled; use store keys to separate tenants (multi-store isolation):

```bash
# Store A operator (store-a/op-a)
curl -X POST http://localhost:8080/agent/chat \
  -H "X-API-Key: sk-store-a" -H "Content-Type: application/json" \
  -d '{"message":"帮我看看我们店都有哪些商品，然后生成一份促销方案"}'

# Store B operator (store-b/op-b, data isolated from store A)
curl -X POST http://localhost:8080/agent/chat \
  -H "X-API-Key: sk-store-b" -H "Content-Type: application/json" \
  -d '{"message":"列出商品"}'
```

Result: the Agent calls `list_products` (with product images) → `list_orders`/`list_campaigns` in turn, then
generates a promo plan using RAG business knowledge; each store reads its own isolated data.

### Custom orchestration (type=marketing)

```bash
curl -X POST http://localhost:8080/agent/chat \
  -H "X-API-Key: sk-store-a" -H "Content-Type: application/json" \
  -d '{"message":"结合当前商品与活动，生成一份促销方案","orchestrationId":"marketing","sessionId":"sess-a-1"}'
```

### Approval gate (human-in-the-loop)

To require human confirmation after a plan is generated, set `config.approvalEnabled: true` for `marketing`
in `orchestrations.json` (and `approvalTimeoutMs`), then process it via the REST approval endpoints:

```bash
curl -G http://localhost:8080/agent/pending-tasks -H "X-API-Key: sk-store-a" --data-urlencode "sessionId=sess-a-1"
curl -X POST http://localhost:8080/agent/approve -H "X-API-Key: sk-store-a" \
  -H "Content-Type: application/json" -d '{"sessionId":"sess-a-1","layerKey":"root"}'
```

> The `todo-delegate` orchestration already ships with `approvalGate=all`, useful for demonstrating a
> "plan marketing rollout + approve every layer" flow.

### RAG business knowledge base

```bash
# Upload a marketing handbook so the Agent can retrieve business knowledge
curl -X POST http://localhost:8080/rag/knowledge-bases/marketing/docs/upload \
  -H "X-API-Key: sk-store-a" -F "file=@./营销手册.md"
```

## 4. Notes

- `store/CommerceDataStore` is an in-memory mock; replace it with real business APIs / a DB in production.
- `tenant/CommerceTenantGateway` is an in-memory registry demo; in production it should integrate your tenant
  table / SSO / IAM and manage the API key lifecycle (issue / rotate / revoke).
- `create_campaign` is a high-privilege write operation and is executed directly in this example; in production,
  combine it with a delegated orchestration `approvalGate` / tool-level permissions, or hook an approval service
  inside the tool so a human makes the launch decision.

## 5. Web Console (observable entry)

The frontend lives in [example-commerce-frontend](../example-commerce-frontend) and provides an observable chat UI:

- Store picker (`/login`): enter with `sk-store-a` / `sk-store-b` (or a custom key), demonstrating multi-store isolation;
- Chat page: shows the current store at the top plus an orchestration selector (`routing`=SSE streaming / `marketing` / `todo-delegate`=REST),
  and renders the Thought / Action / tool call / Observation reasoning timeline in real time on the right;
- Also ships with session management, knowledge base (upload marketing handbooks via RAG) and human-approval pages.

Run the frontend:

```bash
cd example-commerce-frontend
npm ci
npm run dev        # http://localhost:5174 (Vite dev proxy → 8080, no CORS needed)
npm run typecheck  # tsc type check
npm run build      # production build → dist/
```

The backend must be started on 8080 first (see above); in production the backend serves `dist/` statically with
same-origin CORS (allow the frontend origin, e.g. `http://localhost:5174`, in `example.cors.allowed-origins`).

### 5.1 Walkthrough (screenshots)

The following screenshots show a real session on `http://localhost:5174` (with the backend started as above):

**① Store picker (`/#/login`)**: choose "Store A · Operator A" (`sk-store-a`, tenant=store-a) to enter, demonstrating multi-tenant store isolation.

![Store picker](screenshots/01-login.jpg)

**② Chat main view (`/#/chat`)**: the current store "Store A" is shown at the top; switch orchestration modes (`routing`=SSE streaming / `marketing`=REST promo plan / `todo-delegate`=REST delegation); the reasoning-timeline panel is on the right.

![Chat main view](screenshots/02-chat-home.jpg)

**③ Full marketing-orchestration conversation**: with the `marketing` orchestration selected, ask "帮我看看我们店有哪些商品，然后结合活动生成一份促销方案". The Agent automatically calls the three business tools `list_products` → `list_orders` → `list_campaigns` and, from the store-isolated data, produces a store analysis (products on sale / orders / existing campaigns) plus actionable promo tactics (cross-category spend threshold, backpack bundle to break zero sales, wristband as a traffic magnet).

![Marketing plan result](screenshots/03-marketing-result.jpg)

**④ Reasoning-timeline panel**: the right panel streams the full ReAct timeline — Thought / Action (with tool args) / Observation (store data returned by tools) — making tool calls and multi-tenant data isolation easy to observe.

![Reasoning timeline](screenshots/04-trace-timeline.jpg)
