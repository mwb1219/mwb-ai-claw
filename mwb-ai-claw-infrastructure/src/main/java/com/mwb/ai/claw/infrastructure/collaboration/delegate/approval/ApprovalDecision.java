package com.mwb.ai.claw.infrastructure.collaboration.delegate.approval;

/**
 * 人工审批决策（P1 交互与上下文）：
 * <ul>
 *   <li>{@link #APPROVED}：审批通过，该层计划继续委派执行；</li>
 *   <li>{@link #REJECTED}：审批拒绝，该层降级直执行（规划 Agent 直接回答，不再委派）；</li>
 *   <li>{@link #TIMEOUT}：等待超时（approvalTimeoutMs &gt; 0 且到期），按拒绝处理降级直执行。</li>
 * </ul>
 */
public enum ApprovalDecision {
    APPROVED,
    REJECTED,
    TIMEOUT
}
