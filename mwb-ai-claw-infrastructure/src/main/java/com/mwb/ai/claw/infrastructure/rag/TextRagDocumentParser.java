package com.mwb.ai.claw.infrastructure.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mwb.ai.claw.domain.rag.ParsedDocument;
import com.mwb.ai.claw.domain.rag.RagDocumentParser;
import com.mwb.ai.claw.domain.rag.RagDocumentSource;

/**
 * 纯文本与 Markdown 文档解析器。
 */
public class TextRagDocumentParser implements RagDocumentParser {

    @Override
    public ParsedDocument parse(RagDocumentSource source) {
        if (source == null || source.getContent() == null || source.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        if (isMarkdown(source)) {
            return parseMarkdown(source.getContent());
        }
        if (isPlainText(source)) {
            ParsedDocument parsed = new ParsedDocument();
            parsed.getSections().add(new ParsedDocument.Section(null, normalize(source.getContent()).trim()));
            return parsed;
        }
        throw new IllegalArgumentException("默认 RAG 解析器仅支持纯文本和 Markdown");
    }

    private boolean isMarkdown(RagDocumentSource source) {
        String type = normalizedContentType(source);
        String name = source.getName() == null ? "" : source.getName().toLowerCase(Locale.ROOT);
        return type.contains("markdown") || name.endsWith(".md") || name.endsWith(".markdown");
    }

    private boolean isPlainText(RagDocumentSource source) {
        String type = normalizedContentType(source);
        return type.isEmpty() || "text/plain".equals(type);
    }

    private String normalizedContentType(RagDocumentSource source) {
        String type = source.getContentType() == null
                ? "" : source.getContentType().trim().toLowerCase(Locale.ROOT);
        int parameterStart = type.indexOf(';');
        return parameterStart < 0 ? type : type.substring(0, parameterStart).trim();
    }

    private ParsedDocument parseMarkdown(String content) {
        ParsedDocument parsed = new ParsedDocument();
        String[] headings = new String[6];
        String currentTitle = null;
        StringBuilder body = new StringBuilder();
        for (String line : normalize(content).split("\n", -1)) {
            int level = headingLevel(line);
            if (level > 0) {
                flush(parsed, currentTitle, body);
                String title = line.substring(level).trim();
                headings[level - 1] = title;
                for (int i = level; i < headings.length; i++) {
                    headings[i] = null;
                }
                currentTitle = titlePath(headings);
            } else {
                body.append(line).append('\n');
            }
        }
        flush(parsed, currentTitle, body);
        if (parsed.getSections().isEmpty()) {
            parsed.getSections().add(new ParsedDocument.Section(null, normalize(content).trim()));
        }
        return parsed;
    }

    private void flush(ParsedDocument parsed, String title, StringBuilder body) {
        String text = body.toString().trim();
        if (!text.isEmpty()) {
            parsed.getSections().add(new ParsedDocument.Section(title, text));
        }
        body.setLength(0);
    }

    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && level < 6 && line.charAt(level) == '#') {
            level++;
        }
        return level > 0 && level < line.length() && Character.isWhitespace(line.charAt(level)) ? level : 0;
    }

    private String titlePath(String[] headings) {
        List<String> path = new ArrayList<>();
        for (String heading : headings) {
            if (heading != null && !heading.isEmpty()) {
                path.add(heading);
            }
        }
        return String.join(" / ", path);
    }

    private String normalize(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }
}
