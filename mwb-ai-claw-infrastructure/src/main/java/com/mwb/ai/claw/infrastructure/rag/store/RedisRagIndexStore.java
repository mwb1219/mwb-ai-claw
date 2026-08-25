package com.mwb.ai.claw.infrastructure.rag.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.rag.model.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.model.RagVectorQuery;
import com.mwb.ai.claw.domain.rag.store.RagIndexStore;
import com.mwb.ai.claw.infrastructure.redis.RedisSearchTemplate;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * RAG 索引的 MySQL + Redis 双后端实现（provider=redis，或 auto + storage=db）。
 * <p>
 * - MySQL 为权威存储：rag_index_entries 仅文本 + 元数据（无向量列）；
 * - Redis 为派生检索索引：全文 / 向量 KNN 全部在 Redis Stack 完成；
 * - 写入 = 先删后插（MySQL 文档级原子替换）+ 同步双写 Redis Hash；删除双侧同步；
 * - 检索 = FT.SEARCH KNN（FLOAT32 + COSINE），取放大候选后在应用层过滤
 *   knowledgeBaseIds / metadata filters / minScore（与旧 MySQL BLOB 余弦语义对齐）；
 * - Redis 故障不阻断主事务（异常由 RedisSearchTemplate 吞掉并记日志，MySQL 文本可重建）。
 */
