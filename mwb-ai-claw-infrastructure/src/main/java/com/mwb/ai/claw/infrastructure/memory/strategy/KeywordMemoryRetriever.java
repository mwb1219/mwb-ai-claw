package com.mwb.ai.claw.infrastructure.memory.strategy;

import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.domain.memory.MemoryRetriever;
import com.mwb.ai.claw.domain.scope.AgentScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 关键词记忆检索器（Phase 1）：对事实 + 摘要 + 档案按查询词命中打分召回。
 * <p>
 * 简化 BM25：分词（英文按空格 / 中文按字符 bigram）→ 命中计数加权（标题/内容匹配加分）。
 * 候选范围跨会话（多 Agent 共享）：facts.jsonl + 全部会话摘要 + 全部会话归档。
 */
@Component
public class KeywordMemoryRetriever implements MemoryRetriever {

    private static final Logger log = LoggerFactory.getLogger(KeywordMemoryRetriever.class);

    private final MemoryPageStore pageStore;

    public KeywordMemoryRetriever(MemoryPageStore pageStore) {
        this.pageStore = pageStore;
    }

    @Override
    public List<MemoryPage> search(AgentScope scope, String query, int topK) {
        if (query == null || query.trim().isEmpty() || topK <= 0) {
            return new ArrayList<>();
        }
        Set<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return new ArrayList<>();
        }

        List<MemoryPage> candidates = new ArrayList<>();
        candidates.addAll(pageStore.loadFacts(scope));
        candidates.addAll(pageStore.listAllSummaries(scope));
        candidates.addAll(pageStore.listAllArchive(scope));

        List<ScoredPage> scored = new ArrayList<>();
        for (MemoryPage page : candidates) {
            int score = score(page, terms);
            if (score > 0) {
                scored.add(new ScoredPage(page, score));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredPage::getScore).reversed());

        List<MemoryPage> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            result.add(scored.get(i).page);
        }
        log.debug("记忆检索 '{}' 命中 {} 条", query, result.size());
        return result;
    }

    private int score(MemoryPage page, Set<String> terms) {
        String text = (page.getKey() == null ? "" : page.getKey() + " ")
                + page.getContent();
        if (text.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String term : terms) {
            int idx = text.indexOf(term);
            if (idx >= 0) {
                score += 2;
                // key 命中加权
                if (page.getKey() != null && page.getKey().contains(term)) {
                    score += 2;
                }
                // 多次命中累加
                int from = idx + term.length();
                while ((idx = text.indexOf(term, from)) >= 0) {
                    score += 1;
                    from = idx + term.length();
                }
            }
        }
        return score;
    }

    /** 分词：英文按空白/标点切分，中文按字符 bigram（连续两个字符为词） */
    private Set<String> tokenize(String query) {
        Set<String> terms = new HashSet<>();
        for (String part : query.toLowerCase().split("[\\s\\p{Punct}，。！？、；：（）【】「」]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.matches("[\\u4e00-\\u9fff]+")) {
                // 中文：字符 bigram + 整体
                terms.add(part);
                for (int i = 0; i + 2 <= part.length(); i++) {
                    terms.add(part.substring(i, i + 2));
                }
            } else {
                terms.add(part);
            }
        }
        return terms;
    }

    private static class ScoredPage {
        final MemoryPage page;
        final int score;

        ScoredPage(MemoryPage page, int score) {
            this.page = page;
            this.score = score;
        }

        int getScore() {
            return score;
        }
    }
}
