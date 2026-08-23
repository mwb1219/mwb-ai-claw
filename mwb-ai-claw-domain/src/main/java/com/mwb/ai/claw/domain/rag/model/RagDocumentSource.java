package com.mwb.ai.claw.domain.rag;

import lombok.Data;

/**
 * 提交给 RAG 文档解析器的原始内容。
 */
@Data
public class RagDocumentSource {

    /** 文档名称。 */
    private String name;
    /** 文档 MIME 类型。 */
    private String contentType;
    /** 文档原始内容。 */
    private String content;
}
