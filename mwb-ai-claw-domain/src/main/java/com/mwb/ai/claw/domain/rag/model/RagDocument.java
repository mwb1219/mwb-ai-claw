package com.mwb.ai.claw.domain.rag;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/**
 * RAG 原始文档及其索引状态。
 */
@Data
public class RagDocument {

    public enum Status {
        /** 索引构建中。 */
        PROCESSING,
        /** 索引就绪。 */
        READY,
        /** 处理失败。 */
        FAILED
    }

    /** 文档唯一 ID。 */
    private String documentId;
    /** 所属知识库 ID。 */
    private String knowledgeBaseId;
    /** 文档显示名称。 */
    private String name;
    /** 文档 MIME 类型。 */
    private String contentType;
    /** 内容校验和，用于跳过未变化文档。 */
    private String checksum;
    /** 文档版本号。 */
    private long version;
    /** 分块数量。 */
    private int chunkCount;
    /** 文档索引状态。 */
    private Status status;
    /** 解析后的原始全文，用于重建索引。 */
    private String sourceContent;
    /** 最近一次处理失败的异常信息。 */
    private String lastError;
    /** 附加元数据。 */
    private Map<String, String> metadata = new LinkedHashMap<>();
    /** 创建时间戳。 */
    private long createTime;
    /** 最后更新时间戳。 */
    private long updateTime;
}
