package com.mwb.ai.claw.infrastructure.rag.write;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.embed.RagEmbeddingGateway;
import com.mwb.ai.claw.domain.rag.model.ParsedDocument;
import com.mwb.ai.claw.domain.rag.model.RagChunk;
import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.model.RagDocumentSource;
import com.mwb.ai.claw.domain.rag.model.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.model.RagIngestionCommand;
import com.mwb.ai.claw.domain.rag.model.RagIngestionResult;
import com.mwb.ai.claw.domain.rag.store.RagDocumentStore;
import com.mwb.ai.claw.domain.rag.store.RagIndexStore;
import com.mwb.ai.claw.domain.rag.write.RagChunker;
import com.mwb.ai.claw.domain.rag.write.RagDocumentParser;
import com.mwb.ai.claw.domain.rag.write.RagIngestionService;
import com.mwb.ai.claw.infrastructure.rag.store.RagFileSupport;

/**
 * 默认 RAG 写入服务。
 */
public class DefaultRagIngestionService implements RagIngestionService {

    private final RagDocumentParser parser;
    private final RagChunker chunker;
    private final RagEmbeddingGateway embeddingGateway;
    private final RagIndexStore indexStore;
    private final RagDocumentStore documentStore;
    private final RagConfig.IngestionConfig config;
    private final RagConfig.CapacityConfig capacity;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public DefaultRagIngestionService(RagDocumentParser parser,
                                      RagChunker chunker,
                                      RagEmbeddingGateway embeddingGateway,
                                      RagIndexStore indexStore,
                                      RagDocumentStore documentStore,
                                      RagConfig config) {
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingGateway = embeddingGateway;
        this.indexStore = indexStore;
        this.documentStore = documentStore;
        this.config = config.getIngestion();
        this.capacity = config.getCapacity();
    }

    @Override
    public RagIngestionResult ingest(RagIngestionCommand command) {
        return ingest(command, false);
    }

    @Override
    public void deleteDocument(String knowledgeBaseId, String documentId) {
        validateIds(knowledgeBaseId, documentId);
        synchronized (lock(knowledgeBaseId, documentId)) {
            indexStore.deleteByDocument(knowledgeBaseId, documentId);
            documentStore.delete(knowledgeBaseId, documentId);
        }
    }

    @Override
    public RagIngestionResult reindex(String knowledgeBaseId, String documentId) {
        validateIds(knowledgeBaseId, documentId);
        synchronized (lock(knowledgeBaseId, documentId)) {
            RagDocument document = documentStore.find(knowledgeBaseId, documentId);
            if (document == null) {
                throw new IllegalArgumentException("待重建索引的文档不存在: " + documentId);
            }
            RagIngestionCommand command = new RagIngestionCommand();
            command.setKnowledgeBaseId(knowledgeBaseId);
            command.setDocumentId(documentId);
            command.setName(document.getName());
            // 二进制文档仅持久化了解析出的全文，重建时以文本形式重新切分
            command.setContentType(isBinaryContentType(document.getContentType())
                    ? "text/plain" : document.getContentType());
            command.setContent(document.getSourceContent());
            command.setMetadata(document.getMetadata() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(document.getMetadata()));
            return ingest(command, true);
        }
    }

    private RagIngestionResult ingest(RagIngestionCommand command, boolean force) {
        ensureDocumentId(command);
        validate(command);
        String knowledgeBaseId = command.getKnowledgeBaseId();
        String documentId = command.getDocumentId();
        synchronized (lock(knowledgeBaseId, documentId)) {
            RagDocument previous = documentStore.find(knowledgeBaseId, documentId);
            String checksum = checksum(command);
            if (!force && previous != null && previous.getStatus() == RagDocument.Status.READY
                    && checksum.equals(previous.getChecksum())) {
                return result(previous, true);
            }
            enforceKnowledgeBaseCapacity(knowledgeBaseId, previous);

            RagDocument processing = processingDocument(command, previous, checksum);
            documentStore.save(processing);
            try {
                ParsedDocument parsed = parser.parse(toSource(command));
                enforceDocumentChars(parsed);
                List<RagChunk> chunks = chunker.split(processing, parsed);
                if (chunks.isEmpty()) {
                    throw new IllegalArgumentException("文档切分后没有可索引内容");
                }
                enforceChunkCapacity(chunks);
                if (isBinaryUpload(command) && blank(processing.getSourceContent())) {
                    // 二进制文档存解析出的全文，供重建索引时复用（以文本形式重新切分）
                    processing.setSourceContent(joinParsedText(parsed));
                }
                List<float[]> vectors = embed(chunks);
                List<RagIndexEntry> entries = toEntries(chunks, vectors);
                indexStore.upsert(entries);

                processing.setChunkCount(chunks.size());
                processing.setStatus(RagDocument.Status.READY);
                processing.setLastError(null);
                processing.setUpdateTime(System.currentTimeMillis());
                documentStore.save(processing);
                return result(processing, false);
            } catch (RuntimeException e) {
                restoreAfterFailure(previous, processing, e);
                throw e;
            }
        }
    }

