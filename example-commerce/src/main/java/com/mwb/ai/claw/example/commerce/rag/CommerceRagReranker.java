package com.mwb.ai.claw.example.commerce.rag;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.retrieve.RagReranker;

/**
 * 电商重排器：在向量检索后按分数二次排序截取 topK，并记录检索诊断日志。
 * 演示「可选增强扩展点」：框架经 {@code ObjectProvider} 按需注入，缺省不启用。
 */
public class CommerceRagReranker implements RagReranker {

    private static final Logger log = LoggerFactory.getLogger(CommerceRagReranker.class);

    @Override
    public List<RagSearchResult> rerank(String query, List<RagSearchResult> candidates, int topK) {
        List<RagSearchResult> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        int limit = Math.max(0, topK);
        List<RagSearchResult> top = sorted.size() > limit ? sorted.subList(0, limit) : sorted;
        log.info("[commerce RAG reranker] query='{}' 候选 {} 条 → 保留 {} 条，最高分 {}",
                query, candidates.size(), top.size(),
                top.isEmpty() ? "-" : String.format("%.4f", top.get(0).getScore()));
        return top;
    }
}