public class RedisRagIndexStore implements RagIndexStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRagIndexStore.class);

    /** 索引存在性确认的 TTL（毫秒）：避免每次写入/检索都发 FT.INFO。 */
    private static final long INDEX_CONFIRM_TTL = 30_000L;

    private final JdbcTemplate jdbc;
    private final RedisSearchTemplate template;
    private final String table = "rag_index_entries";

    private volatile boolean tableReady;
    /** 已建索引维度（0 = 未记录）。 */
    private volatile int indexDimensions;
    private volatile boolean indexInitialized;
    /** 最近一次确认索引存在的时刻（0 = 未确认），用于自愈检测外部删索引/清空 Redis。 */
    private volatile long lastIndexConfirmedAt;

    public RedisRagIndexStore(JdbcTemplate jdbc, RedisSearchTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    @Override
    public void upsert(List<RagIndexEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("RAG 索引记录不能为空");
        }
        int dimensions = entries.get(0).getVector().length;
        ensureTable();
        for (RagIndexEntry entry : entries) {
            validateEntry(entry, dimensions);
        }
        // 1) MySQL 权威存储：按文档原子替换（先删后插）
        Map<String, List<RagIndexEntry>> byDocument = new LinkedHashMap<>();
        for (RagIndexEntry entry : entries) {
            byDocument.computeIfAbsent(entry.getDocumentId(), key -> new ArrayList<>()).add(entry);
        }
        String deleteSql = "DELETE FROM " + table + " WHERE knowledge_base_id = ? AND document_id = ?";
        String insertSql = "INSERT INTO " + table
                + " (chunk_id, knowledge_base_id, document_id, document_version, sequence, content, metadata)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        for (Map.Entry<String, List<RagIndexEntry>> doc : byDocument.entrySet()) {
            RagIndexEntry first = doc.getValue().get(0);
            jdbc.update(deleteSql, first.getKnowledgeBaseId(), doc.getKey());
            for (RagIndexEntry entry : doc.getValue()) {
                jdbc.update(insertSql, entry.getChunkId(), entry.getKnowledgeBaseId(),
                        entry.getDocumentId(), entry.getDocumentVersion(), entry.getSequence(),
                        entry.getContent(), JsonUtils.toJson(entry.getMetadata() == null
                                ? new LinkedHashMap<>() : entry.getMetadata()));
            }
        }
        // 2) Redis 派生索引：先清所属文档旧条目，再逐条 HSET（含向量 float4）
        ensureIndex(dimensions);
        for (RagIndexEntry entry : entries) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("chunk_id", entry.getChunkId());
            fields.put("knowledge_base_id", entry.getKnowledgeBaseId());
            fields.put("document_id", entry.getDocumentId());
            fields.put("document_version", entry.getDocumentVersion());
            fields.put("chunk_seq", entry.getSequence());
            fields.put("content", entry.getContent());
            fields.put("metadata", JsonUtils.toJson(entry.getMetadata() == null
                    ? new LinkedHashMap<>() : entry.getMetadata()));
            if (indexDimensions == dimensions) {
                fields.put("embedding", RedisSearchTemplate.toFloatBytes(entry.getVector()));
            }
            template.hset(entryKey(entry.getKnowledgeBaseId(), entry.getDocumentId(), entry.getSequence()), fields);
        }
    }

    @Override
    public void deleteByDocument(String knowledgeBaseId, String documentId) {
        String kb = RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseId);
        String doc = RagFileSupport.requireId("documentId", documentId);
        ensureTableIfPossible();
        jdbc.update("DELETE FROM " + table + " WHERE knowledge_base_id = ? AND document_id = ?", kb, doc);
        deleteRedisEntries(kb, doc);
    }

    @Override
    public List<RagSearchResult> search(RagVectorQuery query) {
        if (query == null || query.getVector() == null || query.getVector().length == 0
                || query.getTopK() <= 0) {
            return new ArrayList<>();
        }
        List<String> knowledgeBaseIds = query.getKnowledgeBaseIds() == null
                ? new ArrayList<>() : query.getKnowledgeBaseIds();
        // 检索前确认索引存在（外部删索引/清空 Redis 后可自愈重建）
        ensureIndex(query.getVector().length);
        // KNN 取放大候选（应用层过滤 filters/minScore 后可能不足 topK）
        int fetchLimit = Math.min(Math.max(query.getTopK() * 4, 100), 1000);
        String prefix = kbFilter(knowledgeBaseIds);
        List<RedisSearchTemplate.Hit> hits = template.searchKnn(
                template.index("rag"), prefix, query.getVector(), fetchLimit);

        List<ScoredMatch> scored = new ArrayList<>();
        for (RedisSearchTemplate.Hit hit : hits) {
            String kb = hit.field("knowledge_base_id");
            if (kb == null || (!knowledgeBaseIds.isEmpty() && !knowledgeBaseIds.contains(kb))) {
                continue;
            }
            if (!matchFilters(hit.field("metadata"), query.getFilters())) {
                continue;
            }
            double similarity = 1 - hit.getScore(); // COSINE 距离 → 相似度
            if (similarity < query.getMinScore()) {
                continue;
            }
            scored.add(new ScoredMatch(toResult(hit, similarity), similarity));
        }
        scored.sort(Comparator.<ScoredMatch>comparingDouble(m -> m.score).reversed()
                .thenComparing(m -> m.result.getChunkId()));
        List<RagSearchResult> matches = new ArrayList<>();
        for (int i = 0; i < Math.min(query.getTopK(), scored.size()); i++) {
            matches.add(scored.get(i).result);
        }
        return matches;
    }

    // ==================== 私有方法 ====================

    private RagSearchResult toResult(RedisSearchTemplate.Hit hit, double score) {
        RagSearchResult result = new RagSearchResult();
        result.setKnowledgeBaseId(hit.field("knowledge_base_id"));
        result.setDocumentId(hit.field("document_id"));
        result.setDocumentVersion(hit.fieldLong("document_version", 0));
        result.setChunkId(hit.field("chunk_id"));
        result.setSequence(hit.fieldInt("chunk_seq", 0));
        result.setContent(hit.field("content"));
        result.setScore(score);
        result.setMetadata(parseMetadata(hit.field("metadata")));
        return result;
    }

    private Map<String, String> parseMetadata(String value) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (value == null) {
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
            log.warn("解析 RAG 索引元数据失败，按空处理: {}", value);
        }
        return metadata;
    }

    /** metadata filters 应用层精确匹配（与旧 MySQL BLOB 实现的 JSON 键值语义一致）。 */
    private boolean matchFilters(String metadataJson, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        Map<String, String> metadata = parseMetadata(metadataJson);
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            if (filter.getKey() == null || filter.getKey().isEmpty()) {
                continue;
            }
            if (!filter.getValue().equals(metadata.get(filter.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** knowledgeBaseIds 转 Redis TAG OR 过滤子句（空列表不过滤，检索全部知识库）。 */
    private String kbFilter(List<String> knowledgeBaseIds) {
        if (knowledgeBaseIds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("@knowledge_base_id:{");
        for (int i = 0; i < knowledgeBaseIds.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(escapeTag(RagFileSupport.requireId("knowledgeBaseId", knowledgeBaseIds.get(i))));
        }
        return sb.append('}').toString();
    }

    private void deleteRedisEntries(String knowledgeBaseId, String documentId) {
        String query = "@knowledge_base_id:{" + escapeTag(knowledgeBaseId) + "}"
                + " @document_id:{" + escapeTag(documentId) + "}";
        List<String> keys = template.keysByQuery(template.index("rag"), query, 1000);
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys.toArray(new String[0]));
        }
    }

    private String entryKey(String knowledgeBaseId, String documentId, int sequence) {
        return template.entryPrefix("rag") + knowledgeBaseId + ":" + documentId + ":" + sequence;
    }

    private void ensureIndex(int dimensions) {
        long now = System.currentTimeMillis();
        if (indexDimensions == dimensions && now - lastIndexConfirmedAt < INDEX_CONFIRM_TTL) {
            return;
        }
        synchronized (this) {
            if (indexDimensions == dimensions
                    && System.currentTimeMillis() - lastIndexConfirmedAt < INDEX_CONFIRM_TTL) {
                return;
            }
            String index = template.index("rag");
            if (template.indexExists(index)) {
                // 索引已存在（含重启后 Redis 保留）：读取实际维度并对齐，否则后续
                // HSET 会因维度不匹配而跳过 embedding 写入，导致文档无法被 KNN 检索到。
                int existing = template.indexDimensions(index);
                indexDimensions = existing > 0 ? existing : dimensions;
                indexInitialized = true;
                lastIndexConfirmedAt = System.currentTimeMillis();
                if (existing > 0 && existing != dimensions) {
                    log.warn("RAG Redis 索引已存在（维度 {}），与当前向量维度 {} 不一致，跳过向量写入",
                            existing, dimensions);
                }
                return;
            }
            // 索引不存在（首次创建 / 外部删除或清空 Redis）：重建。
            // FT.CREATE 会接管 PREFIX 下已写入的 entry（含此前"孤儿"数据），实现自愈。
            indexInitialized = false;
            indexDimensions = 0;
            template.createIndex(index, template.entryPrefix("rag"),
                    "knowledge_base_id TAG document_id TAG chunk_seq NUMERIC content TEXT metadata TEXT"
                            + " embedding VECTOR FLAT 6 TYPE FLOAT32 DIM " + dimensions
                            + " DISTANCE_METRIC COSINE");
            indexDimensions = dimensions;
            indexInitialized = true;
            lastIndexConfirmedAt = System.currentTimeMillis();
            log.info("创建 RAG Redis 索引: {}（维度 {}）", index, dimensions);
        }
    }

    private void ensureTable() {
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
                    + "metadata TEXT NOT NULL, "
                    + "PRIMARY KEY (chunk_id))";
            jdbc.execute(ddl);
            try {
                jdbc.execute("CREATE INDEX IF NOT EXISTS " + table + "_kb_idx"
                        + " ON " + table + " (knowledge_base_id)");
            } catch (RuntimeException e) {
                log.warn("创建 RAG 索引知识库索引失败（可继续全表扫描检索）: {}", e.getMessage());
            }
            tableReady = true;
        }
    }

    private void ensureTableIfPossible() {
        if (tableReady) {
            return;
        }
        try {
            jdbc.queryForObject("SELECT 1 FROM " + table + " LIMIT 1", Integer.class);
            tableReady = true;
        } catch (RuntimeException e) {
            // 表不存在，保持未就绪；search/delete 直接返回空即可
        }
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

    private static String escapeTag(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == ',' || c == '.' || c == '_' || c == '-' || c == '|' || c == '(' || c == ')'
                    || c == '{' || c == '}' || c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static final class ScoredMatch {
        final RagSearchResult result;
        final double score;

        ScoredMatch(RagSearchResult result, double score) {
            this.result = result;
            this.score = score;
        }
    }
}
