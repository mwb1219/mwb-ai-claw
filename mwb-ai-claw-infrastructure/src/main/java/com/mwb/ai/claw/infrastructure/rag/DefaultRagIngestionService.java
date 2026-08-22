package com.mwb.ai.claw.infrastructure.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.mwb.ai.claw.domain.rag.ParsedDocument;
import com.mwb.ai.claw.domain.rag.RagChunk;
import com.mwb.ai.claw.domain.rag.RagChunker;
import com.mwb.ai.claw.domain.rag.RagConfig;
import com.mwb.ai.claw.domain.rag.RagDocument;
import com.mwb.ai.claw.domain.rag.RagDocumentParser;
import com.mwb.ai.claw.domain.rag.RagDocumentSource;
import com.mwb.ai.claw.domain.rag.RagDocumentStore;
import com.mwb.ai.claw.domain.rag.RagEmbeddingGateway;
import com.mwb.ai.claw.domain.rag.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.RagIndexStore;
import com.mwb.ai.claw.domain.rag.RagIngestionCommand;
import com.mwb.ai.claw.domain.rag.RagIngestionResult;
import com.mwb.ai.claw.domain.rag.RagIngestionService;

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
            command.setContentType(document.getContentType());
            command.setContent(document.getSourceContent());
            command.setMetadata(document.getMetadata() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(document.getMetadata()));
            return ingest(command, true);
        }
    }

    private RagIngestionResult ingest(RagIngestionCommand command, boolean force) {
        validate(command);
        String knowledgeBaseId = command.getKnowledgeBaseId();
        String documentId = command.getDocumentId();
        synchronized (lock(knowledgeBaseId, documentId)) {
            RagDocument previous = documentStore.find(knowledgeBaseId, documentId);
            String checksum = checksum(command.getContent());
            if (!force && previous != null && previous.getStatus() == RagDocument.Status.READY
                    && checksum.equals(previous.getChecksum())) {
                return result(previous, true);
            }

            RagDocument processing = processingDocument(command, previous, checksum);
            documentStore.save(processing);
            try {
                ParsedDocument parsed = parser.parse(toSource(command));
                List<RagChunk> chunks = chunker.split(processing, parsed);
                if (chunks.isEmpty()) {
                    throw new IllegalArgumentException("文档切分后没有可索引内容");
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

    private void validate(RagIngestionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("RAG 写入命令不能为空");
        }
        validateIds(command.getKnowledgeBaseId(), command.getDocumentId());
        if (blank(command.getContent())) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
    }

    private void validateIds(String knowledgeBaseId, String documentId) {
        RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId);
        RagFileSupport.requireId("documentId", documentId);
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

    private String checksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
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
