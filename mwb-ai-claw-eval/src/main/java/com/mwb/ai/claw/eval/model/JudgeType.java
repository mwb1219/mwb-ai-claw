package com.mwb.ai.claw.eval.model;

/**
 * 判定方式枚举。
 * <ul>
 *   <li>{@link #RULE}：确定性规则（exact/contains/regex），零 LLM 成本、秒级回归；</li>
 *   <li>{@link #LLM}：LLM 语义裁判，适合开放题 / 无正则可判的场景。</li>
 * </ul>
 */
public enum JudgeType {

    /** 确定性规则判定 */
    RULE,

    /** LLM 语义裁判 */
    LLM
}