package com.mwb.ai.claw.infrastructure.rag.write;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import com.mwb.ai.claw.domain.rag.model.ParsedDocument;
import com.mwb.ai.claw.domain.rag.model.RagDocumentSource;
import com.mwb.ai.claw.domain.rag.write.RagDocumentParser;

/**
 * Word 文档解析器（.docx，基于 Apache POI，需引入 {@code org.apache.poi:poi-ooxml}）。
 * <p>
 * 按文档元素顺序提取段落与表格（单元格以 {@code |} 连接），不支持旧版 .doc 二进制格式；
 * 解析结果按单一正文区返回，后续由 {@code RagChunker} 切分。
 */
public class WordRagDocumentParser implements RagDocumentParser {

    @Override
    public ParsedDocument parse(RagDocumentSource source) {
        byte[] bytes = source.getContentBytes();
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Word 解析需要二进制内容: " + source.getName());
        }
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    appendParagraph(text, (XWPFParagraph) element);
                } else if (element instanceof XWPFTable) {
                    appendTable(text, (XWPFTable) element);
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Word 解析失败: " + source.getName(), e);
        }
        if (text.length() == 0) {
            throw new IllegalArgumentException("Word 未提取到文本内容: " + source.getName());
        }
        ParsedDocument parsed = new ParsedDocument();
        parsed.getSections().add(new ParsedDocument.Section(null, text.toString().trim()));
        return parsed;
    }

    private void appendParagraph(StringBuilder text, XWPFParagraph paragraph) {
        String value = paragraph.getText();
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        text.append(value.trim()).append('\n');
    }

    private void appendTable(StringBuilder text, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cellText(cell));
            }
            if (!cells.isEmpty()) {
                text.append(String.join(" | ", cells)).append('\n');
            }
        }
    }

    private String cellText(XWPFTableCell cell) {
        StringBuilder builder = new StringBuilder();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            for (XWPFRun run : paragraph.getRuns()) {
                String value = run.getText(0);
                if (value != null && !value.trim().isEmpty()) {
                    builder.append(value);
                }
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
        }
        return builder.toString().trim();
    }
}
