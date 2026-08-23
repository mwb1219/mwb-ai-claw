package com.mwb.ai.claw.infrastructure.rag.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.store.RagDocumentStore;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * 文件版 RAG 原始文档与状态存储。
 */
public class FileRagDocumentStore implements RagDocumentStore {

    private final Path documentsDir;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public FileRagDocumentStore(RagConfig config) {
        this.documentsDir = ragRoot(config).resolve("documents");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(documentsDir);
        } catch (IOException e) {
            throw new IllegalStateException("初始化 RAG 文档目录失败: " + documentsDir, e);
        }
    }

    @Override
    public RagDocument find(String knowledgeBaseId, String documentId) {
        Path file = documentFile(knowledgeBaseId, documentId);
        synchronized (lock(knowledgeBaseId, documentId)) {
            if (!Files.exists(file)) {
                return null;
            }
            try {
                return JsonUtils.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                        RagDocument.class);
            } catch (IOException e) {
                throw new IllegalStateException("读取 RAG 文档失败: " + file, e);
            }
        }
    }

    @Override
    public void save(RagDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("RAG 文档不能为空");
        }
        Path file = documentFile(document.getKnowledgeBaseId(), document.getDocumentId());
        synchronized (lock(document.getKnowledgeBaseId(), document.getDocumentId())) {
            RagFileSupport.atomicWrite(file, JsonUtils.toJson(document));
        }
    }

    @Override
    public void delete(String knowledgeBaseId, String documentId) {
        Path file = documentFile(knowledgeBaseId, documentId);
        synchronized (lock(knowledgeBaseId, documentId)) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new IllegalStateException("删除 RAG 文档失败: " + file, e);
            }
        }
    }

    @Override
    public List<RagDocument> list(String knowledgeBaseId) {
        Path dir = documentsDir.resolve(RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId));
        if (!Files.exists(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<RagDocument> result = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readDocument)
                    .filter(document -> document != null)
                    .sorted(Comparator.comparing(RagDocument::getDocumentId))
                    .collect(Collectors.toList());
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("列出 RAG 文档失败: " + dir, e);
        }
    }

    private RagDocument readDocument(Path file) {
        try {
            return JsonUtils.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                    RagDocument.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析 RAG 文档失败: " + file, e);
        }
    }

    private Path documentFile(String knowledgeBaseId, String documentId) {
        String kb = RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId);
        String doc = RagFileSupport.requireId("documentId", documentId);
        return documentsDir.resolve(kb).resolve(doc + ".json");
    }

    private Object lock(String knowledgeBaseId, String documentId) {
        return locks.computeIfAbsent(knowledgeBaseId + ":" + documentId, key -> new Object());
    }

    private static Path ragRoot(RagConfig config) {
        String dir = config.getLocal().getDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.dir") + "/.agent/rag";
        }
        return Paths.get(dir).toAbsolutePath().normalize();
    }
}
