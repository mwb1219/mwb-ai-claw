package com.mwb.ai.claw.infrastructure.memory.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.MessageRole;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.memory.evict.PageEvictionPolicy;
import com.mwb.ai.claw.domain.memory.gateway.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.model.EvictionContext;
import com.mwb.ai.claw.domain.memory.model.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.model.MemoryBudget;
import com.mwb.ai.claw.domain.memory.model.MemoryPage;
import com.mwb.ai.claw.domain.memory.retrieve.MemoryRetriever;
import com.mwb.ai.claw.domain.memory.store.MemoryPageStore;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesizer;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.memory.strategy.ImportanceEvictionPolicy;
import com.mwb.ai.claw.infrastructure.memory.strategy.TokenBudgetEvictionPolicy;
import com.mwb.ai.claw.infrastructure.memory.synthesis.MemorySynthesisExecutor;
import com.mwb.ai.claw.infrastructure.util.TokenEstimator;

/**
 * 分层记忆门面实现：工作记忆组装（预算内）+ 摘要换页（策略可插拔）+ 事实提炼与合并（异步）+ 检索。
 */
public class LayeredMemoryGatewayImpl implements LayeredMemoryGateway {

    private static final Logger log = LoggerFactory.getLogger(LayeredMemoryGatewayImpl.class);

    private final LayeredMemoryConfig config;
    private final MemoryPageStore pageStore;
    private final MemorySynthesizer synthesizer;
    private final MemoryRetriever retriever;
    private final PageEvictionPolicy evictionPolicy;
    private final MemorySynthesisExecutor synthesisExecutor;

    public LayeredMemoryGatewayImpl(AgentProperties properties,
                                    MemoryPageStore pageStore,
                                    MemorySynthesizer synthesizer,
                                    MemoryRetriever retriever,
                                    MemorySynthesisExecutor synthesisExecutor) {
        this.config = properties.getMemory();
        this.pageStore = pageStore;
        this.synthesizer = synthesizer;
        this.retriever = retriever;
        // 换页策略可插拔：importance | token（默认）
        this.evictionPolicy = "importance".equalsIgnoreCase(config.getEvictionPolicy())
                ? new ImportanceEvictionPolicy() : new TokenBudgetEvictionPolicy();
        this.synthesisExecutor = synthesisExecutor;
        log.warn("分层记忆配置: enabled={}, window={}, budgetRatio={}, hotWindow={}, blockSize={}, threshold={}, topK={}, policy={}, async={}, retriever={}, vector={}, archive={}, sharedRetrieve={}, model={}",
                config.isEnabled(), config.getContextWindowTokens(), config.getContextBudgetRatio(),
                config.getHotWindowSize(), config.getSummaryBlockSize(),
                config.getImportanceThreshold(), config.getTopK(),
                config.getEvictionPolicy(), config.isSynthesisAsync(),
                config.getRetriever(), config.isVectorEnabled(),
                config.isArchiveEnabled(), config.isSharedRetrieve(),
                properties.getModel());
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

        // 3. 工作记忆原文：Memory 区预算扣除摘要后，从最新消息往前取
        int hotTokens = Math.max(budget.getMemoryBudget() - summaryTokens, 0);
        List<Message> hot = takeRecentMessages(all, hotTokens, config.getHotWindowSize());

        // 4. 共享记忆自动检索换入（多 Agent 共享 + 跨会话档案 RAG）：
        //    以最新 user 消息为查询，跨会话检索事实/摘要/档案并注入，占用 Memory 预算的一小部分
        List<MemoryPage> retrieved = new ArrayList<>();
        if (config.isSharedRetrieve()) {
            String query = latestUserText(all);
            if (query != null && !query.trim().isEmpty()) {
                int retrievedBudget = Math.max(256, budget.getMemoryBudget() / 5);
                retrieved = trimByTokens(retriever.search(scope, query.trim(), config.getTopK()), retrievedBudget);
                if (!retrieved.isEmpty()) {
                    log.debug("分层记忆: 共享检索换入 {} 条（查询 '{}'）", retrieved.size(), query.trim());
                }
            }
        }

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
        // 提炼（摘要生成）是 LLM 调用，异步执行不阻塞主对话链路；快照消息避免执行期数据漂移
        // 异步任务不依赖 ThreadLocal，scope 显式透传
        final String sessionId = session.getSessionId();
        final AgentScope scope = session.getScope();
        final List<Message> snapshot = new ArrayList<>(session.getMessages());
        Runnable task = () -> doAfterTurn(scope, sessionId, snapshot);
        if (config.isSynthesisAsync()) {
            synthesisExecutor.submit(scope, "afterTurn-" + sessionId, task);
        } else {
            task.run();
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
        }
    }

    @Override
    public void afterSession(Session session, Agent agent) {
        if (!isEnabled()) {
            return;
        }
        // 事实提炼（LLM 调用）异步执行；摘要换页由 afterTurn 负责，此处只提炼事实
        final String sessionId = session.getSessionId();
        final AgentScope scope = session.getScope();
        final List<Message> snapshot = new ArrayList<>(session.getMessages());
        Runnable task = () -> doAfterSession(scope, sessionId, snapshot);
        if (config.isSynthesisAsync()) {
            synthesisExecutor.submit(scope, "afterSession-" + sessionId, task);
        } else {
            task.run();
        }
    }

    /** 会话回合后：归档原文（档案 RAG）+ 提取事实并合并去重（同 key 按重要度/信息量择优，版本自增，时间戳保留最新） */
    private void doAfterSession(AgentScope scope, String sessionId, List<Message> all) {
        // 1. 会话原文增量归档（跨会话档案 RAG 数据源，多 Agent 共享）
        if (config.isArchiveEnabled() && !all.isEmpty()) {
            archiveMessages(scope, sessionId, all);
        }
        // 2. 事实提炼 + merge
        List<MemoryPage> freshFacts = synthesizer.extractFacts(scope, all);
        int saved = 0;
        for (MemoryPage fresh : freshFacts) {
            if (fresh.getImportance() < config.getImportanceThreshold()) {
                continue;
            }
            MemoryPage existing = findFact(scope, fresh.getKey());
            MemoryPage merged = synthesizer.mergeFact(existing, fresh);
            if (existing != null) {
                pageStore.deleteFact(scope, existing.getKey());
            }
            merged.setTokenCount(TokenEstimator.estimate(merged));
            pageStore.appendFact(scope, merged);
            saved++;
        }
        log.warn("分层记忆: 会话 {} 提炼结束，新增/更新事实 {} 条", sessionId, saved);
    }

    /** 把会话原文按块归档为 ARCHIVE 页（只归档上次之后的新消息，幂等） */
    private void archiveMessages(AgentScope scope, String sessionId, List<Message> all) {
        int blockSize = config.getSummaryBlockSize();
        int archived = lastArchivedIndex(scope, sessionId);
        int count = 0;
        int end;
        for (int start = archived; start < all.size(); start += blockSize) {
            end = Math.min(start + blockSize, all.size());
            List<Message> block = new ArrayList<>(all.subList(start, end));
            String content = messagesToText(block);
            MemoryPage page = MemoryPage.archive(
                    "archive-" + sessionId + "-" + start,
                    content, sessionId, start, end, TokenEstimator.estimate(content));
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
        if (existing != null) {
            pageStore.deleteFact(scope, existing.getKey());
        }
        pageStore.appendFact(scope, merged);
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
