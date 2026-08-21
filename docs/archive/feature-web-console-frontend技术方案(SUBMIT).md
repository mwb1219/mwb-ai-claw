# mwb-ai-claw Web 控制台前端重开发 技术方案(SUBMIT)

> 状态：待评审（2026-08-21）
> 技术栈：React 18 + TypeScript + Vite（用户已确认）；独立前端工程（用户已确认）
> 关联：[前端-PRD.md](../.trae/documents/前端-PRD.md)、[前端-技术架构.md](../.trae/documents/前端-技术架构.md)（旧方案，本次重写）

## 1. 背景与目标

现有 `frontend/` 为早期联调用测试控制台（零依赖 Vanilla HTML/CSS/JS），存在以下问题：

- 仅覆盖 `/agent/*` 对话与会话接口，未覆盖记忆面板（`/memory/*`）与人工审批（`/agent/pending-tasks` 等）；
- 保留已废弃的**同步对话**（`POST /agent/chat`）与 **WebSocket**（`/ws/agent`）模式，链路冗余；
- 仅深色单一主题，无亮色主题与主题切换。

本次目标：

| # | 目标 | 说明 |
| ---- | ---- | ---- |
| F1 | 目录重命名 | `frontend/` → `example-web-frontend/`，作为独立前端工程 |
| F2 | 完全重新开发 | React 18 + TypeScript + Vite，组件化、模块化 |
| F3 | 完全覆盖 adapter 的 web 接口 | 会话管理、SSE 流式对话、记忆可视化、人工审批、记忆检索调试全量接入 |
| F4 | 对话只走流式 | 仅使用 SSE `GET /agent/chat/stream`，不调用 `POST /agent/chat`，不使用 WebSocket |
| F5 | 主题系统 | 亮色主题：浅蓝 + 白底；暗色主题：深蓝黑底；一键切换并持久化 |

## 2. 现状盘点

### 2.1 后端 Web 接口清单（`@Profile("web")`，example-web 默认激活）

来源：`mwb-ai-claw-adapter/src/main/java/com/mwb/ai/claw/web/`

| 控制器 | 接口 | 方法/路径 | 用途 | 前端接入 |
| ---- | ---- | ---- | ---- | ---- |
| AgentController | 同步对话 | `POST /agent/chat` | 一次性返回回复 | **不用**（F4） |
| AgentController | 流式对话 | `GET /agent/chat/stream?message=&sessionId=&agentId=` | SSE 增量推送 | **核心** |
| AgentController | 创建会话 | `POST /agent/session` | body: `CreateSessionCmd{agentId?, title?}` | 使用 |
| AgentController | 查询会话 | `GET /agent/session/{sessionId}` | 会话详情（含消息） | 使用 |
| AgentController | 会话列表 | `GET /agent/sessions` | 全部会话 | 使用 |
| AgentController | 删除会话 | `DELETE /agent/session/{sessionId}` | 删除 | 使用 |
| ApprovalController | 待审批列表 | `GET /agent/pending-tasks?sessionId=` | 按会话过滤 | 使用 |
| ApprovalController | 审批通过 | `POST /agent/approve` | body: `ApprovalCmd` | 使用 |
| ApprovalController | 审批拒绝 | `POST /agent/reject` | body: `ApprovalCmd` | 使用 |
| MemoryController | 记忆总览 | `GET /memory` | 配置 + 三层统计 + 提炼状态 | 使用 |
| MemoryController | 长期事实 | `GET /memory/facts` | 重要度降序 | 使用 |
| MemoryController | 中期摘要 | `GET /memory/summaries?sessionId=` | 可按会话过滤 | 使用 |
| MemoryController | 档案归档 | `GET /memory/archive?sessionId=` | 可按会话过滤 | 使用 |
| MemoryController | 检索调试 | `GET /memory/search?q=&topK=` | 关键词/向量/hybrid 召回 | 使用 |
| AgentWebSocketHandler | WebSocket | `WS /ws/agent` | 流式对话（旧） | **不用**（F4） |

