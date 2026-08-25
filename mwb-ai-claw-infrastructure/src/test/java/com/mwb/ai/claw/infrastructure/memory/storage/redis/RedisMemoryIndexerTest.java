package com.mwb.ai.claw.infrastructure.memory.storage.redis;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.mwb.ai.claw.domain.llm.EmbeddingGateway;
import com.mwb.ai.claw.domain.memory.model.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.model.MemoryPage;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.redis.RedisSearchTemplate;

/**
 * RedisMemoryIndexer 单测：MySQL 写入后的 Redis 双写（含向量）、删除与反查删除。
 */
public class RedisMemoryIndexerTest {

    private RedisConnection connection;
    private StringRedisTemplate redis;
    private EmbeddingGateway embedding;
    private LayeredMemoryConfig config;
    private RedisMemoryIndexer indexer;

    @Before
    public void setUp() {
        connection = mock(RedisConnection.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });
        embedding = mock(EmbeddingGateway.class);
        config = new LayeredMemoryConfig();
        config.setVectorEnabled(true);
        when(embedding.embed(any())).thenReturn(new float[] {1, 0, 0});
        when(connection.execute(eq("FT.INFO"), any())).thenReturn(null);
        when(connection.execute(eq("FT.CREATE"), any())).thenReturn("OK");
        when(connection.execute(eq("HSET"), any())).thenReturn(1L);
        when(connection.execute(eq("DEL"), any())).thenReturn(1L);
        indexer = new RedisMemoryIndexer(new RedisSearchTemplate(redis, "claw"), embedding, config);
    }

    @Test
    public void upsertFactWritesHashWithVectorAndCreatesIndex() {
        indexer.upsertFact(AgentScope.of("t1", "u1"), MemoryPage.fact("用户偏好-语言", "喜欢 Java", 0.8, "s1"));

        // 首次写入：FT.INFO 探测不存在 → FT.CREATE 建索引 + HSET 条目
        verify(connection).execute(eq("FT.CREATE"), any());
        verify(connection).execute(eq("HSET"), any());
        verify(embedding).embed("喜欢 Java");
    }

    @Test
    public void upsertPageSkipsVectorWhenDisabled() {
        config.setVectorEnabled(false);

        indexer.upsertPage(AgentScope.defaultScope(),
                MemoryPage.summary("summary-3", "摘要内容", "s1", 0, 10, 5));

        verify(connection, never()).execute(eq("FT.CREATE"), any());
        verify(connection).execute(eq("HSET"), any());
    }

    @Test
    public void deleteFactDeletesEntry() {
        indexer.deleteFact(AgentScope.defaultScope(), "用户偏好-语言");

        verify(connection).execute(eq("DEL"), any());
    }

    @Test
    public void deleteSessionPagesQueriesThenDeletes() {
        List<Object> root = new ArrayList<>();
        root.add(1L);
        List<Object> doc = new ArrayList<>();
        doc.add("claw:memory:entry:summary-3");
        doc.add(new ArrayList<>());
        root.add(doc);
        when(connection.execute(eq("FT.SEARCH"), any())).thenReturn(root);

        indexer.deleteSessionPages(AgentScope.defaultScope(), "s1");

        verify(connection).execute(eq("FT.SEARCH"), any());
        verify(connection).execute(eq("DEL"), any());
    }
}
