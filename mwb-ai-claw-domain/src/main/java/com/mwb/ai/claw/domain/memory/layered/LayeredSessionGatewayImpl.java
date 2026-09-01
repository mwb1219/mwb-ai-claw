package com.mwb.ai.claw.domain.memory.layered;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.MessageRole;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.core.SessionGateway;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.memory.layered.spi.PageEvictionPolicy;
import com.mwb.ai.claw.domain.memory.layered.model.EvictionContext;
import com.mwb.ai.claw.domain.memory.layered.model.MemoryBudget;
import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;
import com.mwb.ai.claw.domain.memory.layered.spi.MemoryRetriever;
import com.mwb.ai.claw.domain.memory.layered.spi.MemoryPageStore;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesizer;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher.SynthesisEvent;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher;
import com.mwb.ai.claw.domain.memory.layered.evict.ImportanceEvictionPolicy;
import com.mwb.ai.claw.domain.memory.layered.evict.TokenBudgetEvictionPolicy;
import com.mwb.ai.claw.domain.util.TokenEstimator;

/**
 * 分层记忆门面实现：工作记忆组装（预算内）+ 摘要换页（策略可插拔）+ 事实提炼与合并（异步）+ 检索。
 */
public class LayeredSessionGatewayImpl implements LayeredMemoryGateway {

    private static final Logger log = LoggerFactory.getLogger(LayeredSessionGatewayImpl.class);

    private final LayeredMemoryConfig config;
    private final MemoryPageStore pageStore;
    private final MemorySynthesizer synthesizer;
    private final MemoryRetriever retriever;
    private final PageEvictionPolicy evictionPolicy;
    private final MemorySynthesisDispatcher taskQueue;
    private final SessionGateway sessionGateway;

    public LayeredSessionGatewayImpl(LayeredMemoryConfig config,
                                    MemoryPageStore pageStore,
                                    MemorySynthesizer synthesizer,
                                    MemoryRetriever retriever,
                                    MemorySynthesisDispatcher taskQueue,
                                    SessionGateway sessionGateway) {
        this.config = config;
        this.pageStore = pageStore;
        this.synthesizer = synthesizer;
        this.retriever = retriever;
        // 换页策略可插拔：importance | token（默认）
        this.evictionPolicy = "importance".equalsIgnoreCase(config.getEvictionPolicy())
                ? new ImportanceEvictionPolicy() : new TokenBudgetEvictionPolicy();
        this.taskQueue = taskQueue;
        this.sessionGateway = sessionGateway;
        log.warn("分层记忆配置: enabled={}, window={}, budgetRatio={}, hotWindow={}, blockSize={}, threshold={}, topK={}, policy={}, async={}, retriever={}, vector={}, archive={}, sharedRetrieve={}, model={}",
                config.isEnabled(), config.getContextWindowTokens(), config.getContextBudgetRatio(),
                config.getHotWindowSize(), config.getSummaryBlockSize(),
                config.getImportanceThreshold(), config.getTopK(),
                config.getEvictionPolicy(), config.isSynthesisAsync(),
                config.getRetriever(), config.isVectorEnabled(),
                config.isArchiveEnabled(), config.isSharedRetrieve(),
                config.getStrategy());
    }