    private void enforceKnowledgeBaseCapacity(String knowledgeBaseId, RagDocument previous) {
        int max = capacity.getMaxDocumentsPerKnowledgeBase();
        if (max <= 0 || previous != null) {
            return;
        }
        List<RagDocument> existing = documentStore.list(knowledgeBaseId);
        int active = 0;
        for (RagDocument document : existing) {
            if (document.getStatus() != RagDocument.Status.FAILED) {
                active++;
            }
        }
        if (active >= max) {
            throw new IllegalArgumentException("知识库文档数已达上限 " + max + "，请清理后重试: " + knowledgeBaseId);
        }
    }

    private void enforceDocumentChars(ParsedDocument parsed) {
        int max = capacity.getMaxDocumentChars();
        if (max <= 0) {
            return;
        }
        int total = 0;
        for (ParsedDocument.Section section : parsed.getSections()) {
            if (section.getContent() != null) {
                total += section.getContent().length();
            }
        }
        if (total > max) {
            throw new IllegalArgumentException("文档解析后文本长度超过上限 " + max + " 字符，实际 " + total);
        }
    }

    private void enforceChunkCapacity(List<RagChunk> chunks) {
        int max = capacity.getMaxChunksPerDocument();
        if (max > 0 && chunks.size() > max) {
            throw new IllegalArgumentException("文档分块数超过上限 " + max + "，实际 " + chunks.size()
                    + "，请调大 ingestion.chunk-size 或精简文档");
        }
    }

    private String joinParsedText(ParsedDocument parsed) {
        StringBuilder builder = new StringBuilder();
        for (ParsedDocument.Section section : parsed.getSections()) {
            if (section.getContent() != null && !section.getContent().trim().isEmpty()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                if (section.getTitlePath() != null && !section.getTitlePath().trim().isEmpty()) {
                    builder.append(section.getTitlePath()).append('\n');
                }
                builder.append(section.getContent().trim());
            }
        }
        return builder.toString();
    }

