package com.mwb.ai.claw.infrastructure.collaboration.delegate;

/**
 * Todo 生命周期状态机（P1 交互与上下文）：
 * <pre>
 *   paused --(人工审批通过)--> approved --(开始执行)--> running --(完成)--> done
 *      |                          |                       |
 *      |                          |                       +--(失败)--> failed
 *      +--(审批拒绝/超时)--> 该层降级直执行（不进入委派）
 * </pre>
 * 未启用审批门禁（approvalGate=none）时，Todo 直接进入 running → done / failed。
 */
public enum TodoStatus {
    /** 等待人工审批（命中审批门禁的层，规划完成后进入） */
    PAUSED,
    /** 已批准（人工审批通过，可进入委派执行） */
    APPROVED,
    /** 执行中（已委托子 Agent / 直执行） */
    RUNNING,
    /** 执行完成（结果已产出） */
    DONE,
    /** 执行失败（按 onFailure 策略继续或终止） */
    FAILED
}
