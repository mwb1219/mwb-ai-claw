package com.mwb.ai.claw.domain.rag;

/**
 * RAG 文档解析 SPI。
 */
public interface RagDocumentParser {

    /**
     * 将原始文档内容解析为结构化文档。
     *
     * @param source 文档原始内容
     * @return 解析后的结构化文档
     */
    ParsedDocument parse(RagDocumentSource source);
}
