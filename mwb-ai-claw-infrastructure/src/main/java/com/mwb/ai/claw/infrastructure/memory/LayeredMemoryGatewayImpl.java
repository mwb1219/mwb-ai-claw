package com.mwb.ai.claw.infrastructure.memory;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryBudget;
import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.domain.memory.MemoryRetriever;
import com.mwb.ai.claw.domain.memory.MemorySynthesizer;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 分层记忆门面实现：工作记忆组装（预算内）+ 摘要换页 + 事实提炼与合并 + 检索。
 */
@Component
public class LayeredMemoryGatewayImpl implements LayeredMemoryGateway {

    private static final Logger log = LoggerFactory.getLogger(LayeredMemoryGatewayImpl.class);

    private final LayeredMemoryConfig config;
    private final MemoryPageStore pageStore;
    private final MemorySynthesizer synthesizer;
    private final MemoryRetriever retriever;

    public LayeredMemoryGatewayImpl(AgentProperties properties,
                                    MemoryPageStore pageStore,
                                    MemorySynthesizer synthesizer,
                                    MemoryRetriever retriever) {
        this.config = properties.getMemory();
        this.pageStore = pageStore;
        this.synthesizer = synthesizer;
        this.retriever = retriever;
        log.info("分层记忆配置: enabled={}, window={}, budgetRatio={}, hotWindow={}, blockSize={}, threshold={}, topK={}, model={}",
                config.isEnabled(), config.getContextWindowTokens(), config.getContextBudgetRatio(),
                config.getHotWindowSize(), config.getSummaryBlockSize(),
                config.getImportanceThreshold(), config.getTopK(), properties.getModel());
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

        // 1. 跨会话事实页（重要度降序，进入 System 区预算）
        List<MemoryPage> facts = pageStore.loadFacts();
        facts.sort(Comparator.comparingDouble(MemoryPage::getImportance).reversed());
        facts = trimByTokens(facts, budget.getSystemBudget());

        // 2. 当前会话摘要页（占用 Memory 区预算）
        List<MemoryPage> summaries = pageStore.loadSummaries(session.getSessionId());
        int summaryTokens = summaries.stream().mapToInt(TokenEstimator::estimate).sum();

        // 3. 工作记忆原文：Memory 区预算扣除摘要后，从最新消息往前取
        int hotTokens = Math.max(budget.getMemoryBudget() - summaryTokens, 0);
        List<Message> hot = takeRecentMessages(all, hotTokens, config.getHotWindowSize());

        view.setWorkingMessages(hot);
        view.setSummaryPages(summaries);
        view.setFactPages(facts);
        view.setRetrievedPages(new ArrayList<>());
        return view;
    }

    @Override
    public void afterTurn(Session session, Agent agent) {
        if (!isEnabled()) {
            return;
        }
        List<Message> all = session.getMessages();
        int blockSize = config.getSummaryBlockSize();
        int lastSummarized = lastSummarizedIndex(session.getSessionId());
        int unSummarized = all.size() - lastSummarized;
        if (unSummarized < blockSize) {
            return;
        }
        int contextBudget = new MemoryBudget(config).getContextBudget();
        int totalTokens = TokenEstimator.estimate(all);
        // 预算溢出 或 未摘要消息过多 → 把最旧的未摘要块压缩为摘要页
        if (totalTokens > contextBudget || unSummarized >= blockSize * 2) {
            int end = Math.min(lastSummarized + blockSize, all.size());
            List<Message> block = new ArrayList<>(all.subList(lastSummarized, end));
            String summary = synthesizer.summarizeBlock(block);
            if (summary != null && !summary.isEmpty()) {
                MemoryPage page = MemoryPage.summary(
                        "summary-" + session.getSessionId() + "-" + lastSummarized,
                        summary, session.getSessionId(), lastSummarized, end,
                        TokenEstimator.estimate(summary));
                pageStore.saveSummary(page);
                log.info("分层记忆: 会话 {} 换页生成摘要 [{}:{})", session.getSessionId(), lastSummarized, end);
            }
        }
    }

    @Override
    public void afterSession(Session session, Agent agent) {
        if (!isEnabled()) {
            return;
        }
        // 1. 提取事实并合并去重（摘要换页由 afterTurn 按预算/条数触发，此处不再补摘要，
        //    避免每轮把未摘要消息清零而架空 afterTurn 的批量换页）
        List<MemoryPage> freshFacts = synthesizer.extractFacts(session.getMessages());
        int saved = 0;
        for (MemoryPage fresh : freshFacts) {
            if (fresh.getImportance() < config.getImportanceThreshold()) {
                continue;
            }
            MemoryPage existing = findFact(fresh.getKey());
            MemoryPage merged = synthesizer.mergeFact(existing, fresh);
            if (existing != null) {
                pageStore.deleteFact(existing.getKey());
            }
            merged.setTokenCount(TokenEstimator.estimate(merged));
            pageStore.appendFact(merged);
            saved++;
        }
        log.info("分层记忆: 会话 {} 提炼结束，新增/更新事实 {} 条", session.getSessionId(), saved);
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
        MemoryPage fresh = MemoryPage.fact(topic, content, importance, null);
        fresh.setTokenCount(TokenEstimator.estimate(fresh));
        MemoryPage existing = findFact(topic);
        MemoryPage merged = synthesizer.mergeFact(existing, fresh);
        if (existing != null) {
            pageStore.deleteFact(existing.getKey());
        }
        pageStore.appendFact(merged);
        log.info("分层记忆: 保存事实 '{}'（重要度 {}）", topic, merged.getImportance());
    }

    @Override
    public String readFactsText() {
        if (!isEnabled()) {
            return "";
        }
        List<MemoryPage> facts = pageStore.loadFacts();
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
        return retriever.search(query, topK);
    }

    // ==================== 私有方法 ====================

    /** 已摘要的消息边界 = 所有摘要页 blockEnd 的最大值 */
    private int lastSummarizedIndex(String sessionId) {
        return pageStore.loadSummaries(sessionId).stream()
                .mapToInt(MemoryPage::getBlockEnd)
                .max().orElse(0);
    }

    private MemoryPage findFact(String key) {
        return pageStore.loadFacts().stream()
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

    /** 从最新消息往前取，直到 token 用尽或达到条数上限 */
    private List<Message> takeRecentMessages(List<Message> all, int tokenBudget, int maxCount) {
        List<Message> result = new ArrayList<>();
        int used = 0;
        for (int i = all.size() - 1; i >= 0 && result.size() < maxCount; i--) {
            Message msg = all.get(i);
            int tokens = TokenEstimator.estimate(msg);
            if (used + tokens > tokenBudget && !result.isEmpty()) {
                break;
            }
            result.add(0, msg);
            used += tokens;
        }
        return result;
    }
}
