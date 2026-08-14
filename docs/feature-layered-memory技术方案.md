# 分层记忆（Layered Memory）技术方案

> 目标：突破 LLM 上下文窗口的物理限制，通过**分层存储 + 动态换页 + 检索召回 + 自动提炼**，实现理论上无限的记忆容量。

## 一、背景与问题

### 1.1 现状

当前 memory 领域（`domain/memory`）已有两层记忆：

| 层         | 载体                                      | 读取                   | 写入                        | 局限                                  |
| --------- | --------------------------------------- | -------------------- | ------------------------- | ----------------------------------- |
| 短期记忆（会话级） | `Session` → `.agent/sessions/<id>.json` | 全量历史注入               | 每轮自动持久化                   | 历史**全量**注入 LLM，无 token 预算，长会话必然溢出窗口 |
| 长期记忆（跨会话） | `MEMORY.md` / `AGENT.md`                | 每轮自动注入 system prompt | 仅靠 LLM 显式调 `write_memory` | 单文件、无结构、无检索；写入不稳定                   |

### 1.2 痛点

1. **窗口物理上限**：上下文窗口固定（如 64K tokens），对话一长，早期信息必然被截断丢失；
2. **无预算管理**：`DefaultContextAssembler` 把 `session.messages` 全量拼入，无 token 分配、无换页；
3. **无自动沉淀**：长期记忆写入依赖 LLM 自觉调工具，重要信息易丢；不会自动总结/提炼；
4. **无检索能力**：记忆是"线性文本"，无法按相关性召回，容量再大也取不回来；
5. **跨会话断层**：新会话只有 MEMORY.md 少量信息，无法引用历史会话的上下文。

## 二、设计目标

- **分层存储**：按信息时效与重要性分层，冷热分离；
- **动态换页**：token 预算驱动，热数据原文在窗口内，冷数据压缩/落盘，按需换入；
- **检索召回**：关键词（零依赖）→ 向量（可选）两级检索，按需注入相关历史；
- **自动提炼**：对话中自动生成摘要、提取事实、合并去重，写入触发可控；
- **理论无限容量**：窗口内只放"预算内的活跃页"，其余全部落盘可召回；
- **兼容现有结构**：不破坏 `ContextAssembler` / `MemoryGateway` / 工具接口，渐进式落地。

## 三、总体架构：五层记忆

```
┌─────────────────────────────────────────────────────────┐
│                    LLM 上下文窗口（物理受限）               │
│   System(25%)  Tools(25%)  记忆页 Memory(50%)             │
│   ┌───────────┬───────────┬───────────────────────────┐  │
│   │ 指令层      │ 工具定义    │ 工作记忆 Working (热区)     │  │
│   │ AGENT.md  │           │ 最近 hotWindowSize 条原文   │  │
│   │           │           │ + 摘要页 + 事实页 + 检索页    │  │
│   └───────────┴───────────┴───────────────────────────┘  │
└──────────────────────────┬──────────────────────────────┘
                           │ 换页（预算溢出）/ 检索召回
┌──────────────────────────▼──────────────────────────────┐
│                 磁盘分层存储（理论无限）                    │
│  ┌─────────┐  ┌─────────┐  ┌──────────┐  ┌──────────┐   │
│  │ 短期记忆  │  │ 中期记忆  │  │ 长期记忆   │  │ 档案知识   │   │
│  │ Short    │  │ Mid     │  │ Long     │  │ Archival │   │
│  │ 会话完整  │→ │ 多级摘要页│→ │ 事实条目   │→ │ 归档+索引  │   │
│  │ 历史(冷)  │  │ Summary │  │ Facts    │  │ 全文可检索 │   │
│  └─────────┘  └─────────┘  └──────────┘  └──────────┘   │
└─────────────────────────────────────────────────────────┘
```

