package com.mwb.ai.claw.infrastructure.rag.write;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.model.ParsedDocument;
import com.mwb.ai.claw.domain.rag.model.RagChunk;
import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.write.RagChunker;

/**
 * 按 Markdown 章节、自然边界和字符上限切分文本。
 */
public class TextRagChunker implements RagChunker {

    private final RagConfig.IngestionConfig config;

    public TextRagChunker(RagConfig config) {
        this.config = config.getIngestion();
    }

    @Override
    public List<RagChunk> split(RagDocument document, ParsedDocument parsedDocument) {
        if (document == null || parsedDocument == null) {
            throw new IllegalArgumentException("文档及解析结果不能为空");
        }
        int chunkSize = config.getChunkSize();
        int overlap = config.getChunkOverlap();
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("agent.rag.ingestion.chunk-size 必须大于 0");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("agent.rag.ingestion.chunk-overlap 必须在 [0, chunk-size) 范围内");
        }

        List<RagChunk> result = new ArrayList<>();
        int sequence = 0;
        for (ParsedDocument.Section section : parsedDocument.getSections()) {
            if (section == null || section.getContent() == null || section.getContent().trim().isEmpty()) {
                continue;
            }
            for (String piece : splitText(section.getContent().trim(), chunkSize, overlap)) {
                RagChunk chunk = new RagChunk();
                chunk.setKnowledgeBaseId(document.getKnowledgeBaseId());
                chunk.setDocumentId(document.getDocumentId());
                chunk.setDocumentVersion(document.getVersion());
                chunk.setSequence(sequence);
                chunk.setChunkId(document.getDocumentId() + "-v" + document.getVersion() + "-" + sequence);
                chunk.setContent(withTitle(section.getTitlePath(), piece));
                Map<String, String> metadata = new LinkedHashMap<>(document.getMetadata());
                metadata.put("documentName", value(document.getName()));
                metadata.put("contentType", value(document.getContentType()));
                metadata.put("sequence", String.valueOf(sequence));
                metadata.put("chunkerVersion", "text-v1");
                if (section.getTitlePath() != null && !section.getTitlePath().isEmpty()) {
                    metadata.put("titlePath", section.getTitlePath());
                }
                chunk.setMetadata(metadata);
                result.add(chunk);
                sequence++;
            }
        }
        return result;
    }

    private List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int proposedEnd = Math.min(text.length(), start + chunkSize);
            int end = proposedEnd;
            if (proposedEnd < text.length()) {
                end = naturalBreak(text, start, proposedEnd);
            }
            if (end <= start) {
                end = proposedEnd;
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= text.length()) {
                break;
            }
            int next = Math.max(start + 1, end - overlap);
            while (next < end && Character.isWhitespace(text.charAt(next))) {
                next++;
            }
            start = next;
        }
        return pieces;
    }

    private int naturalBreak(String text, int start, int proposedEnd) {
        int lowerBound = start + Math.max(1, (proposedEnd - start) / 2);
        for (int i = proposedEnd - 1; i >= lowerBound; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？'
                    || c == '.' || c == '!' || c == '?' || c == ';' || c == '；') {
                return i + 1;
            }
        }
        return proposedEnd;
    }

    private String withTitle(String titlePath, String content) {
        return titlePath == null || titlePath.isEmpty() ? content : titlePath + "\n" + content;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