    @Override
    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    @Override
    public MemoryView readContext(Session session, Agent agent) {
        MemoryView view = new MemoryView();
        if (!isEnabled()) {
            view.setWorkingMessages(session.getMessages());
            view.setSummaryPages(new ArrayList<>());
            view.setFactPages(new ArrayList<>());
            view.setRetrievedPages(new ArrayList<>());
            return view;
        }

        MemoryBudget budget = new MemoryBudget(config);
        List<Message> all = session.getMessages();
        AgentScope scope = session.getScope();

        // 1. 跨会话事实页（重要度降序，进入 System 区预算）
        List<MemoryPage> facts = pageStore.loadFacts(scope);
        facts.sort(Comparator.comparingDouble(MemoryPage::getImportance).reversed());
        facts = trimByTokens(facts, budget.getSystemBudget());

        // 2. 当前会话摘要页（占用 Memory 区预算）
        List<MemoryPage> summaries = pageStore.loadSummaries(scope, session.getSessionId());
        int summaryTokens = summaries.stream().mapToInt(TokenEstimator::estimate).sum();

        // 3. 工作记忆原文 + 共享检索：两者共享 Memory 区预算，检索从 HOT 份额中挤（补充而非抢占）。
        //    先算 HOT 可拿多少，再从中切一块给检索（至少 256 token，否则检索结果太少无意义）。
        int memoryBudget = budget.getMemoryBudget();
        int hotTokens = Math.max(memoryBudget - summaryTokens, 0);
        List<MemoryPage> retrieved = new ArrayList<>();
        if (config.isSharedRetrieve()) {
            String query = latestUserText(all);
            if (query != null && !query.trim().isEmpty()) {
                // retrieved 从 HOT 份额里切：至少 256，至多 HOT 的 1/5，且不超过 HOT 本身（防止挤成负数）
                int retrievedBudget = Math.min(Math.max(256, hotTokens / 5), hotTokens);
                if (retrievedBudget > 0) {
                    retrieved = trimByTokens(retriever.search(scope, query.trim(), config.getTopK()), retrievedBudget);
                    // 实际消费的 retrieved token 从 HOT 份额里扣除，保证 summary + hot + retrieved <= memoryBudget
                    int retrievedUsed = retrieved.stream().mapToInt(TokenEstimator::estimate).sum();
                    hotTokens = Math.max(hotTokens - retrievedUsed, 0);
                    if (!retrieved.isEmpty()) {
                        log.debug("分层记忆: 共享检索换入 {} 条（查询 '{}'，消耗 {} token）",
                                retrieved.size(), query.trim(), retrievedUsed);
                    }
                }
            }
        }
        List<Message> hot = takeRecentMessages(all, hotTokens, config.getHotWindowSize());

        view.setWorkingMessages(hot);
        view.setSummaryPages(summaries);
        view.setFactPages(facts);
        view.setRetrievedPages(retrieved);
        return view;
    }

    @Override
    public void afterTurn(Session session, Agent agent) {
        if (!isEnabled()) {
            return;
        }
        // 提炼（摘要生成）是 LLM 调用，异步执行不阻塞主对话链路。
        // 通过 MemorySynthesisDispatcher.produce 投递：快照延迟获取（锁/claim 内才取），
        // 执行回调 doAfterTurn 在 consume 阶段被调用。
        final String sessionId = session.getSessionId();
        final AgentScope scope = session.getScope();
        if (config.isSynthesisAsync()) {
            taskQueue.produce(new MemorySynthesisDispatcher.SynthesisEvent(
                    scope, sessionId, MemorySynthesisDispatcher.Kind.AFTER_TURN,
                    () -> new ArrayList<>(sessionGateway.loadAllMessages(scope, sessionId)),
                    ctx -> doAfterTurn(ctx.scope, ctx.sessionId, ctx.getSnapshot())));
        } else {
            doAfterTurn(scope, sessionId, sessionGateway.loadAllMessages(scope, sessionId));
        }
    }

    /** 轮次内换页：按可插拔策略判断是否把最旧未摘要块压缩为摘要页 */
    private void doAfterTurn(AgentScope scope, String sessionId, List<Message> all) {
        int blockSize = config.getSummaryBlockSize();
        int lastSummarized = lastSummarizedIndex(scope, sessionId);
        EvictionContext ctx = new EvictionContext(all, lastSummarized, TokenEstimator.estimate(all),
                new MemoryBudget(config).getContextBudget(), blockSize, config.getImportanceThreshold());
        if (!evictionPolicy.shouldEvict(ctx)) {
            return;
        }
        int end = Math.min(lastSummarized + blockSize, all.size());
        List<Message> block = new ArrayList<>(all.subList(lastSummarized, end));
        String summary = synthesizer.summarizeBlock(scope, block);
        if (summary != null && !summary.isEmpty()) {
            pageStore.saveSummary(scope, MemoryPage.summary(
                    "summary-" + sessionId + "-" + lastSummarized,
                    summary, sessionId, lastSummarized, end,
                    TokenEstimator.estimate(summary)));
            log.warn("分层记忆: 会话 {} 换页生成摘要 [{}:{})", sessionId, lastSummarized, end);
            // 归档清理：摘要成功后，把该消息块标记为已归档，后续 readContext 只加载未归档段
            markBlockArchived(scope, sessionId, block);
        }
    }

    /** 把已提炼/已归档的消息块标记为 archived=1（以 block 首末 msg_index 精确定界，幂等） */
    private void markBlockArchived(AgentScope scope, String sessionId, List<Message> block) {
        if (block == null || block.isEmpty()) {
            return;
        }
        int from = block.get(0).getMsgIndex();
        int to = block.get(block.size() - 1).getMsgIndex() + 1;
        // 非持久化来源（msgIndex=-1，如文件/内存模式）时归档标记由存储实现自行决定（File 实现为 no-op）
        if (from < 0) {
            return;
        }
        if (sessionGateway != null) {
            sessionGateway.markArchived(scope, sessionId, from, to);
            log.warn("分层记忆: 会话 {} 已归档消息 [{},{})", sessionId, from, to);
        }
    }