| 层        | 内容                     | 容量   | 读取方式        | 写入方式                  |
| -------- | ---------------------- | ---- | ----------- | --------------------- |
| **指令层**  | AGENT.md（Agent 个性/规则）  | 固定   | 自动注入 System | 手动改文件                 |
| **工作记忆** | 本轮活跃消息 + 换入的记忆页        | 预算内  | 每轮组装        | 随对话增长，换页出局            |
| **短期记忆** | 当前会话完整历史（原文）           | 磁盘全量 | 换页时按需       | 每轮自动持久化               |
| **中期记忆** | 历史块的多级摘要（Summary Tree） | 磁盘全量 | 摘要页注入       | 自动提炼（预算触发）            |
| **长期记忆** | 跨会话事实条目（结构化 Facts）     | 磁盘全量 | 事实页注入 / 检索  | 自动提炼 + `write_memory` |
| **档案知识** | 归档全文 + 关键词/向量索引        | 磁盘全量 | 检索召回（RAG）   | 工具写入 / 自动归档           |

## 四、核心机制一：动态换页（Paging）

### 4.1 Token 预算模型

```
窗口预算 = 模型上下文窗口 × memory.context-budget-ratio（默认 0.6）
预算内再分配：
  - System 区 25%（AGENT.md + 事实页 Facts）
  - Tools 区 25%（工具定义，MCP 动态工具）
  - Memory 区 50%（Hot 原文 + Summary 摘要 + Retrieved 检索页）
```

超出预算即触发**换页**，永不"硬截断"丢弃。

### 4.2 页面类型（MemoryPage）

| 类型          | 说明                                   | 生成     |
| ----------- | ------------------------------------ | ------ |
| `HOT`       | 最近 `hotWindowSize` 条消息原文             | 对话直接产生 |
| `SUMMARY`   | 旧块压缩摘要（多级：block → section → session） | LLM 提炼 |
| `FACT`      | 结构化事实条目（跨会话，带重要度/时间戳）                | LLM 提炼 |
| `RETRIEVED` | 按相关性召回的早期/历史片段                       | 检索器    |

### 4.3 换页流程

```
新消息入 Hot
   ↓
组装前检查预算：Hot + Summary + Facts + Retrieved ≤ budget
   ↓ 超出
把最旧的 hotBlock(10条) 压缩为 SUMMARY 页（LLM summarize）
   ↓ Summary 层级已满（depth 上限）
上层 Summary 再聚合 / 提取 FACT 归档 / 原文转入档案可检索
   ↓
换入：本轮组装时若有"早期信息缺口"（如用户问起很久前的话题）
   → 检索器召回相关片段 → 注入 RETRIEVED 页
```

### 4.4 多级摘要树（Summary Tree）

类似 map-reduce summarization，逐级压缩、信息损失逐级增加但保留骨架：

```
block-1(10条) ─┐
block-2(10条) ─┼─→ section-1 摘要 ─┐
block-3(10条) ─┘                    ├─→ session 摘要（最深，兜底召回）
block-4(10条) ─┐                    │
block-5(10条) ─┼─→ section-2 摘要 ─┘
block-6(10条) ─┘
```

- `summaryBlockSize`（默认 10 条/块）、`maxSummaryDepth`（默认 3 级）；
- 每级摘要页带 `tokenCount`，注入时按预算选取最合适粒度；
- 需要细节时向下回溯：会话摘要 → 章节摘要 → 块摘要 → 原文（检索）。

## 五、核心机制二：记忆提炼（Synthesis）

### 5.1 触发时机（可配置）

| 触发   | 说明                             | 默认                             |
| ---- | ------------------------------ | ------------------------------ |
| 轮次触发 | 每 N 轮对话后检查一次                   | `synthesizeTriggerRounds = 10` |
| 预算触发 | Hot 区 token 达到阈值即提炼            | 随预算模型自动                        |
| 会话结束 | 生成会话级摘要 + 提炼事实（`afterSession`） | 开启                             |
| 显式工具 | `write_memory`（升级为结构化写入）       | 保留                             |
| 用户指令 | "记住…/以后…" 由 LLM 判定调用工具         | 保留                             |

