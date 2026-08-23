package com.mwb.ai.claw.domain.rag;

/**
 * RAG 知识写入能力。
 */
public interface RagIngestionService {

    /**
     * 写入文档：解析、切分、向量化并更新索引。
     *
     * @param command 写入命令
     * @return 写入结果
     */
    RagIngestionResult ingest(RagIngestionCommand command);

    /**
     * 删除文档及其索引。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     */
    void deleteDocument(String knowledgeBaseId, String documentId);

    /**
     * 基于已保存的原始内容重建文档索引。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     * @return 重建后的写入结果
     */
    RagIngestionResult reindex(String knowledgeBaseId, String documentId);
}
