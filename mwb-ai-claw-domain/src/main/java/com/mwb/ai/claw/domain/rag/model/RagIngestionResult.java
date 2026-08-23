package com.mwb.ai.claw.domain.rag.model;

import lombok.Data;

/**
 * 文档写入结果。
 */
@Data
public class RagIngestionResult {

    /** 知识库 ID。 */
    private String knowledgeBaseId;
    /** 文档 ID。 */
    private String documentId;
    /** 写入后的文档版本。 */
    private long version;
    /** 分块数量。 */
    private int chunkCount;
    /** 内容未变化而跳过写入时为 true。 */
    private boolean skipped;
    /** 写入后的文档状态。 */
    private RagDocument.Status status;
}
