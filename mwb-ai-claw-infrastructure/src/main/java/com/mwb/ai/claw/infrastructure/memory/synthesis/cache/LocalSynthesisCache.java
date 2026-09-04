package com.mwb.ai.claw.infrastructure.memory.synthesis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.memory.layered.spi.SynthesisCache;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * 本地 JVM 内存版提炼缓存（LRU LinkedHashMap，线程安全，容量由 synthesis-cache-size 控制，<=0 关闭）。
 * <p>
 * 适用场景：单实例部署 / agent.storage.type=file。生产多实例 + storage=db 应优先使用 Redis 版本，
 * 否则多个实例各自维护本地缓存，仍会出现重复提炼与成本浪费。
 */
public class LocalSynthesisCache implements SynthesisCache {

    private static final Logger log = LoggerFactory.getLogger(LocalSynthesisCache.class);

    private final int capacity;
    private final LinkedHashMap<String, Object> map;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public LocalSynthesisCache(AgentProperties properties) {
        LayeredMemoryConfig cfg = properties.getMemory();
        this.capacity = cfg.getSynthesisCacheSize();
        if (this.capacity > 0) {
            this.map = new LinkedHashMap<String, Object>(Math.max(16, capacity), 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > capacity;
                }
            };
        } else {
            this.map = null;
        }
        log.info("提炼缓存[local]: 容量={}", this.capacity > 0 ? this.capacity : "关闭");
    }

    @Override
    public boolean isEnabled() {
        return map != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(AgentScope scope, String key) {
        if (map == null) {
            return null;
        }
        String cacheKey = cacheKey(scope, key);
        synchronized (map) {
            Object v = map.get(cacheKey);
            if (v != null) {
                hits.incrementAndGet();
                return (T) v;
            }
        }
        misses.incrementAndGet();
        return null;
    }

    @Override
    public void put(AgentScope scope, String key, Object value) {
        if (map == null || key == null || value == null) {
            return;
        }
        synchronized (map) {
            map.put(cacheKey(scope, key), value);
        }
    }

    private String cacheKey(AgentScope scope, String key) {
        return (scope != null ? scope.keyPrefix() : "default") + ":" + key;
    }

    @Override
    public int size() {
        return map == null ? 0 : map.size();
    }

    @Override
    public Map<String, Object> stats() {
        long hit = hits.get();
        long miss = misses.get();
        long total = hit + miss;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "local");
        s.put("enabled", isEnabled());
        s.put("capacity", capacity);
        s.put("size", size());
        s.put("hits", hit);
        s.put("misses", miss);
        s.put("hitRate", total == 0 ? 0.0 : Math.round(hit * 1000.0 / total) / 10.0);
        return s;
    }
}
