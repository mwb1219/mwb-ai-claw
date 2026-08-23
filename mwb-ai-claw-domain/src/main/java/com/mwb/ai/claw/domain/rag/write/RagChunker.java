package com.mwb.ai.claw.domain.rag.write;

import java.util.List;

import com.mwb.ai.claw.domain.rag.model.ParsedDocument;
import com.mwb.ai.claw.domain.rag.model.RagChunk;
import com.mwb.ai.claw.domain.rag.model.RagDocument;

/**
 * RAG 文本切分 SPI。
 */
public interface RagChunker {

    /**
     * 将解析后的文档切分为多个文本块。
     *
     * @param document       文档元信息
     * @param parsedDocument 解析后的结构化文档
     * @return 切分后的文本块列表
     */
    List<RagChunk> split(RagDocument document, ParsedDocument parsedDocument);
}
