package com.mwb.ai.claw.infrastructure.memory.synthesis;

import com.mwb.ai.claw.domain.memory.layered.spi.SynthesisCache;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * 提炼缓存单元测试：覆盖 LocalSynthesisCache（JVM 内存 LRU）与 RedisSynthesisCache（分布式 mock）。
 * <p>
 * 核心验证点：scope 隔离、容量 / 禁用行为、LRU 淘汰、Redis 前缀 / TTL / 序列化回读。
 */
public class SynthesisCacheTest {

    // ===================== LocalSynthesisCache =====================

    @Test
    public void localCache_disabledWhenCapacityZeroOrNegative() {
        SynthesisCache cache = new LocalSynthesisCache(propsWithCacheSize(0));
        assertFalse(cache.isEnabled());
        AgentScope scope = AgentScope.of("t1", "u1");
        cache.put(scope, "k1", "v1");
        assertNull(cache.get(scope, "k1"));
        assertEquals(0, cache.size());
    }

    @Test
    public void localCache_putGetAndScopeIsolation() {
        SynthesisCache cache = new LocalSynthesisCache(propsWithCacheSize(10));
        assertTrue(cache.isEnabled());
        AgentScope s1 = AgentScope.of("t1", "u1");
        AgentScope s2 = AgentScope.of("t1", "u2");
        cache.put(s1, "k1", "hello");
        cache.put(s2, "k1", "world");

        assertEquals("hello", cache.get(s1, "k1"));
        assertEquals("world", cache.get(s2, "k1"));
        // 跨 scope 不应互通
        assertNull(cache.get(AgentScope.of("t1", "u3"), "k1"));
        // null value 不写入
        cache.put(s1, "knull", null);
        assertNull(cache.get(s1, "knull"));
        assertEquals(2, cache.size());
    }

    @Test
    public void localCache_lruEvictionWhenExceedCapacity() {
        int cap = 3;
        SynthesisCache cache = new LocalSynthesisCache(propsWithCacheSize(cap));
        AgentScope scope = AgentScope.of("t", "u");
        cache.put(scope, "a", "1");
        cache.put(scope, "b", "2");
        cache.put(scope, "c", "3");
        assertEquals(cap, cache.size());
        // 触发访问 a，使 b 成为 LRU
        assertEquals("1", cache.get(scope, "a"));
        // 插入 d：容量=3，应淘汰 b
        cache.put(scope, "d", "4");
        assertEquals(cap, cache.size());
        assertNull(cache.get(scope, "b"));
        assertEquals("1", cache.get(scope, "a"));
        assertEquals("3", cache.get(scope, "c"));
        assertEquals("4", cache.get(scope, "d"));
    }

    @Test
    public void localCache_statsContainsHitRate() {
        SynthesisCache cache = new LocalSynthesisCache(propsWithCacheSize(10));
        AgentScope scope = AgentScope.of("t", "u");
        cache.put(scope, "a", "1");
        cache.get(scope, "a"); // hit
        cache.get(scope, "a"); // hit
        cache.get(scope, "miss"); // miss
        Map<String, Object> stats = cache.stats();
        assertEquals("local", stats.get("type"));
        assertEquals(2L, stats.get("hits"));
        assertEquals(1L, stats.get("misses"));
        assertNotNull(stats.get("hitRate"));
    }

    // ===================== RedisSynthesisCache =====================