### 2.2 SSE 流式协议（`GET /agent/chat/stream`）

```
event: session   → data: <sessionId>             # 首帧，先获取/创建会话
event: step      → data: <traceStep>             # ReAct 推理轨迹（Thought/Action/Observation 文本）
event: token     → data: <token增量>             # LLM token 级增量（核心）
event: tool_name → data: <toolName>
event: tool_args → data: <argDelta>              # 工具参数增量
event: reply     → data: <最终回复全文>           # 完成后整段推送
event: done      → data:                          # 结束
event: error     → data: <错误信息>
```

注意：`sendSseEvent` 将 data 按 `\n` 拆分为多行 `data:` 发送，浏览器端需按 SSE 规范拼接还原（EventSource 自动处理；fetch 手写解析需自行拼接）。

### 2.3 DTO 结构（`mwb-ai-claw-client` / `mwb-ai-claw-domain`）

统一响应包裹 `SingleResponse<T>`：`{ success, errCode?, errMessage?, data? }`。

- `ChatResponseDTO`：`sessionId, agentId, orchestrationId, reply, traceSteps[]`
- `SessionDTO`：`sessionId, agentId, title, status, createTime, updateTime, messages[]`
- `MessageDTO`：`role, content, timestamp`
- `PendingApprovalDTO`：`sessionId, layerKey, task, todoTitles[], todoCount, createdAt`
- `ApprovalCmd`：见 `mwb-ai-claw-client`，含 `sessionId/layerKey` 等
- `MemoryPage`：`pageId, type(HOT/SUMMARY/FACT/RETRIEVED/ARCHIVE), content, key, importance, tokenCount, sessionId, blockStart, blockEnd, createTime, version`
- `/memory` 总览结构：`{ enabled, config{...}, stats{facts:[count,tokens], summaries:[...], archives:[...], archiveBySession{}}, synthesis{cache, pendingTasks} }`

### 2.4 鉴权与跨域（关键约束）

- `AuthInterceptor` 覆盖 `/agent/**`（含 SSE）；`/memory/**` 不拦截。
- `auth.enabled=false`（默认）直接放行，scope=default。
- `auth.enabled=true` 时校验顺序：`X-API-Key` 头 → `Authorization: Bearer` → `?apiKey=` 查询参数（SSE 专用）。
- **现状**：example-web 无 CORS 配置，`start` 模块有 `CorsConfig` 但 example-web 未启用。独立前端工程（dev server 5173 / 生产静态托管）访问后端属跨域，**需要后端补充 CORS**（见 §8 配套改动）。

## 3. 技术选型

| 项 | 选型 | 理由 |
| ---- | ---- | ---- |
| 框架 | React 18 | 用户确认；组件化适合控制台多面板 |
| 语言 | TypeScript | 后端 DTO 结构清晰，全量类型定义可减少联调错误 |
| 构建 | Vite 5 | 用户确认；dev server 快、产物轻 |
| 路由 | React Router 6（HashRouter） | 纯静态托管友好，无需服务端 rewrite |
| 状态 | Zustand | 轻量；settings/session/chat 三个 store 即可 |
| Markdown | react-markdown + remark-gfm | 对话回复与轨迹为 Markdown |
| 图标 | lucide-react | 轻量线性图标 |
| UI | 自研轻量组件 + CSS 变量 | 主题（亮/暗）需精确可控，不引入重 UI 库 |
| 富交互 | 原生实现 | 代码块复制、消息流、主题切换均轻量自研 |

不使用：非流式 `POST /agent/chat`、WebSocket、重型组件库（AntD/MUI）。

## 4. 总体架构

