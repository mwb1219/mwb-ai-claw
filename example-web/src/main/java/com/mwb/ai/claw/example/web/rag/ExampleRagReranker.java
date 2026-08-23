package com.mwb.ai.claw.example.web.rag;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.retrieve.RagReranker;

/**
 * 示例重排器：向量检索后在业务侧按分数二次排序截取 topK 并记录日志，
 * 演示「可选增强扩展点」（框架经 ObjectProvider 按需注入，缺省不启用）。
 *
 * @author Frank Zhang
 */
public class ExampleRagReranker implements RagReranker {

    private static final Logger log = LoggerFactory.getLogger(ExampleRagReranker.class);

    @Override
    public List<RagSearchResult> rerank(String query, List<RagSearchResult> candidates, int topK) {
        List<RagSearchResult> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        int limit = Math.max(0, topK);
        List<RagSearchResult> top = sorted.size() > limit ? sorted.subList(0, limit) : sorted;
        log.info("[example-web RAG reranker] query='{}' 候选 {} 条 → 保留 {} 条，最高分 {}",
                query, candidates.size(), top.size(),
                top.isEmpty() ? "-" : String.format("%.4f", top.get(0).getScore()));
        return top;
    }
}
