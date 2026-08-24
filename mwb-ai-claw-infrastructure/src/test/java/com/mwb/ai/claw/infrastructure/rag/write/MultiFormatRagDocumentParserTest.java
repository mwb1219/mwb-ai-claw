package com.mwb.ai.claw.infrastructure.rag.write;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

import com.mwb.ai.claw.domain.rag.model.ParsedDocument;
import com.mwb.ai.claw.domain.rag.model.RagDocumentSource;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * 多格式解析器测试：按内容类型 / 扩展名分发到文本 / PDF / Word 解析，并验证缺库提示。
 */
public class MultiFormatRagDocumentParserTest {

    private final MultiFormatRagDocumentParser full = new MultiFormatRagDocumentParser(
            new TextRagDocumentParser(), new PdfRagDocumentParser(), new WordRagDocumentParser());

    @Test
    public void parsesPlainTextByDefault() {
        ParsedDocument parsed = full.parse(source("note.txt", "text/plain", "plain content"));
        assertEquals(1, parsed.getSections().size());
        assertTrue(parsed.getSections().get(0).getContent().contains("plain content"));
    }

    @Test
    public void parsesMarkdownByContentType() {
        ParsedDocument parsed = full.parse(source("guide.md", "text/markdown", "# 标题\n正文"));
        assertEquals("标题", parsed.getSections().get(0).getTitlePath());
    }

    @Test
    public void parsesPdfByContentType() throws Exception {
        ParsedDocument parsed = full.parse(source("doc.pdf", "application/pdf", null, createPdf("Hello PDF world")));
        assertTrue(parsed.getSections().get(0).getContent().contains("Hello PDF world"));
    }

    @Test
    public void parsesPdfByExtension() throws Exception {
        ParsedDocument parsed = full.parse(source("doc.pdf", null, null, createPdf("Hello by extension")));
        assertTrue(parsed.getSections().get(0).getContent().contains("Hello by extension"));
    }

    @Test
    public void parsesWordByContentType() throws Exception {
        ParsedDocument parsed = full.parse(source("doc.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                null, createDocx("Hello Word world")));
        assertTrue(parsed.getSections().get(0).getContent().contains("Hello Word world"));
    }

    @Test
    public void pdfWithoutLibGivesClearError() {
        MultiFormatRagDocumentParser noPdf = new MultiFormatRagDocumentParser(
                new TextRagDocumentParser(), null, new WordRagDocumentParser());
        try {
            noPdf.parse(source("doc.pdf", "application/pdf", null, new byte[] {1}));
            fail("应抛出缺少 PDFBox 依赖提示");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("PDFBox"));
        }
    }

    @Test
    public void wordWithoutLibGivesClearError() {
        MultiFormatRagDocumentParser noWord = new MultiFormatRagDocumentParser(
                new TextRagDocumentParser(), new PdfRagDocumentParser(), null);
        try {
            noWord.parse(source("doc.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    null, new byte[] {1}));
            fail("应抛出缺少 POI 依赖提示");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("POI"));
        }
    }

    private RagDocumentSource source(String name, String contentType, String content) {
        return source(name, contentType, content, null);
    }

    private RagDocumentSource source(String name, String contentType, String content, byte[] bytes) {
        RagDocumentSource source = new RagDocumentSource();
        source.setName(name);
        source.setContentType(contentType);
        source.setContent(content);
        source.setContentBytes(bytes);
        return source;
    }

    private byte[] createPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(100, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] createDocx(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText(text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }
}
