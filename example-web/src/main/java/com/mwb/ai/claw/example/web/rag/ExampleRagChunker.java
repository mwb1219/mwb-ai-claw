package com.mwb.ai.claw.example.web.rag;

import java.util.List;

import com.mwb.ai.claw.domain.rag.ParsedDocument;
import com.mwb.ai.claw.domain.rag.RagChunk;
import com.mwb.ai.claw.domain.rag.RagChunker;
import com.mwb.ai.claw.domain.rag.RagDocument;

/**
 * 示例切分器（装饰模式）：委托默认 {@link TextRagChunker} 完成切分，
 * 并在每个分块的元数据追加自定义扩展标记，演示「替换扩展点」。
 *
 * @author Frank Zhang
 */
public class ExampleRagChunker implements RagChunker {

    private final RagChunker delegate;

    public ExampleRagChunker(RagChunker delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<RagChunk> split(RagDocument document, ParsedDocument parsedDocument) {
        List<RagChunk> chunks = delegate.split(document, parsedDocument);
        for (RagChunk chunk : chunks) {
            chunk.getMetadata().put("extension", "example-web-custom-chunker");
        }
        return chunks;
    }
}