    @Test
    @SuppressWarnings("unchecked")
    public void redisCache_disabledWhenCapacityZero() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        SynthesisCache cache = new RedisSynthesisCache(template, propsWithCacheSize(0));
        assertFalse(cache.isEnabled());
        AgentScope scope = AgentScope.of("t", "u");
        cache.put(scope, "k", "v");
        assertNull(cache.get(scope, "k"));
        // 容量<=0：不应触达 Redis
        verify(template, times(0)).opsForValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void redisCache_putUsesPrefixScopeAndTtl() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);

        AgentProperties props = propsWithRedis(50, 1800, "pfx:");
        SynthesisCache cache = new RedisSynthesisCache(template, props);
        assertTrue(cache.isEnabled());
        AgentScope scope = AgentScope.of("tenant-x", "user-y");
        cache.put(scope, "extract:abc", "提炼结果");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<TimeUnit> unitCaptor = ArgumentCaptor.forClass(TimeUnit.class);
        verify(valueOps, times(1)).set(
                keyCaptor.capture(), valCaptor.capture(),
                ttlCaptor.capture(), unitCaptor.capture());

        String redisKey = keyCaptor.getValue();
        assertTrue("key 前缀应为 pfx:，实际=" + redisKey, redisKey.startsWith("pfx:"));
        assertTrue("key 应包含 scope 前缀，实际=" + redisKey,
                redisKey.contains(scope.keyPrefix()));
        assertTrue("key 应包含业务 key，实际=" + redisKey,
                redisKey.endsWith("extract:abc"));
        assertEquals(1800L, ttlCaptor.getValue().longValue());
        assertEquals(TimeUnit.SECONDS, unitCaptor.getValue());
        // 值里应包含 CacheEntry JSON（类名 + payload）
        String json = valCaptor.getValue();
        assertTrue("JSON 含类名", json.contains("java.lang.String"));
        assertTrue("JSON 含 payload", json.contains("提炼结果"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void redisCache_getMissThenHitRoundTrip() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);

        AgentProperties props = propsWithRedis(50, 3600, "claw:syn:");
        RedisSynthesisCache cache = new RedisSynthesisCache(template, props);
        AgentScope scope = AgentScope.of("t1", "u1");

        // --- 第一次：未命中 ---
        when(valueOps.get(anyString())).thenReturn(null);
        assertNull(cache.get(scope, "summary:x"));
        assertEquals(1L, cache.stats().get("misses"));

        // --- 第二次：命中，模拟写入时的 CacheEntry JSON ---
        String entryJson = "{\"cls\":\"java.lang.String\",\"payload\":\"摘要内容\"}";
        when(valueOps.get(anyString())).thenReturn(entryJson);
        String v = cache.get(scope, "summary:x");
        assertEquals("摘要内容", v);
        assertEquals(1L, cache.stats().get("hits"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void redisCache_getSwallowsExceptionsAndReturnsNull() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

        SynthesisCache cache = new RedisSynthesisCache(template, propsWithRedis(10, 60, "p:"));
        // 不应抛出异常：降级为未命中
        assertNull(cache.get(AgentScope.of("t", "u"), "k"));

        // put 同样吞异常
        doThrow(new RuntimeException("redis down")).when(valueOps)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        cache.put(AgentScope.of("t", "u"), "k", "v"); // no exception
    }

    @Test
    @SuppressWarnings("unchecked")
    public void redisCache_sizeAvoidsScanAndStatsShowsRedisType() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        SynthesisCache cache = new RedisSynthesisCache(template, propsWithRedis(10, 60, "p:"));
        // size 应该返回 -1，避免执行 KEYS *
        assertEquals(-1, cache.size());
        Map<String, Object> stats = cache.stats();
        assertEquals("redis", stats.get("type"));
        assertEquals("p:", stats.get("keyPrefix"));
        assertEquals(60, stats.get("ttlSeconds"));
    }

    // ===================== helpers =====================

    private AgentProperties propsWithCacheSize(int size) {
        AgentProperties p = new AgentProperties();
        LayeredMemoryConfig m = new LayeredMemoryConfig();
        m.setSynthesisCacheSize(size);
        p.setMemory(m);
        return p;
    }

    private AgentProperties propsWithRedis(int size, int ttlSeconds, String keyPrefix) {
        AgentProperties p = new AgentProperties();
        LayeredMemoryConfig m = new LayeredMemoryConfig();
        m.setSynthesisCacheSize(size);
        m.setSynthesisCacheTtlSeconds(ttlSeconds);
        m.setSynthesisCacheRedisKeyPrefix(keyPrefix);
        p.setMemory(m);
        return p;
    }
}
