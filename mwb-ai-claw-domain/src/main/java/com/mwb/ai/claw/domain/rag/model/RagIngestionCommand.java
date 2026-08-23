package com.mwb.ai.claw.domain.rag.model;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/**
 * 文档写入命令。第一阶段接收已读取的文本或 Markdown 内容。
 */
@Data
public class RagIngestionCommand {

    /** 目标知识库 ID。 */
    private String knowledgeBaseId;
    /** 文档 ID；为空时由实现生成。 */
    private String documentId;
    /** 文档名称。 */
    private String name;
    /** 文档 MIME 类型，默认纯文本。 */
    private String contentType = "text/plain";
    /** 文档正文内容。 */
    private String content;
    /** 附加元数据。 */
    private Map<String, String> metadata = new LinkedHashMap<>();
}
