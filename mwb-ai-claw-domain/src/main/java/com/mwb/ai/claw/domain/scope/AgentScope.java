package com.mwb.ai.claw.domain.scope;

import lombok.Value;

/**
 * 租户/用户维度值对象。tenantId/userId 为空表示「默认空间」（legacy 根目录，兼容模式）。
 * <p>
 * 作为存储端口、异步任务、嵌套编排显式携带的身份维度，不依赖全局隐式状态。
 */
@Value
public class AgentScope {

    /** 租户 id（可空，空 = 默认空间） */
    String tenantId;

    /** 用户 id（可空，空 = 默认空间） */
    String userId;

    public static AgentScope of(String tenantId, String userId) {
        return new AgentScope(tenantId, userId);
    }

    public static AgentScope defaultScope() {
        return new AgentScope(null, null);
    }

    /** 是否启用租户隔离（tenantId 非空） */
    public boolean isTenanted() {
        return tenantId != null && !tenantId.isEmpty();
    }

    /** 是否启用用户维度（tenantId 或 userId 非空） */
    public boolean isUserScoped() {
        return (tenantId != null && !tenantId.isEmpty())
                || (userId != null && !userId.isEmpty());
    }

    /**
     * 存储命名空间 key（文件目录 / 表前缀 / Redis key 前缀统一使用）。
     * null → 使用 legacy 根目录。
     */
    public String namespace() {
        return isUserScoped() ? tenantId + "/" + userId : null;
    }

    /**
     * 锁定 / 缓存 key 维度（null 时退化为 "default"）。
     */
    public String keyPrefix() {
        String ns = namespace();
        return ns != null ? ns : "default";
    }
}
