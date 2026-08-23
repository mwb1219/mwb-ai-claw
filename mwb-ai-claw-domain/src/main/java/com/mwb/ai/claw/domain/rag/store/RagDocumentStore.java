package com.mwb.ai.claw.domain.rag.store;

import java.util.List;

import com.mwb.ai.claw.domain.rag.model.RagDocument;

/**
 * RAG 原始文档及状态存储 SPI。
 */
public interface RagDocumentStore {

    /**
     * 按知识库与文档 ID 查询文档。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     * @return 文档；不存在时返回 {@code null}
     */
    RagDocument find(String knowledgeBaseId, String documentId);

    /** 保存或更新文档及状态。 */
    void save(RagDocument document);

    /** 删除文档及其状态。 */
    void delete(String knowledgeBaseId, String documentId);

    /** 列出指定知识库下的全部文档。 */
    List<RagDocument> list(String knowledgeBaseId);
}
