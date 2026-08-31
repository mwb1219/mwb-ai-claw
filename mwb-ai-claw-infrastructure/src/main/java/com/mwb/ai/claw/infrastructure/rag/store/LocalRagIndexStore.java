package com.mwb.ai.claw.infrastructure.rag.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.model.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.model.RagVectorQuery;
import com.mwb.ai.claw.domain.rag.store.RagIndexStore;
import com.mwb.ai.claw.domain.util.JsonUtils;

/**
 * 本地 RAG 向量索引：JSONL 持久化，查询时在内存中执行余弦相似度扫描。
 */
public class LocalRagIndexStore implements RagIndexStore {

    private final Path indexesDir;
    private final ConcurrentMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public LocalRagIndexStore(RagConfig config) {
        String dir = config.getLocal().getDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.dir") + "/.agent/rag";
        }
        this.indexesDir = Paths.get(dir).toAbsolutePath().normalize().resolve("indexes");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(indexesDir);
        } catch (IOException e) {
            throw new IllegalStateException("初始化 RAG 索引目录失败: " + indexesDir, e);
        }
    }

    @Override
    public void upsert(List<RagIndexEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("RAG 索引记录不能为空");
        }
        Map<String, List<RagIndexEntry>> byKnowledgeBase = new LinkedHashMap<>();
        for (RagIndexEntry entry : entries) {
            validateEntry(entry);
            byKnowledgeBase.computeIfAbsent(entry.getKnowledgeBaseId(), key -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, List<RagIndexEntry>> group : byKnowledgeBase.entrySet()) {
            replaceDocuments(group.getKey(), group.getValue());
        }
    }

    @Override
    public void deleteByDocument(String knowledgeBaseId, String documentId) {
        String kb = RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId);
        String doc = RagFileSupport.requireId("documentId", documentId);
        ReentrantReadWriteLock.WriteLock lock = lock(kb).writeLock();
        lock.lock();
        try {
            List<RagIndexEntry> entries = loadEntries(kb);
            boolean changed = entries.removeIf(entry -> doc.equals(entry.getDocumentId()));
            if (changed) {
                writeEntries(kb, entries);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RagSearchResult> search(RagVectorQuery query) {
        if (query == null || query.getVector() == null || query.getVector().length == 0
                || query.getTopK() <= 0) {
            return new ArrayList<>();
        }
        List<String> knowledgeBaseIds = query.getKnowledgeBaseIds() == null
                || query.getKnowledgeBaseIds().isEmpty()
                ? listKnowledgeBaseIds() : query.getKnowledgeBaseIds();
        List<RagSearchResult> matches = new ArrayList<>();
        for (String rawId : knowledgeBaseIds) {
            String knowledgeBaseId = RagFileSupport.requireId("knowledgeBaseId", rawId);
            ReentrantReadWriteLock.ReadLock lock = lock(knowledgeBaseId).readLock();
            lock.lock();
            try {
                for (RagIndexEntry entry : loadEntries(knowledgeBaseId)) {
                    if (!matches(query, entry)) {
                        continue;
                    }
                    double score = cosine(query.getVector(), entry.getVector());
                    if (score >= query.getMinScore()) {
                        matches.add(toResult(entry, score));
                    }
                }
            } finally {
                lock.unlock();
            }
        }
        matches.sort(Comparator.comparingDouble(RagSearchResult::getScore).reversed()
                .thenComparing(RagSearchResult::getChunkId));
        return new ArrayList<>(matches.subList(0, Math.min(query.getTopK(), matches.size())));
    }

    private void replaceDocuments(String knowledgeBaseId, List<RagIndexEntry> replacements) {
        String kb = RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId);
        Set<String> documentIds = replacements.stream()
                .map(RagIndexEntry::getDocumentId)
                .collect(Collectors.toCollection(HashSet::new));
        ReentrantReadWriteLock.WriteLock lock = lock(kb).writeLock();
        lock.lock();
        try {
            List<RagIndexEntry> merged = loadEntries(kb);
            merged.removeIf(entry -> documentIds.contains(entry.getDocumentId()));
            merged.addAll(replacements);
            merged.sort(Comparator.comparing(RagIndexEntry::getDocumentId)
                    .thenComparingInt(RagIndexEntry::getSequence));
            writeEntries(kb, merged);
        } finally {
            lock.unlock();
        }
    }

    private boolean matches(RagVectorQuery query, RagIndexEntry entry) {
        if (entry.getVector() == null || entry.getVector().length != query.getVector().length) {
            return false;
        }
        if (query.getDimensions() > 0 && entry.getDimensions() != query.getDimensions()) {
            return false;
        }
        if (query.getEmbeddingModel() != null && !query.getEmbeddingModel().isEmpty()
                && !query.getEmbeddingModel().equals(entry.getEmbeddingModel())) {
            return false;
        }
        if (query.getFilters() != null) {
            for (Map.Entry<String, String> filter : query.getFilters().entrySet()) {
                if (entry.getMetadata() == null
                        || !filter.getValue().equals(entry.getMetadata().get(filter.getKey()))) {
                    return false;
                }
            }
        }
        return true;
    }

    private RagSearchResult toResult(RagIndexEntry entry, double score) {
        RagSearchResult result = new RagSearchResult();
        result.setKnowledgeBaseId(entry.getKnowledgeBaseId());
        result.setDocumentId(entry.getDocumentId());
        result.setDocumentVersion(entry.getDocumentVersion());
        result.setChunkId(entry.getChunkId());
        result.setSequence(entry.getSequence());
        result.setContent(entry.getContent());
        result.setMetadata(entry.getMetadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(entry.getMetadata()));
        result.setScore(score);
        return result;
    }

    private void validateEntry(RagIndexEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("RAG 索引记录不能为空");
        }
        RagFileSupport.requireId("knowledgeBaseId", entry.getKnowledgeBaseId());
        RagFileSupport.requireId("documentId", entry.getDocumentId());
        if (entry.getChunkId() == null || entry.getChunkId().trim().isEmpty()) {
            throw new IllegalArgumentException("chunkId 不能为空");
        }
        if (entry.getVector() == null || entry.getVector().length == 0) {
            throw new IllegalArgumentException("索引向量不能为空");
        }
        if (entry.getDimensions() != entry.getVector().length) {
            throw new IllegalArgumentException("索引向量维度不一致: " + entry.getChunkId());
        }
    }

    private List<RagIndexEntry> loadEntries(String knowledgeBaseId) {
        Path file = indexFile(knowledgeBaseId);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<RagIndexEntry> result = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) {
                    result.add(JsonUtils.fromJson(line, RagIndexEntry.class));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("读取 RAG 索引失败: " + file, e);
        }
    }

    private void writeEntries(String knowledgeBaseId, List<RagIndexEntry> entries) {
        StringBuilder content = new StringBuilder();
        for (RagIndexEntry entry : entries) {
            content.append(JsonUtils.toJson(entry)).append(System.lineSeparator());
        }
        RagFileSupport.atomicWrite(indexFile(knowledgeBaseId), content.toString());
    }

    private List<String> listKnowledgeBaseIds() {
        if (!Files.exists(indexesDir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> stream = Files.list(indexesDir)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("列出 RAG 知识库索引失败: " + indexesDir, e);
        }
    }

    private Path indexFile(String knowledgeBaseId) {
        return indexesDir.resolve(RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId))
                .resolve("entries.jsonl");
    }

    private ReentrantReadWriteLock lock(String knowledgeBaseId) {
        return locks.computeIfAbsent(knowledgeBaseId, key -> new ReentrantReadWriteLock());
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