### 5.2 提炼动作（异步执行，不阻塞主对话链路）

1. **摘要** `summarize(block)`：旧 Hot 块 → SUMMARY 页；
2. **事实提取** `extract(messages)`：提取"用户偏好 / 项目背景 / 重要决策 / 约束"，带重要度评分（0-1）；
3. **合并去重** `merge(newFacts, existingFacts)`：相同主题冲突/重复合并，带版本与时间戳。

### 5.3 写入策略（何时真正写入长期记忆）

- 重要度 ≥ `importanceThreshold`（默认 0.6）的事实自动写入 `facts.jsonl`；
- 事实带 `key`（主题）去重合并，避免 MEMORY.md 无限膨胀；
- `write_memory` 工具升级为结构化写入（`content + topic + importance`），仍可显式覆盖。

## 六、核心机制三：检索召回（Retrieval）

### 6.1 两阶段演进

- **Phase 1：关键词 / BM25（零依赖）**
  - 建立倒排索引 `index.json`：token → pageId（原文、摘要、事实、档案）；
  - 查询：分词 → 倒排 → BM25 打分 → Top-K；
  - 文件系统即可实现，无外部依赖。
- **Phase 2：向量检索（可选）**
  - 通过 LLM embedding 接口（或本地模型）生成向量，存 `.agent/memory/vectors/`；
  - 余弦相似度召回，与关键词结果融合（RRF 融合排序）。

### 6.2 检索触发场景

| 场景    | 说明                       |
| ----- | ------------------------ |
| 换页缺口  | 组装上下文时发现早期信息缺口（用户提及过去话题） |
| 显式查询  | "我记得… / 之前讨论过…"          |
| 跨会话档案 | 新会话需要引用历史会话上下文           |

## 七、领域建模（DDD）

### 7.1 新增（`domain/memory` 包）

```
memory/
├── LayeredMemoryGateway.java     # 门面：readContext(预算) + afterTurn() + afterSession()
├── MemoryPage.java               # 记忆页（type/content/tokens/importance/timestamps/refs）
├── MemoryPageStore.java          # 页存储（文件）
├── MemoryIndex.java              # 索引（关键词 → pageId）
├── MemorySynthesizer.java        # 提炼器：summarize / extract / merge（依赖 LlmGateway）
├── PageEvictionPolicy.java       # 换页策略接口
│   ├── TokenBudgetPolicy.java    # 预算驱动（默认）
│   └── ImportancePolicy.java     # 重要度驱动（Phase 2）
├── MemoryRetriever.java          # 检索接口
│   ├── KeywordRetriever.java     # BM25（Phase 1）
│   └── VectorRetriever.java      # 向量（Phase 2）
└── MemoryConstants.java          # PageType / MemoryLayer 枚举
```

### 7.2 改造点（最小侵入）

| 类                         | 改动                                                                                                                                |
| ------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `DefaultContextAssembler` | `buildMessages/buildSystemPrompt` 改为向 `LayeredMemoryGateway.readContext(budget)` 取页：System(Facts) + Memory(Hot+Summary+Retrieved) |
| `ReActLoopService`        | `run/streamRun` 每轮结束调用 `memoryManager.afterTurn(session)`（预算检查→换页）                                                                |
| `ChatCmdExe`              | 会话结束时调用 `memoryManager.afterSession(session)`（会话摘要+事实提炼）                                                                          |
| `MemoryGateway`           | 保留（短期历史存取不变）；`LongTermMemoryGateway` 由 `MemorySynthesizer` 承接写入                                                                   |
| `WriteMemoryTool`         | 参数扩展 `topic/importance`，写入 `facts.jsonl` 而非覆盖 MEMORY.md                                                                           |

