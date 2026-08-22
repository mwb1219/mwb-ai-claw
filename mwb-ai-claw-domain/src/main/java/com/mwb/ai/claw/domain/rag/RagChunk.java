package com.mwb.ai.claw.domain.rag;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/**
 * RAG 文档切分后的文本块。
 */
@Data
public class RagChunk {

    /** 分块唯一标识，通常为文档 ID 加序号。 */
    private String chunkId;
    /** 所属知识库 ID。 */
    private String knowledgeBaseId;
    /** 来源文档 ID。 */
    private String documentId;
    /** 来源文档版本，用于索引失效判断。 */
    private long documentVersion;
    /** 块在文档内的顺序号。 */
    private int sequence;
    /** 分块文本内容。 */
    private String content;
    /** 附加元数据。 */
    private Map<String, String> metadata = new LinkedHashMap<>();
}