```
example-web-frontend/                    # 独立前端工程（原 frontend/ 重命名）
├── index.html
├── package.json
├── vite.config.ts                       # dev 代理（可选，见 §8）/ build 配置
├── tsconfig.json
└── src/
    ├── main.tsx                         # 入口：主题初始化 + Router
    ├── App.tsx                          # 布局骨架（Topbar + Sidebar + 路由出口）
    ├── api/
    │   ├── types.ts                     # DTO 类型（与后端一一对应，§2.3）
    │   ├── client.ts                    # REST 封装（fetch + 统一错误处理 + 鉴权头）
    │   └── sse.ts                       # SSE 客户端（EventSource / fetch 双模式）
    ├── pages/
    │   ├── ChatPage.tsx                 # 对话页（核心）
    │   ├── MemoryPage.tsx               # 记忆可视化面板
    │   └── ApprovalPage.tsx             # 人工审批面板
    ├── components/
    │   ├── layout/                      # Topbar / Sidebar / SessionList
    │   ├── chat/                        # MessageList / MessageBubble / TraceTimeline / Composer / CodeBlock
    │   ├── memory/                      # MemoryOverview / FactList / SummaryList / ArchiveList / SearchDebug
    │   ├── approval/                    # PendingList / ApproveReject
    │   └── common/                      # Button / Card / Tag / Loading / Empty / ConfirmDialog / ThemeSwitch
    ├── store/
    │   ├── settings.ts                  # 后端地址、AgentId、apiKey、主题(theme)
    │   ├── session.ts                   # 会话列表、当前会话、历史消息
    │   └── chat.ts                      # 流式状态（busy / 当前气泡 / 轨迹）
    ├── styles/
    │   ├── theme.css                    # CSS 变量（亮/暗两套调色板）
    │   ├── global.css                   # 基础样式
    │   └── components.css               # 组件样式
    └── utils/
        └── format.ts                    # 时间/大小/重要度格式化
```

## 5. API 层设计

### 5.1 REST 客户端（`api/client.ts`）

- 统一 `baseUrl` 取自 settings store（默认 `http://localhost:8080`，可编辑）；
- 统一 `SingleResponse<T>` 解包：`success=false` 抛带 `errCode/errMessage` 的异常；
- 鉴权头：settings 中配置 `apiKey` 时自动附加 `X-API-Key`（REST）与 `?apiKey=`（SSE）；
- 方法清单（与 §2.1 对应）：

```ts
listSessions(): Promise<SessionDTO[]>
getSession(sessionId): Promise<SessionDTO>
createSession(cmd: CreateSessionCmd): Promise<SessionDTO>
deleteSession(sessionId): Promise<void>
pendingTasks(sessionId?): Promise<PendingApprovalDTO[]>
approve(cmd: ApprovalCmd): Promise<void>
reject(cmd: ApprovalCmd): Promise<void>
memoryOverview(): Promise<MemoryOverview>
memoryFacts(): Promise<MemoryPage[]>
memorySummaries(sessionId?): Promise<MemoryPage[]>
memoryArchive(sessionId?): Promise<MemoryPage[]>
memorySearch(q, topK=5): Promise<MemoryPage[]>
```

### 5.2 SSE 客户端（`api/sse.ts`）—— 核心模块

统一回调接口：

```ts
interface StreamCallbacks {
  onSession(sessionId: string): void;
  onStep(step: string): void;
  onToken(token: string): void;
  onToolName(name: string): void;
  onToolArgs(delta: string): void;
  onReply(reply: string): void;
  onDone(): void;
  onError(message: string): void;
}
chatStream(cmd: { message, sessionId?, agentId? }, cb: StreamCallbacks): { close(): void }
```

双模式实现：

1. **EventSource 模式（默认）**：无自定义头需求、无需 POST 时使用。鉴权走 `?apiKey=` 查询参数；浏览器自动处理多行 `data:` 拼接与断线事件。
2. **fetch + ReadableStream 模式**：需要自定义头（如 `Authorization: Bearer`）或需取消控制时使用。手写 SSE 帧解析（`event:` / `data:` 累积，`\n\n` 分帧，多行 data 用 `\n` 拼接），支持 `AbortController` 主动中断。

