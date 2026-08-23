package com.mwb.ai.claw.domain.rag;

import java.util.List;

/**
 * 独立 RAG 检索能力。
 */
public interface RagRetrievalService {

    /**
     * 执行独立 RAG 检索。
     *
     * @param query 检索请求
     * @return 按相关度降序排列的命中结果
     */
    List<RagSearchResult> retrieve(RagQuery query);
}
