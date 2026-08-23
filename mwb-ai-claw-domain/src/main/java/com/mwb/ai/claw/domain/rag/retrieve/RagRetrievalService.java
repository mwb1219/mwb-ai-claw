package com.mwb.ai.claw.domain.rag.retrieve;

import java.util.List;

import com.mwb.ai.claw.domain.rag.model.RagQuery;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;

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
