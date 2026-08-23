package com.mwb.ai.claw.infrastructure.rag;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.model.ParsedDocument;
import com.mwb.ai.claw.domain.rag.model.RagChunk;
import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.model.RagDocumentSource;
import com.mwb.ai.claw.infrastructure.rag.write.TextRagChunker;
import com.mwb.ai.claw.infrastructure.rag.write.TextRagDocumentParser;

/**
 * 默认文本解析与切分策略测试。
 */
public class TextRagDocumentParserTest {

    @Test
    public void markdownKeepsHeadingPathAndChunkMetadata() {
        RagDocumentSource source = new RagDocumentSource();
        source.setName("guide.md");
        source.setContentType("text/markdown");
        source.setContent("# Product\nOverview\n\n## Install\nFirst step. Second step.");

        ParsedDocument parsed = new TextRagDocumentParser().parse(source);
        assertEquals(2, parsed.getSections().size());
        assertEquals("Product", parsed.getSections().get(0).getTitlePath());
        assertEquals("Product / Install", parsed.getSections().get(1).getTitlePath());

        RagConfig config = new RagConfig();
        config.getIngestion().setChunkSize(20);
        config.getIngestion().setChunkOverlap(4);
        RagDocument document = new RagDocument();
        document.setKnowledgeBaseId("kb");
        document.setDocumentId("guide");
        document.setName("guide.md");
        document.setContentType("text/markdown");
        document.setVersion(3L);

        List<RagChunk> chunks = new TextRagChunker(config).split(document, parsed);
        assertTrue(chunks.size() >= 2);
        assertEquals("guide-v3-0", chunks.get(0).getChunkId());
        assertEquals("text-v1", chunks.get(0).getMetadata().get("chunkerVersion"));
        assertTrue(chunks.get(chunks.size() - 1).getMetadata().get("titlePath").contains("Install"));
    }

    @Test
    public void unsupportedContentTypeIsRejected() {
        RagDocumentSource source = new RagDocumentSource();
        source.setName("manual.pdf");
        source.setContentType("application/pdf");
        source.setContent("not parsed PDF bytes");

        try {
            new TextRagDocumentParser().parse(source);
            fail("默认解析器不应将 PDF 当作纯文本处理");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("纯文本和 Markdown"));
        }
    }
}
