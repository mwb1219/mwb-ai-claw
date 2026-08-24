package com.mwb.ai.claw.example.commerce.rag;

import java.util.List;

import com.mwb.ai.claw.domain.rag.model.ParsedDocument;
import com.mwb.ai.claw.domain.rag.model.RagChunk;
import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.write.RagChunker;

/**
 * 电商切分器（装饰模式）：委托默认 {@link com.mwb.ai.claw.infrastructure.rag.write.TextRagChunker}
 * 完成切分，并在每个分块的元数据追加「电商业务扩展标记」。
 * 演示「替换/增强扩展点」：框架以 {@code @ConditionalOnMissingBean} 保证自定义 Bean 覆盖默认实现。
 */
public class CommerceRagChunker implements RagChunker {

    private final RagChunker delegate;

    public CommerceRagChunker(RagChunker delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<RagChunk> split(RagDocument document, ParsedDocument parsedDocument) {
        List<RagChunk> chunks = delegate.split(document, parsedDocument);
        for (RagChunk chunk : chunks) {
            chunk.getMetadata().put("extension", "commerce-custom-chunker");
            // 业务侧可根据来源标记知识库场景（如 product / campaign 手册）
            if (document.getKnowledgeBaseId() != null) {
                chunk.getMetadata().put("knowledgeBaseId", document.getKnowledgeBaseId());
            }
        }
        return chunks;
    }
}