    private List<float[]> embed(List<RagChunk> chunks) {
        int batchSize = config.getEmbeddingBatchSize();
        if (batchSize <= 0) {
            throw new IllegalArgumentException("agent.rag.ingestion.embedding-batch-size 必须大于 0");
        }
        List<float[]> all = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(chunks.size(), start + batchSize);
            List<String> texts = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                texts.add(chunks.get(i).getContent());
            }
            List<float[]> batch = embeddingGateway.embedBatch(texts);
            if (batch == null || batch.size() != texts.size()) {
                throw new IllegalStateException("Embedding 返回数量与请求数量不一致");
            }
            all.addAll(batch);
        }
        validateVectors(all);
        return all;
    }

    private void validateVectors(List<float[]> vectors) {
        if (blank(embeddingGateway.modelId())) {
            throw new IllegalStateException("RAG Embedding modelId 不能为空");
        }
        int expected = embeddingGateway.dimensions();
        for (float[] vector : vectors) {
            if (vector == null || vector.length == 0) {
                throw new IllegalStateException("Embedding 返回了空向量");
            }
            if (expected <= 0) {
                expected = vector.length;
            }
            if (vector.length != expected) {
                throw new IllegalStateException("Embedding 向量维度不一致");
            }
        }
    }

    private List<RagIndexEntry> toEntries(List<RagChunk> chunks, List<float[]> vectors) {
        List<RagIndexEntry> entries = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RagChunk chunk = chunks.get(i);
            float[] vector = vectors.get(i);
            RagIndexEntry entry = new RagIndexEntry();
            entry.setChunkId(chunk.getChunkId());
            entry.setKnowledgeBaseId(chunk.getKnowledgeBaseId());
            entry.setDocumentId(chunk.getDocumentId());
            entry.setDocumentVersion(chunk.getDocumentVersion());
            entry.setSequence(chunk.getSequence());
            entry.setContent(chunk.getContent());
            entry.setMetadata(new LinkedHashMap<>(chunk.getMetadata()));
            entry.setVector(vector);
            entry.setEmbeddingModel(embeddingGateway.modelId());
            entry.setDimensions(vector.length);
            entries.add(entry);
        }
        return entries;
    }

    private RagDocument processingDocument(RagIngestionCommand command,
                                           RagDocument previous,
                                           String checksum) {
        long now = System.currentTimeMillis();
        RagDocument document = new RagDocument();
        document.setKnowledgeBaseId(command.getKnowledgeBaseId());
        document.setDocumentId(command.getDocumentId());
        document.setName(blank(command.getName()) ? command.getDocumentId() : command.getName().trim());
        document.setContentType(blank(command.getContentType()) ? "text/plain" : command.getContentType().trim());
        document.setChecksum(checksum);
        document.setVersion(previous == null ? 1L : previous.getVersion() + 1L);
        document.setStatus(RagDocument.Status.PROCESSING);
        document.setSourceContent(command.getContent());
        document.setMetadata(command.getMetadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(command.getMetadata()));
        document.setCreateTime(previous == null ? now : previous.getCreateTime());
        document.setUpdateTime(now);
        return document;
    }

    private RagDocumentSource toSource(RagIngestionCommand command) {
        RagDocumentSource source = new RagDocumentSource();
        source.setName(command.getName());
        source.setContentType(command.getContentType());
        source.setContent(command.getContent());
        source.setContentBytes(command.getContentBytes());
        return source;
    }

    private void restoreAfterFailure(RagDocument previous, RagDocument processing, RuntimeException error) {
        if (previous != null && previous.getStatus() == RagDocument.Status.READY) {
            documentStore.save(previous);
            return;
        }
        processing.setStatus(RagDocument.Status.FAILED);
        processing.setLastError(truncate(error.getMessage()));
        processing.setUpdateTime(System.currentTimeMillis());
        documentStore.save(processing);
    }

    /** 文档 ID 为空时自动生成（兑现 RagIngestionCommand.documentId「为空时由实现生成」约定）。 */
    private void ensureDocumentId(RagIngestionCommand command) {
        if (blank(command.getDocumentId())) {
            command.setDocumentId(UUID.randomUUID().toString().replace("-", ""));
        }
    }

    private void validate(RagIngestionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("RAG 写入命令不能为空");
        }
        validateIds(command.getKnowledgeBaseId(), command.getDocumentId());
        if (blank(command.getContent()) && (command.getContentBytes() == null
                || command.getContentBytes().length == 0)) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
    }

    private void validateIds(String knowledgeBaseId, String documentId) {
        RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId);
        RagFileSupport.requireId("documentId", documentId);
    }

    /** 是否为二进制内容上传（PDF / Word）：仅有 contentBytes 且无文本 content。 */
    private boolean isBinaryUpload(RagIngestionCommand command) {
        return blank(command.getContent())
                && command.getContentBytes() != null && command.getContentBytes().length > 0;
    }

    /** 是否为二进制文档类型（重建索引时无法复用原始字节，需按已提取文本处理）。 */
    private boolean isBinaryContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.trim().toLowerCase();
        return type.startsWith("application/pdf")
                || type.startsWith("application/msword")
                || type.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml")
                || type.startsWith("application/octet-stream");
    }

    private Object lock(String knowledgeBaseId, String documentId) {
        return locks.computeIfAbsent(knowledgeBaseId + ":" + documentId, key -> new Object());
    }

    private RagIngestionResult result(RagDocument document, boolean skipped) {
        RagIngestionResult result = new RagIngestionResult();
        result.setKnowledgeBaseId(document.getKnowledgeBaseId());
        result.setDocumentId(document.getDocumentId());
        result.setVersion(document.getVersion());
        result.setChunkCount(document.getChunkCount());
        result.setStatus(document.getStatus());
        result.setSkipped(skipped);
        return result;
    }

    private String checksum(RagIngestionCommand command) {
        try {
            if (isBinaryUpload(command)) {
                return "b:" + sha256Hex(command.getContentBytes());
            }
            return "t:" + sha256Hex(command.getContent().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private String sha256Hex(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String truncate(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