两者对上层暴露完全一致的 `chatStream` 签名，页面层无感知。

## 6. 页面与功能设计

### 6.1 布局（三栏 → 顶栏 + 左侧栏 + 主区）

```
┌─────────────────────────────────────────────────────┐
│ Topbar: ◈ mwb-ai-claw │ 后端地址 │ AgentId │ APIKey │ 主题切换 │
├──────────────┬──────────────────────────────────────┤
│ Sidebar      │  Main 区（路由：/chat /memory /approval）│
│  会话列表     │                                      │
│  + 新建 / ↻  │   对话页 / 记忆面板 / 审批面板        │
│  切换/删除    │                                      │
├──────────────┴──────────────────────────────────────┤
│ 状态条（请求状态 / 流式状态 / 错误提示）               │
└─────────────────────────────────────────────────────┘
```

### 6.2 对话页（ChatPage，核心）

- **消息流**：用户 / 助手 / 工具 / 系统四类气泡；助手回复 Markdown 渲染（代码块高亮 + 一键复制）；
- **流式渲染策略**：流式期间 `onToken` 增量以纯文本追加（避免半截 Markdown 闪烁），`onDone` 后统一切 Markdown 渲染（沿用旧控制台成熟做法）；
- **推理轨迹**：右侧可折叠时间线，`step` 事件按 `[Thought]/[Action]/[Observation]` 分类着色；
- **工具调用**：`tool_name` / `tool_args` 在轨迹区展示执行状态；
- **会话联动**：`session` 首帧 → 自动插入会话列表并高亮；消息沿用当前会话；
- **交互**：Enter 发送 / Shift+Enter 换行；发送中禁用输入并显示停止按钮（AbortController 中断 SSE）；
- **Agent ID**：顶部输入，非空时随请求 `agentId=` 传递。

### 6.3 记忆面板（MemoryPage）

- **总览区**：三层（FACTS / SUMMARIES / ARCHIVES）统计卡片（条数 + 估算 token），`enabled/config` 摘要，`synthesis.cache/pendingTasks`；
- **Tab 子区**：
  - 事实列表：重要度降序，展示 `key / importance(进度条) / content / version / 时间`；
  - 摘要列表：按会话过滤下拉，展示 `blockStart-blockEnd / content / tokenCount`；
  - 归档列表：按会话聚合分布（`archiveBySession`）+ 条目列表；
  - 检索调试：`q` 输入 + `topK` 选择 → 召回结果列表（含类型/重要度/会话）。

### 6.4 审批面板（ApprovalPage）

- 顶部 `sessionId` 过滤输入 + 刷新；
- 待审批卡片：`layerKey / task / todoCount / todoTitles 列表 / createdAt`；
- 操作：`审批通过` / `审批拒绝`（带二次确认），成功后局部刷新；
- 与对话页联动：对话触发审批暂停时提示"有待审批任务，请到审批面板处理"。

## 7. 主题系统（F5）

### 7.1 机制

- `styles/theme.css` 定义两套 CSS 变量调色板，挂在 `:root[data-theme="light"]` 与 `:root[data-theme="dark"]`；
- 默认主题：优先 `localStorage['claw-theme']` → `prefers-color-scheme`（暗色偏好则暗色）→ `light`；
- 切换：Topbar `ThemeSwitch`，写 `document.documentElement.dataset.theme` + 持久化 localStorage；
- 页面 `color-scheme` 同步，保证滚动条/表单原生控件跟随。

### 7.2 调色板（CSS 变量）

