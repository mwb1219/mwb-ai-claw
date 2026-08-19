package com.mwb.ai.claw.infrastructure.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 认证鉴权配置（agent.auth.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.auth")
public class AuthProperties {

    /** 是否启用认证（默认关闭；关闭时行为与现状一致，scope=default） */
    private boolean enabled = false;

    /** API Key 请求头（默认 X-API-Key；同时支持 Authorization: Bearer 与 SSE ?apiKey= 参数） */
    private String header = "X-API-Key";

    /** 静态 API Key 映射：tenantId → userId → apiKey（也可经 auth.json 外部化） */
    private Map<String, Map<String, String>> apiKeys = new HashMap<>();

    /** 未配置工具权限时的兜底用户 */
    private String defaultUser = "default";

    /** 工具级权限：userId → 可用工具列表（缺省 = 全部允许） */
    private Map<String, List<String>> toolPermissions = new HashMap<>();

    /**
     * 反查 apiKey → [tenantId, userId]；未命中返回 null。
     */
    public String[] resolve(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Map<String, String>> tenant : apiKeys.entrySet()) {
            for (Map.Entry<String, String> user : tenant.getValue().entrySet()) {
                if (apiKey.equals(user.getValue())) {
                    return new String[]{tenant.getKey(), user.getKey()};
                }
            }
        }
        return null;
    }

    /**
     * 用户可用工具列表（未配置返回空列表 = 全部允许，由调用方判定）。
     */
    public List<String> toolsOf(String userId) {
        if (userId == null || userId.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> tools = toolPermissions.get(userId);
        return tools != null ? tools : new ArrayList<>();
    }
}
