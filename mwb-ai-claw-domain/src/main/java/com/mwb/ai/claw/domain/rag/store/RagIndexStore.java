package com.mwb.ai.claw.domain.rag.store;

import java.util.List;

import com.mwb.ai.claw.domain.rag.model.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.model.RagVectorQuery;

/**
 * RAG 索引存储 SPI。
 */
public interface RagIndexStore {

    /**
     * 原子替换本批记录所属文档的全部索引块。
     */
    void upsert(List<RagIndexEntry> entries);

    /**
     * 删除指定文档的全部索引块。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     */
    void deleteByDocument(String knowledgeBaseId, String documentId);

    /**
     * 按向量相似度检索。
     *
     * @param query 向量查询
     * @return 按相似度降序排列的命中结果
     */
    List<RagSearchResult> search(RagVectorQuery query);
}
