package com.mwb.ai.claw.domain.memory.layered.retriever;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;
import com.mwb.ai.claw.domain.memory.layered.spi.MemoryPageStore;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySearchable;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 关键词记忆检索器测试：存储实现 MemorySearchable（db）时 SQL 下推优先，
 * 否则（file）回退全量加载 + 内存打分（原有行为）。
 */
public class KeywordMemoryRetrieverTest {

    @Test
    public void searchUsesSqlPushdownWhenStoreIsMemorySearchable() {
        MemorySearchableStore store = new MemorySearchableStore();
        KeywordMemoryRetriever retriever = new KeywordMemoryRetriever(store);

        List<MemoryPage> result = retriever.search(scope(), "用户偏好", 3);

        assertEquals(2, result.size());
        assertEquals("fact-用户偏好-语言", result.get(0).getPageId());
        assertEquals("summary-1", result.get(1).getPageId());
        // 下推路径：命中事实 + 记忆页，未触发全量加载
        assertTrue(store.searchFactsCalled);
        assertTrue(store.searchPagesCalled);
        assertTrue(store.fullLoadCalled.isEmpty());
    }

    @Test
    public void searchFallsBackToFullLoadWhenStoreIsPlainMemoryPageStore() {
        MemoryPageStore store = mock(MemoryPageStore.class);
        when(store.loadFacts(any())).thenReturn(Arrays.asList(fact()));
        when(store.listAllSummaries(any())).thenReturn(Arrays.asList(summary()));
        when(store.listAllArchive(any())).thenReturn(new ArrayList<>());
        KeywordMemoryRetriever retriever = new KeywordMemoryRetriever(store);

        List<MemoryPage> result = retriever.search(scope(), "用户偏好", 3);

        // file 模式：全量加载 + 内存打分命中
        assertEquals(2, result.size());
        verify(store).loadFacts(any());
        verify(store).listAllSummaries(any());
        verify(store).listAllArchive(any());
    }

    @Test
    public void emptyQueryReturnsEmptyWithoutTouchingStore() {
        MemorySearchableStore store = new MemorySearchableStore();
        KeywordMemoryRetriever retriever = new KeywordMemoryRetriever(store);

        assertTrue(retriever.search(scope(), "   ", 3).isEmpty());
        assertFalse(store.searchFactsCalled);
        assertFalse(store.searchPagesCalled);
    }

    private AgentScope scope() {
        return AgentScope.of("tenant-1", "user-1");
    }

    private MemoryPage fact() {
        return MemoryPage.fact("用户偏好-语言", "用户偏好使用 Java", 0.8, "s1");
    }

    private MemoryPage summary() {
        return MemoryPage.summary("summary-1", "用户偏好的历史摘要", "s1", 0, 10, 12);
    }

    /** 实现 MemorySearchable 的存储桩：记录调用、返回固定命中。 */
    private static final class MemorySearchableStore implements MemoryPageStore, MemorySearchable {

        boolean searchFactsCalled;
        boolean searchPagesCalled;
        final List<String> fullLoadCalled = new ArrayList<>();

        @Override
        public List<MemoryPage> searchFacts(AgentScope scope, List<String> terms, int topK) {
            searchFactsCalled = true;
            return Arrays.asList(fact());
        }

        @Override
        public List<MemoryPage> searchPages(AgentScope scope, List<String> terms, int topK) {
            searchPagesCalled = true;
            return Arrays.asList(summary());
        }

        @Override
        public List<MemoryPage> searchByVector(AgentScope scope, float[] queryVector, int topK) {
            return new ArrayList<>();
        }

        private static MemoryPage fact() {
            return MemoryPage.fact("用户偏好-语言", "用户偏好使用 Java", 0.8, "s1");
        }

        private static MemoryPage summary() {
            return MemoryPage.summary("summary-1", "用户偏好的历史摘要", "s1", 0, 10, 12);
        }

        @Override
        public void saveSummary(AgentScope scope, MemoryPage page) {
            fullLoadCalled.add("saveSummary");
        }

        @Override
        public List<MemoryPage> loadSummaries(AgentScope scope, String sessionId) {
            fullLoadCalled.add("loadSummaries");
            return new ArrayList<>();
        }

        @Override
        public List<MemoryPage> listAllSummaries(AgentScope scope) {
            fullLoadCalled.add("listAllSummaries");
            return new ArrayList<>();
        }

        @Override
        public void appendFact(AgentScope scope, MemoryPage fact) {
            fullLoadCalled.add("appendFact");
        }

        @Override
        public List<MemoryPage> loadFacts(AgentScope scope) {
            fullLoadCalled.add("loadFacts");
            return new ArrayList<>();
        }

        @Override
        public void deleteFact(AgentScope scope, String key) {
            fullLoadCalled.add("deleteFact");
        }

        @Override
        public void deleteSessionPages(AgentScope scope, String sessionId) {
            fullLoadCalled.add("deleteSessionPages");
        }

        @Override
        public void saveArchive(AgentScope scope, MemoryPage page) {
            fullLoadCalled.add("saveArchive");
        }

        @Override
        public List<MemoryPage> loadArchive(AgentScope scope, String sessionId) {
            fullLoadCalled.add("loadArchive");
            return new ArrayList<>();
        }

        @Override
        public List<MemoryPage> listAllArchive(AgentScope scope) {
            fullLoadCalled.add("listAllArchive");
            return new ArrayList<>();
        }

        @Override
        public void deleteSessionArchive(AgentScope scope, String sessionId) {
            fullLoadCalled.add("deleteSessionArchive");
        }
    }
}
