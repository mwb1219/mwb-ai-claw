package com.mwb.ai.claw.domain.rag.model;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/**
 * 写入 RAG 索引的完整记录。
 */
@Data
public class RagIndexEntry {

    /** 分块唯一标识。 */
    private String chunkId;
    /** 所属知识库 ID。 */
    private String knowledgeBaseId;
    /** 来源文档 ID。 */
    private String documentId;
    /** 来源文档版本。 */
    private long documentVersion;
    /** 块在文档内的顺序号。 */
    private int sequence;
    /** 分块文本内容。 */
    private String content;
    /** 附加元数据。 */
    private Map<String, String> metadata = new LinkedHashMap<>();
    /** 文本向量。 */
    private float[] vector;
    /** 生成该向量的模型标识。 */
    private String embeddingModel;
    /** 向量维度。 */
    private int dimensions;
}
