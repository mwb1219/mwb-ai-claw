package com.mwb.ai.claw.infrastructure.rag.write;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import com.mwb.ai.claw.domain.rag.model.ParsedDocument;
import com.mwb.ai.claw.domain.rag.model.RagDocumentSource;
import com.mwb.ai.claw.domain.rag.write.RagDocumentParser;

/**
 * PDF 文档解析器（基于 Apache PDFBox，需引入 {@code org.apache.pdfbox:pdfbox}）。
 * <p>
 * 解析结果按单一正文区返回，后续由 {@code RagChunker} 按长度 / 自然边界切分；
 * 多栏 / 扫描件 PDF 不做版面还原（扫描件需 OCR，属后续演进）。
 */
public class PdfRagDocumentParser implements RagDocumentParser {

    @Override
    public ParsedDocument parse(RagDocumentSource source) {
        byte[] bytes = source.getContentBytes();
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("PDF 解析需要二进制内容: " + source.getName());
        }
        try (PDDocument document = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalArgumentException("PDF 未提取到文本内容: " + source.getName());
            }
            ParsedDocument parsed = new ParsedDocument();
            parsed.getSections().add(new ParsedDocument.Section(null, text.trim()));
            return parsed;
        } catch (IOException e) {
            throw new IllegalArgumentException("PDF 解析失败: " + source.getName(), e);
        }
    }
}