    @Override
    public void afterSession(Session session, Agent agent) {
        if (!isEnabled()) {
            return;
        }
        // 事实提炼（LLM 调用）异步执行；摘要换页由 afterTurn 负责，此处只提炼事实。
        // 通过 MemorySynthesisDispatcher.produce 投递，快照延迟获取。
        final String sessionId = session.getSessionId();
        final AgentScope scope = session.getScope();
        if (config.isSynthesisAsync()) {
            taskQueue.produce(new MemorySynthesisDispatcher.SynthesisEvent(
                    scope, sessionId, MemorySynthesisDispatcher.Kind.AFTER_SESSION,
                    () -> new ArrayList<>(sessionGateway.loadAllMessages(scope, sessionId)),
                    ctx -> doAfterSession(ctx.scope, ctx.sessionId, ctx.getSnapshot())));
        } else {
            doAfterSession(scope, sessionId, sessionGateway.loadAllMessages(scope, sessionId));
        }
    }

    /** 会话回合后：归档原文（档案 RAG，A1 保留热窗 + B1 游标跟随摘要 + B2 价值约束 + B3 空闲收敛）+ 提取事实并合并去重（同 key 按重要度/信息量择优，版本自增，时间戳保留最新） */
    private void doAfterSession(AgentScope scope, String sessionId, List<Message> all) {
        // B3 空闲判定：距离最后一次会话活动超过 archive-idle-timeout 则视为会话结束，
        // 把剩余热窗整体收敛（safeEnd=全量）；否则会话进行中仅保留最近热窗（safeEnd = 全量 - 热窗）。
        boolean idle = isSessionIdle(all);
        int keepRecent = config.getArchiveKeepRecent() > 0 ? config.getArchiveKeepRecent() : config.getHotWindowSize();
        int safeEnd = idle ? all.size() : Math.max(0, all.size() - keepRecent);

        // 1. 会话原文归档（跨会话档案 RAG 数据源）：只归档滚出热窗的旧段，且游标跟随摘要；归档范围=[lastArchived, safeEnd)
        if (config.isArchiveEnabled() && !all.isEmpty() && safeEnd > 0) {
            archiveMessages(scope, sessionId, all, safeEnd);
            // 只标记已归档旧段，热窗内的最新消息保持 archived=0（供 Hot 工作记忆与前端展示）
            markBlockArchived(scope, sessionId, all.subList(0, safeEnd));
        }
        // 2. 事实提炼 + merge（Phase 1：原子 UPSERT，消除 delete+append RMW 竞态）
        List<MemoryPage> freshFacts = synthesizer.extractFacts(scope, all);
        int saved = 0;
        for (MemoryPage fresh : freshFacts) {
            if (fresh.getImportance() < config.getImportanceThreshold()) {
                continue;
            }
            MemoryPage existing = findFact(scope, fresh.getKey());
            MemoryPage merged = synthesizer.mergeFact(existing, fresh);
            merged.setTokenCount(TokenEstimator.estimate(merged));
            pageStore.upsertFactAtomic(scope, merged);
            saved++;
        }
        log.warn("分层记忆: 会话 {} 提炼结束（idle={}），新增/更新事实 {} 条, 归档边界到 {}", sessionId, idle, saved, safeEnd);
    }

