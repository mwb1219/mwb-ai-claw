package com.mwb.ai.claw.infrastructure.rag.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.mwb.ai.claw.domain.rag.model.RagDocument;

/**
 * JDBC 版 RAG 文档存储测试：count→update/insert 保存、查询映射、删除与列表。
 */
public class JdbcRagDocumentStoreTest {

    private JdbcTemplate jdbc;
    private JdbcRagDocumentStore store;

    @Before
    public void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new JdbcRagDocumentStore(jdbc);
    }

    @Test
    public void saveInsertsNewDocumentWhenNotExists() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), ArgumentMatchers.<Object>any()))
                .thenReturn(0);

        store.save(document("kb-1", "doc-1"));

        // INSERT 共 13 个占位符（知识库+文档+11 个业务字段）
        verify(jdbc).update(contains("INSERT INTO claw_rag_document"), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void saveUpdatesExistingDocument() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), ArgumentMatchers.<Object>any()))
                .thenReturn(1);

        store.save(document("kb-1", "doc-1"));

        // UPDATE：10 个 SET 占位符 + 2 个 WHERE 占位符
        verify(jdbc).update(contains("UPDATE claw_rag_document"), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void findReturnsNullWhenMissing() throws SQLException {
        when(jdbc.query(anyString(), any(RowMapper.class), eq("kb-1"), eq("doc-1")))
                .thenReturn(Arrays.asList());

        assertNull(store.find("kb-1", "doc-1"));
    }

    @Test
    public void findMapsRowToDocument() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("knowledge_base_id")).thenReturn("kb-1");
        when(rs.getString("document_id")).thenReturn("doc-1");
        when(rs.getString("name")).thenReturn("a.txt");
        when(rs.getString("content_type")).thenReturn("text/plain");
        when(rs.getString("checksum")).thenReturn("abc");
        when(rs.getLong("version")).thenReturn(2L);
        when(rs.getInt("chunk_count")).thenReturn(3);
        when(rs.getString("status")).thenReturn("READY");
        when(rs.getString("source_content")).thenReturn("hello");
        when(rs.getString("last_error")).thenReturn(null);
        when(rs.getString("metadata")).thenReturn("{\"lang\":\"zh\"}");
        when(rs.getLong("create_time")).thenReturn(1L);
        when(rs.getLong("update_time")).thenReturn(2L);

        when(jdbc.query(anyString(), any(RowMapper.class), eq("kb-1"), eq("doc-1"))).thenAnswer(invocation -> {
            RowMapper<RagDocument> captured = invocation.getArgument(1);
            return Arrays.asList(captured.mapRow(rs, 0));
        });

        RagDocument document = store.find("kb-1", "doc-1");

        assertEquals("doc-1", document.getDocumentId());
        assertEquals("a.txt", document.getName());
        assertEquals(2L, document.getVersion());
        assertEquals(3, document.getChunkCount());
        assertEquals(RagDocument.Status.READY, document.getStatus());
        assertEquals("hello", document.getSourceContent());
        assertEquals("zh", document.getMetadata().get("lang"));
    }

    @Test
    public void deleteIssuesDeleteSql() {
        store.delete("kb-1", "doc-1");
        verify(jdbc).update(contains("DELETE FROM claw_rag_document"), eq("kb-1"), eq("doc-1"));
    }

    @Test
    public void listFiltersByKnowledgeBase() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq("kb-1")))
                .thenReturn(Arrays.asList());

        assertEquals(0, store.list("kb-1").size());
        verify(jdbc).query(contains("WHERE knowledge_base_id = ?"), any(RowMapper.class), eq("kb-1"));
    }

    private RagDocument document(String kb, String doc) {
        RagDocument document = new RagDocument();
        document.setKnowledgeBaseId(kb);
        document.setDocumentId(doc);
        document.setName("a.txt");
        document.setContentType("text/plain");
        document.setChecksum("abc");
        document.setVersion(1L);
        document.setChunkCount(2);
        document.setStatus(RagDocument.Status.READY);
        document.setSourceContent("hello world");
        document.setMetadata(new LinkedHashMap<>());
        document.setCreateTime(1L);
        return document;
    }
}
