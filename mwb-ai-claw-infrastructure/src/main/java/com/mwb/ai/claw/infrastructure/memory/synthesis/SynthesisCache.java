package com.mwb.ai.claw.infrastructure.memory.synthesis;

import java.util.Map;

import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 记忆提炼缓存 SPI：按「操作类型 + 输入内容哈希」缓存 summarize/extract 结果。
 * <p>
 * 同一段消息块在重复触发提炼（如 afterTurn 边界漂移、异常重试、多 Agent 共享场景、多实例部署）时
 * 直接命中缓存，避免重复调用 LLM 产生成本。缓存 key 自动带 scope 前缀（scope + contentHash），
 * 杜绝跨用户互相命中。
 * <p>
 * 提供两种实现：
 * <ul>
 *     <li>local：JVM 内存 LinkedHashMap LRU，适合单实例 + storage=file 形态；</li>
 *     <li>redis：分布式 String TTL 缓存，适合 storage=db / 多实例水平扩展形态。</li>
 * </ul>
 */
public interface SynthesisCache {

    /** 缓存是否启用（容量/后端关闭时 get 永远返回 null，put 永远 no-op） */
    boolean isEnabled();

    /**
     * 读取缓存；命中返回缓存值，未命中返回 null（key 内部拼入 scope 前缀）。
     *
     * @param scope scope 维度（租户/用户，隔离命名空间）
     * @param key   业务侧 key（如 "summary:{hash}"、"extract:{hash}"）
     * @return 缓存值，未命中或禁用时返回 null
     */
    <T> T get(AgentScope scope, String key);

    /**
     * 写入缓存；value 为 null（提炼失败）不缓存，避免与"未命中"混淆。
     *
     * @param scope scope 维度（租户/用户，隔离命名空间）
     * @param key   业务侧 key
     * @param value 提炼结果，null 不写入
     */
    void put(AgentScope scope, String key, Object value);

    /** 当前缓存条目数（Redis 实现返回 -1，避免 keys * 性能开销） */
    int size();

    /** 命中率统计（面板/诊断用）；Redis 实现仅记录进程内累计 hits/misses */
    Map<String, Object> stats();
}
