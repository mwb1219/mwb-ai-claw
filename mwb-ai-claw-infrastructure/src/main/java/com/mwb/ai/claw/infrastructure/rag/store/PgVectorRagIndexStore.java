package com.mwb.ai.claw.infrastructure.rag.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.model.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.model.RagVectorQuery;
import com.mwb.ai.claw.domain.rag.store.RagIndexStore;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * PGVector 向量索引参考实现（provider=pgvector）。
 * <p>
 * 依赖 PostgreSQL + <a href="https://github.com/pgvector/pgvector">pgvector</a> 扩展，
 * 通过应用侧 {@code JdbcTemplate} 读写；首次写入时按实际向量维度建表，并尝试创建向量索引。
 * <p>
 * 前置条件：
 * <pre>
 *   -- 在目标库执行一次（需有权限）
 *   CREATE EXTENSION IF NOT EXISTS vector;
 *   -- 应用侧引入驱动并指向 PostgreSQL（spring.datasource.*）
 *   -- 配置 agent.rag.provider=pgvector
 * </pre>
 */
public class PgVectorRagIndexStore implements RagIndexStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorRagIndexStore.class);

    private final JdbcTemplate jdbc;
    private final String table;
    private final String indexType;
    private final String similarity;
    private volatile boolean tableReady;

    public PgVectorRagIndexStore(JdbcTemplate jdbc, RagConfig config) {
        RagConfig.PgVectorConfig pg = config.getPgvector();
        this.jdbc = jdbc;
        this.table = qualifiedTable(pg);
        this.indexType = requireIdentifier(pg.getIndexType(), "indexType");
        this.similarity = requireIdentifier(pg.getSimilarity(), "similarity");
    }

    @Override
    public void upsert(List<RagIndexEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("RAG 索引记录不能为空");
        }
        int dimensions = entries.get(0).getVector().length;
        ensureTable(dimensions);
        for (RagIndexEntry entry : entries) {
            validateEntry(entry, dimensions);
        }
        // 原子替换本批记录所属文档：先删后插（同文档块在一次事务内完成）
        Map<String, List<RagIndexEntry>> byDocument = new LinkedHashMap<>();
        for (RagIndexEntry entry : entries) {
            byDocument.computeIfAbsent(entry.getDocumentId(), key -> new ArrayList<>()).add(entry);
        }
        String deleteSql = "DELETE FROM " + table + " WHERE knowledge_base_id = ? AND document_id = ?";
        String insertSql = "INSERT INTO " + table
                + " (chunk_id, knowledge_base_id, document_id, document_version, sequence, content,"
                + " metadata, embedding, embedding_model, dimensions)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector, ?, ?)";
        for (Map.Entry<String, List<RagIndexEntry>> doc : byDocument.entrySet()) {
            RagIndexEntry first = doc.getValue().get(0);
            jdbc.update(deleteSql, first.getKnowledgeBaseId(), doc.getKey());
            for (RagIndexEntry entry : doc.getValue()) {
                jdbc.update(insertSql, entry.getChunkId(), entry.getKnowledgeBaseId(),
                        entry.getDocumentId(), entry.getDocumentVersion(), entry.getSequence(),
                        entry.getContent(), JsonUtils.toJson(entry.getMetadata() == null
                                ? new LinkedHashMap<>() : entry.getMetadata()),
                        toVector(entry.getVector()), entry.getEmbeddingModel(), entry.getDimensions());
            }
        }
    }

    @Override
    public void deleteByDocument(String knowledgeBaseId, String documentId) {
        String kb = RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId);
        String doc = RagFileSupport.requireId("documentId", documentId);
        ensureTableIfPossible();
        jdbc.update("DELETE FROM " + table + " WHERE knowledge_base_id = ? AND document_id = ?", kb, doc);
    }

    @Override
    public List<RagSearchResult> search(RagVectorQuery query) {
        if (query == null || query.getVector() == null || query.getVector().length == 0
                || query.getTopK() <= 0) {
            return new ArrayList<>();
        }
        ensureTableIfPossible();
        List<String> knowledgeBaseIds = query.getKnowledgeBaseIds();
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("PGVector 检索必须显式指定 knowledgeBaseIds");
        }
        List<Object> args = new ArrayList<>();
        args.add(toVector(query.getVector()));

        StringBuilder sql = new StringBuilder("SELECT chunk_id, knowledge_base_id, document_id,")
                .append(" document_version, sequence, content, metadata, embedding_model, dimensions,")
                .append(" (embedding <=> ?::vector) AS distance")
                .append(" FROM ").append(table)
                .append(" WHERE knowledge_base_id IN (");
        for (int i = 0; i < knowledgeBaseIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
            args.add(RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseIds.get(i)));
        }
        sql.append(')');
        if (query.getFilters() != null) {
            for (Map.Entry<String, String> filter : query.getFilters().entrySet()) {
                if (filter.getKey() == null || filter.getKey().isEmpty()) {
                    continue;
                }
                sql.append(" AND metadata->>? = ?");
                args.add(filter.getKey());
                args.add(filter.getValue() == null ? "" : filter.getValue());
            }
        }
        sql.append(" ORDER BY embedding <-> ?::vector LIMIT ?");
        args.add(toVector(query.getVector()));
        args.add(query.getTopK());

        List<RagSearchResult> matches = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql.toString(), args.toArray())) {
            double distance = ((Number) row.get("distance")).doubleValue();
            double score = 1D - distance;
            if (score < query.getMinScore()) {
                continue;
            }
            matches.add(toResult(row, score));
        }
        matches.sort(Comparator.comparingDouble(RagSearchResult::getScore).reversed()
                .thenComparing(RagSearchResult::getChunkId));
        return matches;
    }

    private RagSearchResult toResult(Map<String, Object> row, double score) {
        RagSearchResult result = new RagSearchResult();
        result.setChunkId(String.valueOf(row.get("chunk_id")));
        result.setKnowledgeBaseId(String.valueOf(row.get("knowledge_base_id")));
        result.setDocumentId(String.valueOf(row.get("document_id")));
        result.setDocumentVersion(((Number) row.get("document_version")).longValue());
        result.setSequence(((Number) row.get("sequence")).intValue());
        result.setContent(String.valueOf(row.get("content")));
        result.setMetadata(parseMetadata(row.get("metadata")));
        result.setScore(score);
        return result;
    }

    private Map<String, String> parseMetadata(Object value) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (value == null) {
            return metadata;
        }
        try {
            Map<String, Object> raw = JsonUtils.fromJson(String.valueOf(value), Map.class);
            if (raw != null) {
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    metadata.put(entry.getKey(), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("解析 RAG 索引元数据失败，按空处理: {}", value);
        }
        return metadata;
    }

    private void ensureTable(int dimensions) {
        if (tableReady) {
            return;
        }
        synchronized (this) {
            if (tableReady) {
                return;
            }
            String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "chunk_id VARCHAR(128) NOT NULL, "
                    + "knowledge_base_id VARCHAR(128) NOT NULL, "
                    + "document_id VARCHAR(128) NOT NULL, "
                    + "document_version BIGINT NOT NULL, "
                    + "sequence INT NOT NULL, "
                    + "content TEXT NOT NULL, "
                    + "metadata JSONB NOT NULL DEFAULT '{}'::jsonb, "
                    + "embedding vector(" + dimensions + ") NOT NULL, "
                    + "embedding_model VARCHAR(128), "
                    + "dimensions INT NOT NULL, "
                    + "PRIMARY KEY (chunk_id))";
            jdbc.execute(ddl);
            try {
                jdbc.execute("CREATE INDEX IF NOT EXISTS " + table.replace('.', '_') + "_kb_idx"
                        + " ON " + table + " (knowledge_base_id)");
                jdbc.execute(indexDdl(dimensions));
            } catch (RuntimeException e) {
                log.warn("创建 PGVector 向量索引失败（可继续使用全表扫描检索）: {}", e.getMessage());
            }
            tableReady = true;
        }
    }

    private void ensureTableIfPossible() {
        if (tableReady) {
            return;
        }
        // 检索 / 删除时表可能尚未创建（无写入），尝试探测；失败说明表不存在，按空结果处理
        try {
            jdbc.queryForObject("SELECT 1 FROM " + table + " LIMIT 1", Integer.class);
            tableReady = true;
        } catch (RuntimeException e) {
            // 表不存在，保持未就绪；search/delete 直接返回空即可
        }
    }

    private String indexDdl(int dimensions) {
        String name = table.replace('.', '_') + "_embedding_idx";
        String with = "ivfflat".equals(indexType)
                ? " WITH (lists = 100)"
                : " WITH (m = 16, ef_construction = 64)";
        return "CREATE INDEX IF NOT EXISTS " + name + " ON " + table
                + " USING " + indexType + " (embedding " + similarity + ")" + with;
    }

    private void validateEntry(RagIndexEntry entry, int dimensions) {
        RagFileSupport.requireId("knowledgeBaseId", entry.getKnowledgeBaseId());
        RagFileSupport.requireId("documentId", entry.getDocumentId());
        if (entry.getChunkId() == null || entry.getChunkId().trim().isEmpty()) {
            throw new IllegalArgumentException("chunkId 不能为空");
        }
        if (entry.getVector() == null || entry.getVector().length == 0) {
            throw new IllegalArgumentException("索引向量不能为空");
        }
        if (entry.getVector().length != dimensions) {
            throw new IllegalArgumentException("索引向量维度不一致: " + entry.getChunkId());
        }
    }

    private String toVector(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 4 + 2);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }

    private String qualifiedTable(RagConfig.PgVectorConfig pg) {
        String schema = requireIdentifier(pg.getSchema(), "schema");
        String name = requireIdentifier(pg.getTable(), "table");
        return "public".equals(schema) ? name : schema + "." + name;
    }

    private String requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException("agent.rag.pgvector." + field
                    + " 仅允许字母、数字、下划线与点: " + value);
        }
        return value.trim();
    }
}
