package com.mwb.ai.claw.domain.core;

/**
 * 错误分类（C3 统一异常模型）：LLM 层、工具层失败统一映射为三类终态。
 * <ul>
 *   <li>TRANSIENT：瞬时错误（网络 / 超时 / 5xx / 429），可重试；重试 + 降级后仍失败按此分类对外；</li>
 *   <li>BUSINESS：业务错误（参数 / 权限 / 业务校验失败），不可重试；</li>
 *   <li>BUDGET：预算耗尽（单次运行 token 预算超限），中止执行。</li>
 * </ul>
 */
public enum ErrorCategory {

    /** 瞬时错误：可重试 */
    TRANSIENT,

    /** 业务错误：不可重试 */
    BUSINESS,

    /** 预算耗尽：中止执行 */
    BUDGET
}
