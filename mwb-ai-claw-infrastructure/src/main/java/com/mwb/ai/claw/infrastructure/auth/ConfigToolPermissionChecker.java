package com.mwb.ai.claw.infrastructure.auth;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.tool.ToolPermissionChecker;
import com.mwb.ai.claw.infrastructure.config.AuthProperties;

/**
 * 基于 {@code agent.auth.tool-permissions} 的静态工具授权实现。
 * <p>
 * 规则：认证关闭 / 用户未配置权限 → 全部允许（与现状一致）；配置了权限则仅放行白名单内工具。
 * scope 来源：AgentScopeContext（请求线程内执行，主 Agent 与协作工具调用同线程可读）。
 */
@Component
public class ConfigToolPermissionChecker implements ToolPermissionChecker {

    @Resource
    private AuthProperties authProperties;

    @Override
    public boolean isAllowed(AgentScope scope, String toolName) {
        if (!authProperties.isEnabled()) {
            return true;
        }
        String userId = scope != null ? scope.getUserId() : null;
        if (userId == null || userId.isEmpty()) {
            return true;
        }
        List<String> allowed = authProperties.toolsOf(userId);
        return allowed.isEmpty() || allowed.contains(toolName);
    }
}
