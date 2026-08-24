package com.mwb.ai.claw.infrastructure.rag.write;

import java.util.Locale;

import com.mwb.ai.claw.domain.rag.model.ParsedDocument;
import com.mwb.ai.claw.domain.rag.model.RagDocumentSource;
import com.mwb.ai.claw.domain.rag.write.RagDocumentParser;

/**
 * 多格式组合解析器（默认 RAG 解析器）：按内容类型 / 文件扩展名分发到对应实现。
 * <p>
 * - 文本 / Markdown → 文本解析（内置）；
 * - PDF → PDF 解析（需引入 Apache PDFBox，未引入时给出明确提示）；
 * - Word（.docx）→ Word 解析（需引入 Apache POI，未引入时给出明确提示）。
 * <p>
 * 传入 {@code pdfParser} / {@code wordParser} 为 null 表示对应库未引入，
 * 此时组合解析器退化为纯文本解析，与旧行为完全一致。
 */
public class MultiFormatRagDocumentParser implements RagDocumentParser {

    private final RagDocumentParser textParser;
    private final RagDocumentParser pdfParser;
    private final RagDocumentParser wordParser;

    public MultiFormatRagDocumentParser(RagDocumentParser textParser,
                                        RagDocumentParser pdfParser,
                                        RagDocumentParser wordParser) {
        this.textParser = textParser;
        this.pdfParser = pdfParser;
        this.wordParser = wordParser;
    }

    @Override
    public ParsedDocument parse(RagDocumentSource source) {
        if (source == null) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        if (isPdf(source)) {
            if (pdfParser == null) {
                throw new IllegalArgumentException(
                        "PDF 解析需要引入 Apache PDFBox 依赖: org.apache.pdfbox:pdfbox");
            }
            return pdfParser.parse(source);
        }
        if (isWord(source)) {
            if (wordParser == null) {
                throw new IllegalArgumentException(
                        "Word(.docx) 解析需要引入 Apache POI 依赖: org.apache.poi:poi-ooxml");
            }
            return wordParser.parse(source);
        }
        return textParser.parse(source);
    }

    private boolean isPdf(RagDocumentSource source) {
        String type = normalizedContentType(source);
        String name = source.getName() == null ? "" : source.getName().toLowerCase(Locale.ROOT);
        return "application/pdf".equals(type) || "application/x-pdf".equals(type)
                || name.endsWith(".pdf");
    }

    private boolean isWord(RagDocumentSource source) {
        String type = normalizedContentType(source);
        String name = source.getName() == null ? "" : source.getName().toLowerCase(Locale.ROOT);
        return "application/msword".equals(type)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(type)
                || name.endsWith(".docx");
    }

    private String normalizedContentType(RagDocumentSource source) {
        String type = source.getContentType() == null
                ? "" : source.getContentType().trim().toLowerCase(Locale.ROOT);
        int parameterStart = type.indexOf(';');
        return parameterStart < 0 ? type : type.substring(0, parameterStart).trim();
    }
}
