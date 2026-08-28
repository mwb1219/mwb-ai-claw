package com.mwb.ai.claw.infrastructure.memory.synthesis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.mwb.ai.claw.domain.memory.model.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * Redis 分布式版提炼缓存（String + JSON 序列化 + TTL，适合 storage=db 多实例部署）。
 * <p>
 * 关键设计：
 * <ul>
 *     <li>存取走 JSON 字符串（StringRedisTemplate，统一序列化，避免 JDK 序列化跨类加载器问题）；</li>
 *     <li>按 scope.keyPrefix + businessKey 组合 Redis key，再加配置前缀隔离命名空间；</li>
 *     <li>容量参数在 Redis 形态下仅用于诊断输出（容量由 Redis 内存策略与 TTL 控制）；</li>
 *     <li>容量<=0 仍然"禁用"缓存（所有操作 no-op / 返回 null），与 LocalSynthesisCache 行为一致；</li>
 *     <li>size() 返回 -1（避免在生产 Redis 执行 keys *），hits/misses 仅记录本进程累计值（面板用）。</li>
 * </ul>
 */
public class RedisSynthesisCache implements SynthesisCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSynthesisCache.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final int ttlSeconds;
    private final int capacity;
    private final boolean enabled;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public RedisSynthesisCache(StringRedisTemplate redisTemplate, AgentProperties properties) {
        LayeredMemoryConfig cfg = properties.getMemory();
        this.redisTemplate = redisTemplate;
        this.capacity = cfg.getSynthesisCacheSize();
        this.enabled = this.capacity > 0;
        this.ttlSeconds = cfg.getSynthesisCacheTtlSeconds() > 0 ? cfg.getSynthesisCacheTtlSeconds() : 3600;
        String pfx = cfg.getSynthesisCacheRedisKeyPrefix();
        this.keyPrefix = (pfx != null && !pfx.trim().isEmpty()) ? pfx.trim() : "claw:syn:";
        log.info("提炼缓存[redis]: 容量状态={}, keyPrefix={}, ttl={}s（容量<=0 时禁用）",
                this.enabled ? ("参考（上限=" + this.capacity + "，Redis 按 TTL 淘汰）") : "关闭",
                this.keyPrefix, this.ttlSeconds);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(AgentScope scope, String key) {
        if (!enabled || key == null) {
            return null;
        }
        try {
            String raw = redisTemplate.opsForValue().get(redisKey(scope, key));
            if (raw == null) {
                misses.incrementAndGet();
                return null;
            }
            hits.incrementAndGet();
            CacheEntry entry = JsonUtils.fromJson(raw, CacheEntry.class);
            if (entry == null) {
                return null;
            }
            // 简单值：直接按字符串/数值/布尔返回；列表与页对象由调用侧类型擦除 → 需要按 classHint 还原
            if (entry.getCls() == null || entry.getCls().isEmpty() || entry.getPayload() == null) {
                return (T) entry.getPayload();
            }
            try {
                Class<?> clazz = Class.forName(entry.getCls());
                Object payload = entry.getPayload();
                if (payload != null && !clazz.isInstance(payload)) {
                    // payload 是 Map/List JSON 反序列化结果，需要二次精确转换
                    return JsonUtils.fromJson(JsonUtils.toJson(payload), (Class<T>) clazz);
                }
                return (T) payload;
            } catch (ClassNotFoundException e) {
                // 类不存在视为缓存失效（模型变更后旧条目自然淘汰），走未命中
                log.warn("Redis 提炼缓存类不存在，按未命中处理: {}", entry.getCls());
                return null;
            }
        } catch (Exception e) {
            // Redis 网络异常：按未命中处理，不阻塞主对话链路
            log.warn("Redis 提炼缓存读取失败，按未命中跳过: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void put(AgentScope scope, String key, Object value) {
        if (!enabled || key == null || value == null) {
            return;
        }
        try {
            CacheEntry entry = new CacheEntry();
            entry.setCls(value.getClass().getName());
            entry.setPayload(value);
            redisTemplate.opsForValue().set(
                    redisKey(scope, key),
                    JsonUtils.toJson(entry),
                    ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 提炼缓存写入失败，跳过: {}", e.getMessage());
        }
    }

    private String redisKey(AgentScope scope, String key) {
        return keyPrefix + (scope != null ? scope.keyPrefix() : "default") + ":" + key;
    }

    @Override
    public int size() {
        // 避免 KEYS * 扫描，诊断用途返回 -1
        return -1;
    }

    @Override
    public Map<String, Object> stats() {
        long hit = hits.get();
        long miss = misses.get();
        long total = hit + miss;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "redis");
        s.put("enabled", enabled);
        s.put("capacity", capacity);
        s.put("keyPrefix", keyPrefix);
        s.put("ttlSeconds", ttlSeconds);
        s.put("size", size());
        s.put("hits", hit);
        s.put("misses", miss);
        s.put("hitRate", total == 0 ? 0.0 : Math.round(hit * 1000.0 / total) / 10.0);
        return s;
    }

    /** Redis 存储的包装对象，保留类型以便还原精确类（避免 List<MemoryPage> 这类泛型丢失）。 */
    public static class CacheEntry {
        private String cls;
        private Object payload;

        public String getCls() { return cls; }
        public void setCls(String cls) { this.cls = cls; }
        public Object getPayload() { return payload; }
        public void setPayload(Object payload) { this.payload = payload; }
    }
}
