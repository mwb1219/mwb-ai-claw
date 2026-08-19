package com.mwb.ai.claw.domain.tool;

import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 工具级静态授权端口（与人工审批门 {@link ToolApproval} 分层互补）：
 * 无权调用直接拒绝（不进入 ReAct 步骤与审批流程），由执行入口统一拦截。
 */
public interface ToolPermissionChecker {

    /**
     * 当前 scope 是否允许调用该工具；未启用鉴权 / 无配置时返回 true。
     */
    boolean isAllowed(AgentScope scope, String toolName);
}
