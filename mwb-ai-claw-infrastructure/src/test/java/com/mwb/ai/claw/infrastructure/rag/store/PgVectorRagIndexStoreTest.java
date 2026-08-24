package com.mwb.ai.claw.infrastructure.rag.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.model.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.model.RagVectorQuery;

/**
 * PGVector 索引存储测试：验证建表 DDL、先删后插、检索 SQL 与结果映射、输入校验与标识符防注入。
 * （以 Mock JdbcTemplate 验证 SQL 生成与映射；真实 PostgreSQL + pgvector 联调见 T4 验证说明。）
 */
public class PgVectorRagIndexStoreTest {

    private JdbcTemplate jdbc;
    private PgVectorRagIndexStore store;

    @Before
    public void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new PgVectorRagIndexStore(jdbc, new RagConfig());
    }

    @Test
    public void upsertCreatesTableThenDeletesOldDocumentAndInsertsChunks() {
        List<RagIndexEntry> entries = Arrays.asList(
                entry("c1", "kb-1", "doc-1", 2L, 0, "alpha", new float[] {1, 0, 0}),
                entry("c2", "kb-1", "doc-1", 2L, 1, "beta", new float[] {0, 1, 0}));

        store.upsert(entries);

        // 建表与向量索引 DDL
        verify(jdbc).execute(contains("CREATE TABLE IF NOT EXISTS rag_index_entries"));
        verify(jdbc, times(2)).execute(contains("CREATE INDEX IF NOT EXISTS"));
        // 原子替换：先删旧文档块，再插入新块
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).update(sqlCaptor.capture(), (Object[]) any());
        List<String> sqls = sqlCaptor.getAllValues();
        assertEquals("DELETE FROM", sqls.get(0).substring(0, "DELETE FROM".length()));
        assertEquals(2, sqls.stream().filter(s -> s.startsWith("INSERT INTO")).count());
        assertEquals(1, sqls.stream().filter(s -> s.startsWith("DELETE FROM")).count());
    }

    @Test
    public void upsertRejectsEmptyEntries() {
        try {
            store.upsert(new ArrayList<>());
            fail("空记录应被拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("不能为空"));
        }
    }

    @Test
    public void upsertRejectsMismatchedVectorDimensions() {
        List<RagIndexEntry> entries = Arrays.asList(
                entry("c1", "kb-1", "doc-1", 1L, 0, "a", new float[] {1, 0, 0}),
                entry("c2", "kb-1", "doc-1", 1L, 1, "b", new float[] {1, 0}));
        try {
            store.upsert(entries);
            fail("维度不一致应被拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("维度不一致"));
        }
    }

    @Test
    public void searchMapsRowsAppliesMinScoreAndSortsByScoreDesc() {
        RagVectorQuery query = new RagVectorQuery();
        query.setKnowledgeBaseIds(Arrays.asList("kb-1", "kb-2"));
        query.setVector(new float[] {1, 0, 0});
        query.setTopK(10);
        query.setMinScore(0.5);
        query.getFilters().put("lang", "zh");

        // 距离 0.1 → 分 0.9；0.8 → 分 0.2（低于阈值剔除）；0.4 → 分 0.6
        when(jdbc.queryForList(anyString(), (Object[]) any()))
                .thenReturn(Arrays.asList(row("c1", 0.1), row("c2", 0.8), row("c3", 0.4)));

        List<RagSearchResult> results = store.search(query);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).getChunkId());
        assertEquals(0.9, results.get(0).getScore(), 1e-9);
        assertEquals("c3", results.get(1).getChunkId());
        assertEquals("content-c3", results.get(1).getContent());
        assertEquals("zh", results.get(1).getMetadata().get("lang"));

        // 检索 SQL 携带知识库范围、过滤条件与向量排序
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), (Object[]) any());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("knowledge_base_id IN (?, ?)"));
        assertTrue(sql.contains("metadata->>?"));
        assertTrue(sql.contains("ORDER BY embedding <-> ?::vector LIMIT ?"));
    }

    @Test
    public void searchRequiresKnowledgeBaseIds() {
        RagVectorQuery query = new RagVectorQuery();
        query.setVector(new float[] {1, 0, 0});
        query.setTopK(5);
        try {
            store.search(query);
            fail("未指定知识库应被拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("必须显式指定 knowledgeBaseIds"));
        }
    }

    @Test
    public void searchWithEmptyVectorReturnsEmptyWithoutTouchingDatabase() {
        RagVectorQuery query = new RagVectorQuery();
        query.setKnowledgeBaseIds(Arrays.asList("kb-1"));
        query.setVector(new float[0]);
        query.setTopK(5);

        assertTrue(store.search(query).isEmpty());
        verify(jdbc, times(0)).queryForList(anyString(), (Object[]) any());
    }

    @Test
    public void deleteByDocumentIssuesDeleteSql() {
        store.deleteByDocument("kb-1", "doc-1");
        verify(jdbc).update(contains("DELETE FROM rag_index_entries"), eq("kb-1"), eq("doc-1"));
    }

    @Test
    public void rejectsSqlInjectionInIdentifiers() {
        RagConfig config = new RagConfig();
        config.getPgvector().setTable("rag_entries; DROP TABLE x");
        try {
            new PgVectorRagIndexStore(jdbc, config);
            fail("非法表名应被拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("仅允许字母、数字、下划线与点"));
        }
    }

    private RagIndexEntry entry(String chunkId, String kb, String doc, long version,
                                int seq, String content, float[] vector) {
        RagIndexEntry entry = new RagIndexEntry();
        entry.setChunkId(chunkId);
        entry.setKnowledgeBaseId(kb);
        entry.setDocumentId(doc);
        entry.setDocumentVersion(version);
        entry.setSequence(seq);
        entry.setContent(content);
        entry.setMetadata(new LinkedHashMap<>());
        entry.setVector(vector);
        entry.setEmbeddingModel("model-1");
        entry.setDimensions(vector.length);
        return entry;
    }

    private Map<String, Object> row(String chunkId, double distance) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chunk_id", chunkId);
        row.put("knowledge_base_id", "kb-1");
        row.put("document_id", "doc-1");
        row.put("document_version", 1L);
        row.put("sequence", 0);
        row.put("content", "content-" + chunkId);
        row.put("metadata", "{\"lang\":\"zh\"}");
        row.put("embedding_model", "model-1");
        row.put("dimensions", 3);
        row.put("distance", distance);
        return row;
    }
}
