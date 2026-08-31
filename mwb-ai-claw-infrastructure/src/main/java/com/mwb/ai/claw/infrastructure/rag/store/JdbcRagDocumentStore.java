package com.mwb.ai.claw.infrastructure.rag.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.store.RagDocumentStore;
import com.mwb.ai.claw.domain.util.JsonUtils;

/**
 * JDBC 版 RAG 原始文档与状态存储（agent.storage.type=db）：claw_rag_document 表。
 * <p>
 * - 与业务同库（MySQL 等），主键 (knowledge_base_id, document_id)；
 * - metadata / source_content 以 JSON / TEXT 存储（与 JDBC 存储族风格一致）；
 * - 表结构见 {@code framework-schema.sql}，首次使用自动建表。
 */
public class JdbcRagDocumentStore implements RagDocumentStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRagDocumentStore.class);

    private final JdbcTemplate jdbc;
    private volatile boolean tableReady;

    public JdbcRagDocumentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RagDocument find(String knowledgeBaseId, String documentId) {
        String kb = RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId);
        String doc = RagFileSupport.requireId("documentId", documentId);
        ensureTableIfPossible();
        List<RagDocument> found = jdbc.query(
                "SELECT knowledge_base_id, document_id, name, content_type, checksum, version, chunk_count,"
                        + " status, source_content, last_error, metadata, create_time, update_time"
                        + " FROM claw_rag_document WHERE knowledge_base_id = ? AND document_id = ?",
                (rs, i) -> toDocument(rs), kb, doc);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public void save(RagDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("RAG 文档不能为空");
        }
        String kb = RagFileSupport.requireId("knowledgeBaseId", document.getKnowledgeBaseId());
        String doc = RagFileSupport.requireId("documentId", document.getDocumentId());
        ensureTable();
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM claw_rag_document"
                + " WHERE knowledge_base_id = ? AND document_id = ?", Integer.class, kb, doc);
        if (cnt != null && cnt > 0) {
            jdbc.update("UPDATE claw_rag_document SET name=?, content_type=?, checksum=?, version=?,"
                            + " chunk_count=?, status=?, source_content=?, last_error=?, metadata=?, update_time=?"
                            + " WHERE knowledge_base_id = ? AND document_id = ?",
                    document.getName(), document.getContentType(), document.getChecksum(),
                    document.getVersion(), document.getChunkCount(),
                    status(document.getStatus()), document.getSourceContent(), document.getLastError(),
                    JsonUtils.toJson(document.getMetadata() == null
                            ? new LinkedHashMap<>() : document.getMetadata()),
                    System.currentTimeMillis(), kb, doc);
        } else {
            jdbc.update("INSERT INTO claw_rag_document (knowledge_base_id, document_id, name, content_type,"
                            + " checksum, version, chunk_count, status, source_content, last_error, metadata,"
                            + " create_time, update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    kb, doc, document.getName(), document.getContentType(), document.getChecksum(),
                    document.getVersion(), document.getChunkCount(),
                    status(document.getStatus()), document.getSourceContent(), document.getLastError(),
                    JsonUtils.toJson(document.getMetadata() == null
                            ? new LinkedHashMap<>() : document.getMetadata()),
                    document.getCreateTime(), System.currentTimeMillis());
        }
    }

    @Override
    public void delete(String knowledgeBaseId, String documentId) {
        String kb = RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId);
        String doc = RagFileSupport.requireId("documentId", documentId);
        ensureTableIfPossible();
        jdbc.update("DELETE FROM claw_rag_document WHERE knowledge_base_id = ? AND document_id = ?", kb, doc);
    }

    @Override
    public List<RagDocument> list(String knowledgeBaseId) {
        String kb = RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId);
        ensureTableIfPossible();
        return jdbc.query("SELECT knowledge_base_id, document_id, name, content_type, checksum, version,"
                        + " chunk_count, status, source_content, last_error, metadata, create_time, update_time"
                        + " FROM claw_rag_document WHERE knowledge_base_id = ? ORDER BY document_id ASC",
                (rs, i) -> toDocument(rs), kb);
    }

    private RagDocument toDocument(ResultSet rs) throws SQLException {
        RagDocument document = new RagDocument();
        document.setKnowledgeBaseId(rs.getString("knowledge_base_id"));
        document.setDocumentId(rs.getString("document_id"));
        document.setName(rs.getString("name"));
        document.setContentType(rs.getString("content_type"));
        document.setChecksum(rs.getString("checksum"));
        document.setVersion(rs.getLong("version"));
        document.setChunkCount(rs.getInt("chunk_count"));
        document.setStatus(parseStatus(rs.getString("status")));
        document.setSourceContent(rs.getString("source_content"));
        document.setLastError(rs.getString("last_error"));
        document.setMetadata(parseMetadata(rs.getString("metadata")));
        document.setCreateTime(rs.getLong("create_time"));
        document.setUpdateTime(rs.getLong("update_time"));
        return document;
    }

    private Map<String, String> parseMetadata(String value) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (value == null || value.isEmpty()) {
            return metadata;
        }
        try {
            Map<String, Object> raw = JsonUtils.fromJson(value, Map.class);
            if (raw != null) {
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    metadata.put(entry.getKey(), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("解析 RAG 文档元数据失败，按空处理: {}", value);
        }
        return metadata;
    }

    private RagDocument.Status parseStatus(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return RagDocument.Status.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String status(RagDocument.Status status) {
        return status == null ? null : status.name();
    }

    private void ensureTable() {
        if (tableReady) {
            return;
        }
        synchronized (this) {
            if (tableReady) {
                return;
            }
            jdbc.execute("CREATE TABLE IF NOT EXISTS claw_rag_document ("
                    + "knowledge_base_id VARCHAR(128) NOT NULL, "
                    + "document_id VARCHAR(128) NOT NULL, "
                    + "name VARCHAR(512), "
                    + "content_type VARCHAR(128), "
                    + "checksum VARCHAR(128), "
                    + "version BIGINT NOT NULL DEFAULT 0, "
                    + "chunk_count INT NOT NULL DEFAULT 0, "
                    + "status VARCHAR(32), "
                    + "source_content TEXT, "
                    + "last_error TEXT, "
                    + "metadata TEXT, "
                    + "create_time BIGINT NOT NULL DEFAULT 0, "
                    + "update_time BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (knowledge_base_id, document_id))");
            tableReady = true;
        }
    }

    private void ensureTableIfPossible() {
        if (tableReady) {
            return;
        }
        try {
            jdbc.queryForObject("SELECT 1 FROM claw_rag_document LIMIT 1", Integer.class);
            tableReady = true;
        } catch (RuntimeException e) {
            // 表不存在，保持未就绪；find/delete/list 直接按空处理
        }
    }
}
