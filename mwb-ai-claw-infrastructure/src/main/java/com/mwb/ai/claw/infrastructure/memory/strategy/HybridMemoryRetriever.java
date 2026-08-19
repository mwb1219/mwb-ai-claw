package com.mwb.ai.claw.infrastructure.memory.strategy;

import com.mwb.ai.claw.domain.memory.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryRetriever;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合记忆检索器（Phase 3）：按配置在关键词 / 向量 / RRF 融合之间切换。
 * <p>
 * - retriever=keyword：仅关键词检索（零依赖，Phase 1 行为）；
 * - retriever=vector：仅向量检索（需 embedding 可用）；
 * - retriever=hybrid（默认）：关键词 + 向量结果按 RRF（Reciprocal Rank Fusion）融合排序。
 * <p>
 * 作为 {@code @Primary} 注入到 {@link com.mwb.ai.claw.domain.memory.LayeredMemoryGateway}，
 * 其余实现保留为独立 Bean 便于测试与组合。
 */
@Component
@Primary
public class HybridMemoryRetriever implements MemoryRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridMemoryRetriever.class);

    /** RRF 融合常数（k 越大，单列表排名对最终分数影响越平滑） */
    private static final int RRF_K = 60;

    private final KeywordMemoryRetriever keywordRetriever;
    private final VectorMemoryRetriever vectorRetriever;
    private final LayeredMemoryConfig config;

    public HybridMemoryRetriever(KeywordMemoryRetriever keywordRetriever,
                                 VectorMemoryRetriever vectorRetriever,
                                 AgentProperties properties) {
        this.keywordRetriever = keywordRetriever;
        this.vectorRetriever = vectorRetriever;
        this.config = properties.getMemory();
    }

    @Override
    public List<MemoryPage> search(AgentScope scope, String query, int topK) {
        String mode = config.getRetriever();
        if ("keyword".equalsIgnoreCase(mode)) {
            return keywordRetriever.search(scope, query, topK);
        }
        if ("vector".equalsIgnoreCase(mode)) {
            return vectorRetriever.search(scope, query, topK);
        }
        // hybrid（默认）：关键词与向量各自扩召回（topK*3），再 RRF 融合
        List<MemoryPage> keywordHits = keywordRetriever.search(scope, query, topK * 3);
        List<MemoryPage> vectorHits = vectorRetriever.search(scope, query, topK * 3);
        return fuse(keywordHits, vectorHits, topK);
    }

    /** RRF 融合：按 pageId 聚合，score = Σ 1/(k + rank)，rank 从 1 开始 */
    private List<MemoryPage> fuse(List<MemoryPage> keywordHits, List<MemoryPage> vectorHits, int topK) {
        Map<String, RrfEntry> entries = new HashMap<>();
        addRanked(keywordHits, entries);
        addRanked(vectorHits, entries);

        List<RrfEntry> all = new ArrayList<>(entries.values());
        all.sort((a, b) -> Double.compare(b.score, a.score));

        List<MemoryPage> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, all.size()); i++) {
            result.add(all.get(i).page);
        }
        if (!keywordHits.isEmpty() || !vectorHits.isEmpty()) {
            log.debug("混合检索融合 {} 条（关键词 {} + 向量 {}）→ 输出 {} 条",
                    all.size(), keywordHits.size(), vectorHits.size(), result.size());
        }
        return result;
    }

    private void addRanked(List<MemoryPage> hits, Map<String, RrfEntry> entries) {
        for (int i = 0; i < hits.size(); i++) {
            MemoryPage page = hits.get(i);
            String pageId = page.getPageId() != null ? page.getPageId() : "p-" + i;
            RrfEntry entry = entries.computeIfAbsent(pageId, k -> new RrfEntry(page));
            entry.score += 1.0 / (RRF_K + (i + 1));
        }
    }

    private static class RrfEntry {
        final MemoryPage page;
        double score;

        RrfEntry(MemoryPage page) {
            this.page = page;
        }
    }
}
