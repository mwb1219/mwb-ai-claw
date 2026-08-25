package com.mwb.ai.claw.infrastructure.rag.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.rag.model.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.model.RagVectorQuery;
import com.mwb.ai.claw.infrastructure.redis.RedisSearchTemplate;

/**
 * RedisRagIndexStore 单测：MySQL 文本存储 + Redis 索引双写、双侧删除、KNN 检索过滤。
 */
public class RedisRagIndexStoreTest {

    private JdbcTemplate jdbc;
    private RedisConnection connection;
    private StringRedisTemplate redis;
    private RedisRagIndexStore store;

    /** FT.SEARCH 返回体中的 Hash 字段是否携带向量。 */
    private boolean withEmbedding;

    @Before
    public void setUp() {
        jdbc = mock(JdbcTemplate.class);
        connection = mock(RedisConnection.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });
        // FT.INFO 默认返回 null（索引不存在）
        when(connection.execute(eq("FT.INFO"), any())).thenReturn(null);
        // 默认 HSET / DEL / FT.CREATE 返回简单值
        when(connection.execute(eq("HSET"), any())).thenReturn(1L);
        when(connection.execute(eq("DEL"), any())).thenReturn(1L);
        when(connection.execute(eq("FT.CREATE"), any())).thenReturn("OK");
        withEmbedding = true;
        store = new RedisRagIndexStore(jdbc, new RedisSearchTemplate(redis, "claw"));
    }

    // ==================== 写入 ====================

    @Test
    public void upsertWritesMysqlAndRedisIndex() {
        store.upsert(Arrays.asList(entry("kb-1", "doc-1", "c1", 0)));

        // MySQL 权威：先删所属文档、再逐条插入（无 embedding 列）
        verify(jdbc).update(contains("DELETE FROM rag_index_entries"), eq("kb-1"), eq("doc-1"));
        verify(jdbc).update(contains("INSERT INTO rag_index_entries"),
                eq("c1"), eq("kb-1"), eq("doc-1"), eq(1L), eq(0), eq("text1"),
                contains("\"lang\":\"java\""));
        // Redis 派生：首次写入建索引 + HSET 条目（含向量字段）
        verify(connection).execute(eq("FT.CREATE"), any());
        verify(connection).execute(eq("HSET"), any());
    }

    @Test
    public void upsertSkipsVectorWhenIndexExistsWithOtherDimension() {
        // 第一次写入：索引不存在（FT.INFO 默认 null）→ 创建索引（FT.CREATE 1 次）
        store.upsert(Arrays.asList(entry("kb-1", "doc-1", "c1", 0)));
        // 第二次：模拟索引已存在但维度不同（3 维 vs 当前 4 维）→ 不重建、HSET 不含 embedding
        List<Object> attr = new ArrayList<>();
        attr.add("identifier"); attr.add("embedding");
        attr.add("attribute"); attr.add("embedding");
        attr.add("type"); attr.add("VECTOR");
        attr.add("dim"); attr.add(3L);
        List<Object> attributes = new ArrayList<>();
        attributes.add(attr);
        List<Object> info = new ArrayList<>();
        info.add("attributes"); info.add(attributes);
        when(connection.execute(eq("FT.INFO"), any())).thenReturn(info);

        withEmbedding = false;
        store.upsert(Arrays.asList(entry("kb-1", "doc-1", "c2", 0)));

        verify(connection, times(1)).execute(eq("FT.CREATE"), any());
        verify(connection, times(2)).execute(eq("HSET"), any());
    }

    // ==================== 删除 ====================

    @Test
    public void deleteByDocumentRemovesMysqlAndRedis() {
        List<Object> root = new ArrayList<>();
        root.add(1L);
        List<Object> doc = new ArrayList<>();
        doc.add("claw:rag:entry:kb-1:doc-1:0");
        doc.add(new ArrayList<>());
        root.add(doc);
        when(connection.execute(eq("FT.SEARCH"), any())).thenReturn(root);

        store.deleteByDocument("kb-1", "doc-1");

        verify(jdbc).update(contains("DELETE FROM rag_index_entries"), eq("kb-1"), eq("doc-1"));
        verify(connection).execute(eq("DEL"), any());
    }

    // ==================== 检索 ====================

    @Test
    public void searchFiltersKbMinScoreAndMetadata() {
        when(connection.execute(eq("FT.SEARCH"), any())).thenReturn(knnResult());

        RagVectorQuery query = new RagVectorQuery();
        query.setKnowledgeBaseIds(Arrays.asList("kb-1"));
        query.setVector(new float[] {1, 0, 0});
        query.setTopK(5);
        query.setMinScore(0.5);
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("lang", "java");
        query.setFilters(filters);

        List<RagSearchResult> result = store.search(query);

        // kb-2 命中被过滤；kb-1 中 lang=python 被 filters 过滤；仅 java 命中
        assertEquals(1, result.size());
        RagSearchResult hit = result.get(0);
        assertEquals("c1", hit.getChunkId());
        assertEquals("kb-1", hit.getKnowledgeBaseId());
        assertEquals(0.9, hit.getScore(), 1e-6);
    }

    @Test
    public void searchWithEmptyKnowledgeBaseIdsSearchesAll() {
        when(connection.execute(eq("FT.SEARCH"), any())).thenReturn(knnResult());

        RagVectorQuery query = new RagVectorQuery();
        query.setVector(new float[] {1, 0, 0});
        query.setTopK(5);
        query.setMinScore(0);

        List<RagSearchResult> result = store.search(query);

        // 无 kb 过滤：三条全部命中（score 0.9 / 0.8 / 0.8），按相似度降序
        assertEquals(3, result.size());
        assertEquals("c1", result.get(0).getChunkId());
    }

    // ==================== 工具 ====================

    private RagIndexEntry entry(String kb, String doc, String chunkId, int seq) {
        RagIndexEntry entry = new RagIndexEntry();
        entry.setChunkId(chunkId);
        entry.setKnowledgeBaseId(kb);
        entry.setDocumentId(doc);
        entry.setDocumentVersion(1);
        entry.setSequence(seq);
        entry.setContent("text1");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("lang", "java");
        entry.setMetadata(metadata);
        entry.setVector(withEmbedding ? new float[] {1, 0, 0} : new float[] {1, 0, 0, 0});
        entry.setEmbeddingModel("test");
        entry.setDimensions(3);
        return entry;
    }

    /** 构造三条 KNN 命中（RESP2 扁平格式：[count, key, [字段], score, ...]）：
     *  kb-1/java、kb-1/python、kb-2/java。 */
    private List<Object> knnResult() {
        List<Object> root = new ArrayList<>();
        root.add(3L);
        root.addAll(doc("claw:rag:entry:kb-1:doc-1:0", "c1", "kb-1", "doc-1", "java", 0.1));
        root.addAll(doc("claw:rag:entry:kb-1:doc-2:0", "c2", "kb-1", "doc-2", "python", 0.2));
        root.addAll(doc("claw:rag:entry:kb-2:doc-1:0", "c3", "kb-2", "doc-1", "java", 0.2));
        return root;
    }

    /** RESP2 扁平命中：[key, [字段...], score]。 */
    private List<Object> doc(String key, String chunkId, String kb, String doc, String lang, double score) {
        List<Object> hit = new ArrayList<>();
        hit.add(key);
        List<Object> fields = new ArrayList<>();
        fields.add("chunk_id"); fields.add(chunkId);
        fields.add("knowledge_base_id"); fields.add(kb);
        fields.add("document_id"); fields.add(doc);
        fields.add("document_version"); fields.add(1L);
        fields.add("chunk_seq"); fields.add(0);
        fields.add("content"); fields.add("text");
        fields.add("metadata"); fields.add("{\"lang\":\"" + lang + "\"}");
        hit.add(fields);
        hit.add(score);
        return hit;
    }
}
