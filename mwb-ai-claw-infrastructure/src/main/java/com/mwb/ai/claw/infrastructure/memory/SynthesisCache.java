package com.mwb.ai.claw.infrastructure.memory;

import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 提炼结果缓存（Phase 4 成本优化）：按「操作类型 + 输入内容哈希」缓存 summarize/extract 结果。
 * <p>
 * 同一段消息块在重复触发提炼（如 afterTurn 边界漂移、异常重试、多 Agent 共享场景）时直接命中缓存，
 * 避免重复调用 LLM 产生成本。容量由 {@code synthesis-cache-size} 配置，LRU 淘汰；<=0 时关闭。
 */
@Component
public class SynthesisCache {

    private static final Logger log = LoggerFactory.getLogger(SynthesisCache.class);

    private final int capacity;
    private final LinkedHashMap<String, Object> map;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public SynthesisCache(AgentProperties properties) {
        this.capacity = properties.getMemory().getSynthesisCacheSize();
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
        log.warn("提炼缓存: 容量={}", this.capacity > 0 ? this.capacity : "关闭");
    }

    public boolean isEnabled() {
        return map != null;
    }

    /** 命中返回缓存值，未命中返回 null */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        if (map == null) {
            return null;
        }
        synchronized (map) {
            Object v = map.get(key);
            if (v != null) {
                hits.incrementAndGet();
                return (T) v;
            }
        }
        misses.incrementAndGet();
        return null;
    }

    /** 缓存提炼结果；value 为 null（提炼失败）不缓存，避免与"未命中"混淆 */
    public void put(String key, Object value) {
        if (map == null || key == null || value == null) {
            return;
        }
        synchronized (map) {
            map.put(key, value);
        }
    }

    /** 当前缓存条目数 */
    public int size() {
        return map == null ? 0 : map.size();
    }

    /** 命中率统计（面板/诊断用） */
    public Map<String, Object> stats() {
        long hit = hits.get();
        long miss = misses.get();
        long total = hit + miss;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", isEnabled());
        s.put("capacity", capacity);
        s.put("size", size());
        s.put("hits", hit);
        s.put("misses", miss);
        s.put("hitRate", total == 0 ? 0.0 : Math.round(hit * 1000.0 / total) / 10.0);
        return s;
    }
}
