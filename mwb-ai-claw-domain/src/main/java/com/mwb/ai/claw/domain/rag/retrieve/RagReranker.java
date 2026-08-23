package com.mwb.ai.claw.domain.rag;

import java.util.List;

/**
 * 可选的 RAG 结果重排 SPI。
 */
public interface RagReranker {

    /**
     * 对候选结果进行重排并截取前 topK 条。
     *
     * @param query      查询文本
     * @param candidates 候选结果
     * @param topK       返回条数上限
     * @return 重排后的结果
     */
    List<RagSearchResult> rerank(String query, List<RagSearchResult> candidates, int topK);
}