| 变量 | 亮色（浅蓝 + 白） | 暗色（深蓝黑） |
| ---- | ---- | ---- |
| `--bg` | `#f5f9ff`（极浅蓝白） | `#0e1626`（深蓝黑） |
| `--bg-surface` | `#ffffff` | `#16223a` |
| `--bg-hover` | `#e3f0ff` | `#1d2c48` |
| `--text` | `#1c2733` | `#e6edf7` |
| `--text-secondary` | `#5b6b7c` | `#93a4bd` |
| `--border` | `#d6e4f5` | `#263552` |
| `--primary` | `#1e88e5`（浅蓝主色） | `#4da3ff` |
| `--primary-bg` | `#e3f2fd` | `#17324f` |
| `--primary-text` | `#0b5cad` | `#bfe0ff` |
| `--success` | `#2e7d32` | `#5ccb7a` |
| `--warning` | `#b26a00` | `#e8b64c` |
| `--danger` | `#c62828` | `#ff7b72` |
| `--code-bg` | `#f2f6fb` | `#0f1a2e` |
| `--shadow` | `rgba(30,136,229,.12)` | `rgba(0,0,0,.45)` |

所有组件样式一律引用变量，不写死色值，保证双主题一致。

## 8. 配套改动（后端，最小侵入）

1. **CORS**：`example-web` 增加 `CorsConfig`，放行 `allowedOriginPatterns: ${WEB_CORS_ORIGINS:http://localhost:5173}`，允许 GET/POST/DELETE + `X-API-Key` 头；`/agent/**` 与 `/memory/**` 均生效。
2. **（可选）dev 代理**：前端 `vite.config.ts` 配置 `/agent`、`/memory` 代理到 `http://localhost:8080`，开发期可免 CORS；生产仍走 CORS。
3. 后端业务代码零改动。

## 9. 开发 / 构建 / 运行

```bash
# 开发（默认 http://localhost:5173，proxy 到 8080）
cd example-web-frontend
npm install
npm run dev

# 生产构建
npm run build          # 产出 dist/
# 静态托管 dist/（Nginx / 任意静态服务器），后端开启 CORS 并指向实际后端地址
```

版本基线：`react@18.x`、`typescript@5.x`、`vite@5.x`、`react-router-dom@6.x`、`zustand@4.x`、`react-markdown@9.x`、`remark-gfm@4.x`、`lucide-react`。

## 10. 实施步骤与验收

| 里程碑 | 内容 | 验收 |
| ---- | ---- | ---- |
| M1 | 目录重命名 + Vite/TS 脚手架 + 主题系统 + 布局骨架 | 双主题切换即时生效并持久化；页面可路由 |
| M2 | API 层（REST + SSE 双模式）+ 会话管理 + 流式对话页 | 流式对话端到端可用；会话增删查列齐全；轨迹/工具调用展示 |
| M3 | 记忆面板 + 审批面板 + 检索调试 | 五类记忆接口、三类审批接口全部接入 |
| M4 | 打磨：暗色完整适配、响应式、加载/错误/空态、状态条 | 无接口遗漏；主题无硬编码色值残留 |

总体验收：

- [ ] `frontend/` 已重命名为 `example-web-frontend/`
- [ ] 对话仅走 SSE，代码中不存在 `POST /agent/chat` 与 WebSocket 调用
- [ ] §2.1 表中"使用"列接口全部接入并可用
- [ ] 亮色为浅蓝 + 白，暗色为深蓝黑，可一键切换并持久化

## 11. 风险与对策

| 风险 | 对策 |
| ---- | ---- |
| EventSource 无法自定义 header（auth.enabled=true 场景） | SSE 客户端双模式，需 header 时用 fetch + ReadableStream |
| 独立部署跨域被拦截 | 后端补充 CorsConfig（§8），开发期可走 Vite 代理 |
| 超长流式回复渲染卡顿 | 流式期间纯文本追加，完成后再 Markdown 渲染；消息列表虚拟化预留 |
| 多行 data 拼接错误 | 复用 EventSource 原生能力；fetch 模式严格按 SSE 规范拼接 |
| 后端接口字段与前端类型漂移 | types.ts 集中定义并与 §2.3 对齐；联调期以实际 JSON 校验 |
