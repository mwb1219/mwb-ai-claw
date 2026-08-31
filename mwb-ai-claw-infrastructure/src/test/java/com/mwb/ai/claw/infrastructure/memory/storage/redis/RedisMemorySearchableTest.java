package com.mwb.ai.claw.infrastructure.memory.storage.redis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.redis.RedisSearchTemplate;

/**
 * RedisMemorySearchable 单测：关键词全文 / 向量 KNN 的查询构造与结果解析。
 */
public class RedisMemorySearchableTest {

    private RedisConnection connection;
    private StringRedisTemplate redis;
    private RedisMemorySearchable searchable;

    @Before
    public void setUp() {
        connection = mock(RedisConnection.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });
        searchable = new RedisMemorySearchable(new RedisSearchTemplate(redis, "claw"));
    }

    @Test
    public void searchFactsBuildsQueryWithPageTypeAndTerms() {
        when(connection.execute(eq("FT.SEARCH"), any())).thenReturn(factHits());

        List<MemoryPage> pages = searchable.searchFacts(AgentScope.defaultScope(), Arrays.asList("java", "语言"), 3);

        assertEquals(1, pages.size());
        MemoryPage page = pages.get(0);
        assertEquals("fact-用户偏好-语言", page.getPageId());
        assertEquals(MemoryPage.PageType.FACT, page.getType());
        assertEquals("用户偏好-语言", page.getKey());
        assertTrue(page.getContent().contains("喜欢 Java"));
        assertEquals(0.8, page.getImportance(), 1e-6);
    }

    @Test
    public void searchFactsAppliesScopeTagFilter() {
        when(connection.execute(eq("FT.SEARCH"), any())).thenReturn(factHits());

        searchable.searchFacts(AgentScope.of("tenant-1", "user-1"), Arrays.asList("java"), 3);

        verify(connection).execute(eq("FT.SEARCH"), any());
    }

    @Test
    public void searchPagesBuildsQueryForSummaryAndArchive() {
        List<Object> root = new ArrayList<>();
        root.add(1L);
        root.addAll(doc("claw:memory:entry:summary-3", "summary-3", "SUMMARY", "这是摘要内容", 0));
        when(connection.execute(eq("FT.SEARCH"), any())).thenReturn(root);

        List<MemoryPage> pages = searchable.searchPages(AgentScope.defaultScope(), Arrays.asList("摘要"), 2);

        assertEquals(1, pages.size());
        assertEquals("summary-3", pages.get(0).getPageId());
        assertEquals(MemoryPage.PageType.SUMMARY, pages.get(0).getType());
    }

    @Test
    public void searchByVectorConvertsDistanceToSimilarity() {
        List<Object> root = new ArrayList<>();
        root.add(1L);
        root.addAll(doc("claw:memory:entry:summary-3", "summary-3", "SUMMARY", "内容", 0.15));
        when(connection.execute(eq("FT.SEARCH"), any())).thenReturn(root);

        List<MemoryPage> pages = searchable.searchByVector(AgentScope.defaultScope(), new float[] {1, 0, 0}, 3);

        assertEquals(1, pages.size());
        assertEquals("summary-3", pages.get(0).getPageId());
    }

    // ==================== 工具 ====================

    /** RESP2 扁平格式：[count, key, [字段...], score]（KNN 带独立 score 元素）。 */
    private List<Object> factHits() {
        List<Object> root = new ArrayList<>();
        root.add(1L);
        root.add("claw:memory:entry:fact-用户偏好-语言");
        root.add(fields("fact-用户偏好-语言", "FACT", "s1", "用户偏好-语言", "用户喜欢 Java", 0.8));
        root.add(2.0);
        return root;
    }

    /** RESP2 扁平命中：[key, [字段...], score]。 */
    private List<Object> doc(String key, String pageId, String type, String content, double score) {
        List<Object> hit = new ArrayList<>();
        hit.add(key);
        hit.add(fields(pageId, type, "s1", "", content, 0.5));
        hit.add(score);
        return hit;
    }

    private List<Object> fields(String pageId, String type, String sessionId, String factKey,
                                String content, double importance) {
        List<Object> fields = new ArrayList<>();
        fields.add("page_id"); fields.add(pageId);
        fields.add("page_type"); fields.add(type);
        fields.add("session_id"); fields.add(sessionId);
        fields.add("fact_key"); fields.add(factKey);
        fields.add("content"); fields.add(content);
        fields.add("importance"); fields.add(importance);
        fields.add("block_start"); fields.add(0);
        fields.add("block_end"); fields.add(10);
        fields.add("token_count"); fields.add(5);
        fields.add("create_time"); fields.add(123456L);
        return fields;
    }
}