    /** 会话是否已闲置：最后一条带时间戳的消息距今超过 archive-idle-timeout（null/0=不启用） */
    private boolean isSessionIdle(List<Message> all) {
        Duration idleTimeout = config.getArchiveIdleTimeout();
        if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).getTimestamp() > 0) {
                return now - all.get(i).getTimestamp() >= idleTimeout.toMillis();
            }
        }
        return false;
    }

    /**
     * 把会话原文按块归档为 ARCHIVE 页（跨会话档案 RAG 数据源）。
     * <p>
     * A1：只归档 {@code [start, safeEnd)}（safeEnd = 全量 - 保留热窗），最近热窗始终保留未归档；
     * B1：归档起点对齐摘要进度（start = max(lastArchived, lastSummarized)），已摘要旧块由摘要页承接检索、不再重复全文归档；
     * B2：块 token 数低于 archiveMinTokens 时跳过全文归档（低价值块仅保留摘要）。
     */
    private void archiveMessages(AgentScope scope, String sessionId, List<Message> all, int safeEnd) {
        int blockSize = config.getSummaryBlockSize();
        int minTokens = config.getArchiveMinTokens();
        // B1：归档起点统一对齐摘要进度（未摘要的旧段先留待 summarize 换页后再归档，避免重复/空转）
        int start = Math.max(lastArchivedIndex(scope, sessionId), lastSummarizedIndex(scope, sessionId));
        if (start >= safeEnd) {
            return; // 本轮无旧消息需要归档
        }
        int count = 0;
        for (int s = start; s < safeEnd; s += blockSize) {
            int end = Math.min(s + blockSize, safeEnd);
            List<Message> block = new ArrayList<>(all.subList(s, end));
            // B2：价值约束——低价值块只保留摘要，不归档全文
            int blockTokens = TokenEstimator.estimate(block);
            if (minTokens > 0 && blockTokens < minTokens) {
                continue;
            }
            String content = messagesToText(block);
            MemoryPage page = MemoryPage.archive(
                    "archive-" + sessionId + "-" + s,
                    content, sessionId, s, end, blockTokens);
            pageStore.saveArchive(scope, page);
            count++;
        }
        if (count > 0) {
            log.warn("分层记忆: 会话 {} 归档 {} 块（历史原文，可跨会话检索）", sessionId, count);
        }
    }

    /** 已归档的消息边界 = 所有 ARCHIVE 页 blockEnd 的最大值 */
    private int lastArchivedIndex(AgentScope scope, String sessionId) {
        return pageStore.loadArchive(scope, sessionId).stream()
                .mapToInt(MemoryPage::getBlockEnd)
                .max().orElse(0);
    }

    private String messagesToText(List<Message> block) {
        StringBuilder sb = new StringBuilder();
        for (Message m : block) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("[").append(m.getRole().getValue()).append("] ")
                    .append(m.getContent() == null ? "" : m.getContent());
        }
        return sb.toString();
    }

    @Override
    public void saveFact(String topic, String content, double importance) {
        if (!isEnabled()) {
            return;
        }
        if (importance < config.getImportanceThreshold()) {
            log.debug("事实重要度 {} 低于阈值 {}，已丢弃: {}",
                    String.format("%.2f", importance), config.getImportanceThreshold(), topic);
            return;
        }
        AgentScope scope = AgentScopeContext.get();
        MemoryPage fresh = MemoryPage.fact(topic, content, importance, null);
        fresh.setTokenCount(TokenEstimator.estimate(fresh));
        MemoryPage existing = findFact(scope, topic);
        MemoryPage merged = synthesizer.mergeFact(existing, fresh);
        pageStore.upsertFactAtomic(scope, merged);
        log.warn("分层记忆: 保存事实 '{}'（重要度 {}）", topic, merged.getImportance());
    }

    @Override
    public String readFactsText() {
        if (!isEnabled()) {
            return "";
        }
        List<MemoryPage> facts = pageStore.loadFacts(AgentScopeContext.get());
        if (facts.isEmpty()) {
            return "(暂无长期记忆)";
        }
        facts.sort(Comparator.comparingDouble(MemoryPage::getImportance).reversed());
        StringBuilder sb = new StringBuilder();
        for (MemoryPage fact : facts) {
            sb.append("- **").append(fact.getKey()).append("**（重要度 ")
                    .append(String.format("%.1f", fact.getImportance()))
                    .append("）：").append(fact.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public List<MemoryPage> search(String query, int topK) {
        if (!isEnabled()) {
            return new ArrayList<>();
        }
        return retriever.search(AgentScopeContext.get(), query, topK);
    }

    // ==================== 私有方法 ====================

    /** 已摘要的消息边界 = 所有摘要页 blockEnd 的最大值 */
    private int lastSummarizedIndex(AgentScope scope, String sessionId) {
        return pageStore.loadSummaries(scope, sessionId).stream()
                .mapToInt(MemoryPage::getBlockEnd)
                .max().orElse(0);
    }

    private MemoryPage findFact(AgentScope scope, String key) {
        return pageStore.loadFacts(scope).stream()
                .filter(f -> key.equals(f.getKey()))
                .findFirst().orElse(null);
    }

    /** 按 token 预算从高到低截取列表 */
    private List<MemoryPage> trimByTokens(List<MemoryPage> pages, int budget) {
        List<MemoryPage> result = new ArrayList<>();
        int used = 0;
        for (MemoryPage page : pages) {
            int tokens = TokenEstimator.estimate(page);
            if (used + tokens > budget) {
                break;
            }
            result.add(page);
            used += tokens;
        }
        return result;
    }

    /**
     * 从最新消息往前取，直到 token 用尽或达到条数上限。
     * <p>
     * 关键保证：
     * 1) tool 消息与其前置的 assistant（携带 tool_calls）消息<b>成组保留或整体跳过</b>，
     *    避免预算截断时裁掉 assistant 却留下孤立的 tool 结果——那会导致 LLM 收到
     *    "role=tool 无前置 tool_calls" 的非法序列（HTTP 400）或工具结果丢失；
     * 2) 最新 user 消息（当前任务的提问）<b>强制保留</b>，避免巨型工具结果把用户问题挤出预算，
     *    否则 LLM 会因看不到用户意图而答非所问或返回空回复；
     * 3) 输出严格保持时间正序（旧 → 新）。
     */
    private List<Message> takeRecentMessages(List<Message> all, int tokenBudget, int maxCount) {
        // 定位最新 user 消息：它是当前任务的提问，必须保留
        int latestUserIdx = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (MessageRole.USER == all.get(i).getRole()) {
                latestUserIdx = i;
                break;
            }
        }

        // 1. 从尾部往前聚合成「组」：tool 消息并入其前置 assistant(tool_calls) 所在组
        List<List<Message>> groups = new ArrayList<>();
        List<Message> pendingTools = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            Message msg = all.get(i);
            if (MessageRole.TOOL == msg.getRole()) {
                pendingTools.add(0, msg);
                continue;
            }
            if (MessageRole.ASSISTANT == msg.getRole()
                    && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                // 仅并入该 assistant 实际声明了 id 的 tool 结果，避免错配
                Set<String> callIds = new HashSet<>();
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getId() != null && !tc.getId().isEmpty()) {
                        callIds.add(tc.getId());
                    }
                }
                List<Message> matched = new ArrayList<>();
                for (Message t : pendingTools) {
                    if (t.getToolCallId() != null && callIds.contains(t.getToolCallId())) {
                        matched.add(t);
                    }
                }
                List<Message> group = new ArrayList<>(1 + matched.size());
                group.add(msg);
                group.addAll(matched);
                groups.add(group);
                pendingTools = new ArrayList<>();
                continue;
            }
            // 普通消息（user / 无 tool_calls 的 assistant）：pending 中无法配对的 tool 视为孤儿丢弃
            pendingTools = new ArrayList<>();
            groups.add(Collections.singletonList(msg));
        }

        // 2. 组装：最新 user 消息所在组强制保留（它是当前任务的提问），
        //    其余组从最新往前按预算/条数选中
        boolean[] selected = new boolean[groups.size()];
        int selectedCount = 0;
        int used = 0;
        int userGroupIdx = -1;
        for (int i = 0; i < groups.size(); i++) {
            if (latestUserIdx >= 0 && groups.get(i).size() == 1
                    && groups.get(i).get(0) == all.get(latestUserIdx)) {
                userGroupIdx = i;
                break;
            }
        }
        if (userGroupIdx >= 0) {
            selected[userGroupIdx] = true;
            selectedCount += groups.get(userGroupIdx).size();
            used += sumTokens(groups.get(userGroupIdx));
        }
        for (int i = 0; i < groups.size(); i++) {
            if (selected[i]) {
                continue;
            }
            int tokens = sumTokens(groups.get(i));
            if (used + tokens > tokenBudget && selectedCount > 0) {
                break;
            }
            if (selectedCount + groups.get(i).size() > maxCount) {
                break;
            }
            selected[i] = true;
            selectedCount += groups.get(i).size();
            used += tokens;
        }

        // 3. 按时间正序输出（groups[0] 最新 → groups[last] 最旧；组内保持 assistant → tool 顺序）
        List<Message> result = new ArrayList<>();
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (selected[i]) {
                result.addAll(groups.get(i));
            }
        }
        return result;
    }

    private int sumTokens(List<Message> group) {
        int tokens = 0;
        for (Message m : group) {
            tokens += TokenEstimator.estimate(m);
        }
        return tokens;
    }

    /** 最新 user 消息内容（用作共享检索查询词） */
    private String latestUserText(List<Message> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            if (MessageRole.USER == all.get(i).getRole()) {
                return all.get(i).getContent();
            }
        }
        return null;
    }
}
