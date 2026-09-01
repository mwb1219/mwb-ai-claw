package com.mwb.ai.claw.infrastructure;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;
import com.mwb.ai.claw.domain.rag.access.RagAccessPolicy;
import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.model.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.model.RagVectorQuery;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.memory.storage.file.FileBasedSessionGateway;
import com.mwb.ai.claw.infrastructure.memory.storage.file.FileMemoryPageStore;
import com.mwb.ai.claw.infrastructure.rag.access.AllowAllRagAccessPolicy;
import com.mwb.ai.claw.infrastructure.rag.store.LocalRagIndexStore;

/**
 * 多租户隔离集成测试（T8）：
 * <ul>
 *   <li>会话：按 tenant/user 命名空间隔离，跨租户 / 跨用户读取返回 null；</li>
 *   <li>记忆：摘要页 / 事实按命名空间隔离，越权读取为空；</li>
 *   <li>RAG：存储层全局共享（不读取 AgentScope），访问控制由 {@link RagAccessPolicy} 在 API 层负责。</li>
 * </ul>
 */
public class MultiTenantIsolationTest {

    private static final float[] V = new float[] {1F, 0F, 0F};

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ==================== 会话隔离 ====================

    @Test
    public void session_isIsolatedByTenantAndUser() throws Exception {
        FileBasedSessionGateway gateway = new FileBasedSessionGateway(properties());
        gateway.init();

        Session s = new Session();
        s.setSessionId("s1");
        s.setTenantId("tenant-a");
        s.setUserId("u1");
        s.setTitle("a-session");
        gateway.saveSession(s);

        // 本租户本用户可读
        assertNotNull(gateway.getSession(AgentScope.of("tenant-a", "u1"), "s1"));
        // 跨租户越权读取返回 null
        assertNull(gateway.getSession(AgentScope.of("tenant-b", "u1"), "s1"));
        // 跨用户越权读取返回 null
        assertNull(gateway.getSession(AgentScope.of("tenant-a", "u2"), "s1"));
        // 默认空间（legacy）越权读取返回 null
        assertNull(gateway.getSession(AgentScope.defaultScope(), "s1"));

        // 会话列表按命名空间过滤
        assertEquals(1, gateway.listSessions(AgentScope.of("tenant-a", "u1")).size());
        assertEquals(0, gateway.listSessions(AgentScope.of("tenant-b", "u1")).size());
        assertEquals(0, gateway.listSessions(AgentScope.of("tenant-a", "u2")).size());
    }

    // ==================== 记忆隔离 ====================

    @Test
    public void memory_isIsolatedByTenant() {
        FileMemoryPageStore store = new FileMemoryPageStore(properties());
        store.init();
        AgentScope tenantA = AgentScope.of("tenant-a", "u1");
        AgentScope tenantB = AgentScope.of("tenant-b", "u1");

        store.saveSummary(tenantA, MemoryPage.summary("p1", "a-summary", "s1", 0, 5, 10));
        store.appendFact(tenantA, MemoryPage.fact("pref-lang", "中文", 0.9D, "s1"));

        // 本租户可见
        assertFalse(store.loadSummaries(tenantA, "s1").isEmpty());
        assertFalse(store.loadFacts(tenantA).isEmpty());
        // 跨租户越权读取为空
        assertTrue(store.loadSummaries(tenantB, "s1").isEmpty());
        assertTrue(store.loadFacts(tenantB).isEmpty());
        assertTrue(store.listAllSummaries(tenantB).isEmpty());
    }

    // ==================== RAG：全局共享 + API 层访问控制 ====================

    @Test
    public void rag_storageIsGloballyShared_acrossTenants() {
        LocalRagIndexStore store = ragIndexStore();
        store.init();

        // 两个不同"租户"的文档写入同一知识库（RAG 写入层不读取 scope）
        store.upsert(Collections.singletonList(ragEntry("kb-shared", "doc-a", "alpha from A")));
        store.upsert(Collections.singletonList(ragEntry("kb-shared", "doc-b", "alpha from B")));

        // 检索同一知识库返回全部匹配，不按租户隔离（框架硬约束：RAG 全局共享）
        List<RagSearchResult> results = search(store, "kb-shared", 10);
        assertEquals(2, results.size());
    }

    @Test
    public void rag_accessPolicyDeniesCrossTenantAccess() {
        // 默认策略：全部放行（全局共享语义）
        RagAccessPolicy allowAll = new AllowAllRagAccessPolicy();
        assertTrue(allowAll.canAccess("tenant-b", "u1", "kb-shared", RagAccessPolicy.Action.READ));

        // 接入方自定义策略：仅允许 tenant-a 访问，实现越权访问拒绝
        RagAccessPolicy tenantScoped = (tenantId, userId, kb, action) -> "tenant-a".equals(tenantId);
        assertTrue(tenantScoped.canAccess("tenant-a", "u1", "kb-shared", RagAccessPolicy.Action.READ));
        assertFalse("跨租户读取应被拒绝", tenantScoped.canAccess("tenant-b", "u1", "kb-shared",
                RagAccessPolicy.Action.READ));
        assertFalse("跨租户写入应被拒绝", tenantScoped.canAccess("tenant-b", "u1", "kb-shared",
                RagAccessPolicy.Action.WRITE));
    }

    // ==================== 工具方法 ====================

    private AgentProperties properties() {
        AgentProperties props = new AgentProperties();
        props.setMemoryDir(tmp.getRoot().getAbsolutePath());
        return props;
    }

    private LocalRagIndexStore ragIndexStore() {
        RagConfig config = new RagConfig();
        config.getLocal().setDir(tmp.getRoot().getAbsolutePath());
        return new LocalRagIndexStore(config);
    }

    private RagIndexEntry ragEntry(String kb, String doc, String content) {
        RagIndexEntry e = new RagIndexEntry();
        e.setKnowledgeBaseId(kb);
        e.setDocumentId(doc);
        e.setDocumentVersion(1L);
        e.setChunkId(doc + "-chunk");
        e.setSequence(0);
        e.setContent(content);
        e.setVector(V);
        e.setEmbeddingModel("test-embedding");
        e.setDimensions(3);
        return e;
    }

    private List<RagSearchResult> search(LocalRagIndexStore store, String kb, int topK) {
        RagVectorQuery q = new RagVectorQuery();
        q.setKnowledgeBaseIds(Collections.singletonList(kb));
        q.setVector(V);
        q.setDimensions(3);
        q.setTopK(topK);
        q.setMinScore(0D);
        return store.search(q);
    }
}