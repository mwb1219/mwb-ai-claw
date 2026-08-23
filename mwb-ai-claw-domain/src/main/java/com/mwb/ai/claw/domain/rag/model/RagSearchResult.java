package com.mwb.ai.claw.domain.rag;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/**
 * RAG 检索命中及其引用信息。
 */
@Data
public class RagSearchResult {

    /** 命中知识库 ID。 */
    private String knowledgeBaseId;
    /** 来源文档 ID。 */
    private String documentId;
    /** 来源文档版本。 */
    private long documentVersion;
    /** 命中分块 ID。 */
    private String chunkId;
    /** 命中分块在文档内的顺序号。 */
    private int sequence;
    /** 命中分块文本内容。 */
    private String content;
    /** 相关度分数。 */
    private double score;
    /** 附加元数据。 */
    private Map<String, String> metadata = new LinkedHashMap<>();
}