### 7.3 时序（一次对话）

```
用户输入 → ChatCmdExe
   ├─ session 加载（短期）→ 预算检查 → 检索换入（若缺口）→ contextAssembler.assemble()
   │     └─ LayeredMemoryGateway.readContext(budget)
   ├─ ReActLoop.run()（每轮：assemble → LLM → 工具）
   ├─ afterTurn()：Hot 溢出 → summarize / extract（异步）
   └─ afterSession()：会话级摘要 + 事实 merge
   └─ memoryGateway.saveSession()（短期持久化）
```

## 八、持久化格式

```
.agent/
├── AGENT.md                          # 指令层（不变）
├── sessions/<sessionId>.json         # 短期：完整历史（不变）
└── memory/
    ├── index.json                    # 关键词倒排索引（token → pageId）
    ├── facts.jsonl                   # 长期：事实条目（跨会话，按行）
    ├── pages/
    │   └── <sessionId>/
    │       ├── summaries/            # 中期：多级摘要页
    │       │   ├── block-1.json
    │       │   └── section-1.json
    │       └── retrieved.json        # 检索缓存（可选）
    ├── archive/                      # 档案：全文归档（可检索）
    └── vectors/                      # Phase 2：向量索引（可选）
```

事实条目示例（`facts.jsonl`）：

```json
{"key":"用户偏好-语言","content":"用户偏好用中文交流","importance":0.9,"sessionId":"ab12...","createTime":1690000000000,"version":3}
{"key":"项目背景-技术栈","content":"项目使用 COLA/DDD 架构","importance":0.8,"sessionId":"cd34...","createTime":1690000001000,"version":1}
```

## 九、配置项（application.yml）

```yaml
agent:
  memory:
    layered:
      enabled: true
      context-budget-ratio: 0.6        # 记忆区占模型窗口比例
      prompt-budget-ratio: 0.25        # System 区（AGENT.md + Facts）
      tool-budget-ratio: 0.25          # Tools 区
      hot-window-size: 20              # Hot 原文条数
      summary-block-size: 10           # 多少条消息合成一个摘要块
      max-summary-depth: 3             # 摘要树深度
      synthesize-trigger-rounds: 10    # 每 N 轮提炼检查
      importance-threshold: 0.6        # 事实写入阈值
      retriever: keyword               # keyword | vector
      top-k: 5                         # 检索召回条数
```

## 十、实施阶段（Roadmap）

- **Phase 1（本期落地）**：页结构与 token 预算 → 多级摘要换页 → 轮次/预算触发提炼 → `facts.jsonl` 结构化长期记忆 → 关键词检索 → `write_memory` 工具升级；
- **Phase 2**：重要度换页策略可插拔、事实 merge 去重、提炼异步化（线程池/事件）；
- **Phase 3**：向量检索、跨会话档案 RAG、多 Agent 共享记忆；
- **Phase 4**：成本优化（提炼调度与缓存、小模型提炼）、记忆可视化面板（`/memory` 查看分层内容）。

## 十一、风险与权衡

| 风险        | 缓解                             |
| --------- | ------------------------------ |
| 摘要信息损失    | 重要度保底 + 事实条目保留关键细节 + 原文归档可检索召回 |
| 提炼 LLM 成本 | 触发频率可控、可用小模型提炼、异步执行            |
| 组装延迟      | 索引/摘要缓存、预算内短路、检索 Top-K 限制      |
| 事实冲突/过期   | 条目带版本与时间戳、merge 时按时间戳保留最新      |

## 十二、验证方式

- **单元测试**：预算分配、换页触发、摘要树层级、事实去重合并、关键词检索 Top-K；
- **长会话压测**：连续 100+ 轮对话，断言每轮请求 token ≤ 预算、早期关键信息可召回；
- **记忆回访**：新会话提问"我之前说过…"，验证跨会话事实召回与摘要注入。

