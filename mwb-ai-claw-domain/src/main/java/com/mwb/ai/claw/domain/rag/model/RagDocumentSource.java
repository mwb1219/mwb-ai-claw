package com.mwb.ai.claw.domain.rag.model;

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
    /** 文档原始内容（文本 / Markdown 时使用）。 */
    private String content;
    /** 文档原始二进制内容（PDF / Word 等二进制格式时使用，与 content 二选一）。 */
    private byte[] contentBytes;
}
