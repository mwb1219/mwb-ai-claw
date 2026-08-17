package com.mwb.ai.claw.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.cola.dto.SingleResponse;
import com.mwb.ai.claw.domain.memory.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.memory.MemorySynthesisExecutor;
import com.mwb.ai.claw.infrastructure.memory.SynthesisCache;
import com.mwb.ai.claw.infrastructure.util.TokenEstimator;

/**
 * 记忆可视化面板（Phase 4）：只读 REST 接口，展示分层记忆各层内容与统计。
 * <p>
 * - {@code GET /memory}：总览（配置 + 各层统计 + 提炼缓存/队列状态）；
 * - {@code GET /memory/facts}：长期记忆事实列表（重要度降序）；
 * - {@code GET /memory/summaries?sessionId=}：中期摘要页（可按会话过滤，空=全部）；
 * - {@code GET /memory/archive?sessionId=}：档案归档块（可按会话过滤，空=全部）；
 * - {@code GET /memory/search?q=&topK=}：检索召回调试。
 */
@RestController
@RequestMapping("/memory")
@Profile("web")
public class MemoryController {

    @Resource
    private MemoryPageStore pageStore;

    @Resource
    private LayeredMemoryGateway layeredMemoryGateway;

    @Resource
    private AgentProperties agentProperties;

    @Resource
    private SynthesisCache synthesisCache;

    @Resource
    private MemorySynthesisExecutor synthesisExecutor;

    /**
     * 总览：分层记忆配置快照 + 各层统计 + 提炼缓存/队列状态。
     */
    @GetMapping
    public SingleResponse<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        LayeredMemoryConfig cfg = agentProperties.getMemory();
        List<MemoryPage> facts = pageStore.loadFacts();
        List<MemoryPage> summaries = pageStore.listAllSummaries();
        List<MemoryPage> archives = pageStore.listAllArchive();

        result.put("enabled", cfg.isEnabled());
        result.put("config", configSnapshot(cfg));
        result.put("stats", layerStats(facts, summaries, archives));
        result.put("synthesis", Map.of(
                "cache", synthesisCache.stats(),
                "pendingTasks", synthesisExecutor.pendingCount()));
        return SingleResponse.of(result);
    }

    /**
     * 长期记忆事实列表（重要度降序，含版本/时间戳）。
     */
    @GetMapping("/facts")
    public SingleResponse<List<MemoryPage>> facts() {
        List<MemoryPage> facts = pageStore.loadFacts();
        facts.sort(Comparator.comparingDouble(MemoryPage::getImportance).reversed());
        return SingleResponse.of(facts);
    }

    /**
     * 中期摘要页（sessionId 为空则返回全部会话）。
     */
    @GetMapping("/summaries")
    public SingleResponse<List<MemoryPage>> summaries(
            @RequestParam(required = false) String sessionId) {
        List<MemoryPage> pages = (sessionId == null || sessionId.isBlank())
                ? pageStore.listAllSummaries() : pageStore.loadSummaries(sessionId);
        return SingleResponse.of(pages);
    }

    /**
     * 档案归档块（sessionId 为空则返回全部会话）。
     */
    @GetMapping("/archive")
    public SingleResponse<List<MemoryPage>> archive(
            @RequestParam(required = false) String sessionId) {
        List<MemoryPage> pages = (sessionId == null || sessionId.isBlank())
                ? pageStore.listAllArchive() : pageStore.loadArchive(sessionId);
        return SingleResponse.of(pages);
    }

    /**
     * 检索召回调试：按当前检索器（keyword/vector/hybrid）对记忆执行检索。
     */
    @GetMapping("/search")
    public SingleResponse<List<MemoryPage>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int topK) {
        if (!layeredMemoryGateway.isEnabled()) {
            return SingleResponse.of(new ArrayList<>());
        }
        return SingleResponse.of(layeredMemoryGateway.search(q, topK));
    }

    // ==================== 私有方法 ====================

    private Map<String, Object> configSnapshot(LayeredMemoryConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contextWindowTokens", cfg.getContextWindowTokens());
        m.put("contextBudgetRatio", cfg.getContextBudgetRatio());
        m.put("promptBudgetRatio", cfg.getPromptBudgetRatio());
        m.put("toolBudgetRatio", cfg.getToolBudgetRatio());
        m.put("hotWindowSize", cfg.getHotWindowSize());
        m.put("summaryBlockSize", cfg.getSummaryBlockSize());
        m.put("importanceThreshold", cfg.getImportanceThreshold());
        m.put("evictionPolicy", cfg.getEvictionPolicy());
        m.put("synthesisAsync", cfg.isSynthesisAsync());
        m.put("retriever", cfg.getRetriever());
        m.put("topK", cfg.getTopK());
        m.put("vectorEnabled", cfg.isVectorEnabled());
        m.put("embeddingModel", cfg.getEmbeddingModel());
        m.put("archiveEnabled", cfg.isArchiveEnabled());
        m.put("sharedRetrieve", cfg.isSharedRetrieve());
        m.put("synthesizerModel", cfg.getSynthesizerModel());
        m.put("synthesisCacheSize", cfg.getSynthesisCacheSize());
        return m;
    }

    private Map<String, Object> layerStats(List<MemoryPage> facts,
                                           List<MemoryPage> summaries,
                                           List<MemoryPage> archives) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("facts", List.of(count(facts), tokens(facts)));
        m.put("summaries", List.of(count(summaries), tokens(summaries)));
        m.put("archives", List.of(count(archives), tokens(archives)));
        // 按会话聚合（跨会话档案分布）
        Map<String, Integer> bySession = new LinkedHashMap<>();
        for (MemoryPage p : archives) {
            bySession.merge(p.getSessionId() == null ? "(null)" : p.getSessionId(), 1, Integer::sum);
        }
        m.put("archiveBySession", bySession);
        return m;
    }

    private int count(List<MemoryPage> pages) {
        return pages == null ? 0 : pages.size();
    }

    private int tokens(List<MemoryPage> pages) {
        if (pages == null) {
            return 0;
        }
        return pages.stream().mapToInt(TokenEstimator::estimate).sum();
    }
}
