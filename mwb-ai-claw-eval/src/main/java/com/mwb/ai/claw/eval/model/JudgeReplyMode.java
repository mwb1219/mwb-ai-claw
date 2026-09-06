package com.mwb.ai.claw.eval.model;

/**
 * LLM 裁判返回格式（强制结构化输出之一）。
 * <ul>
 *   <li>{@link #QUOTE}：从期望答案中抽引用作为判据（适合「输出是否包含期望要点」）；</li>
 *   <li>{@link #NUMBER}：返回 0-10 数值分；</li>
 *   <li>{@link #BOOLEAN}：返回 true/false 布尔判定。</li>
 * </ul>
 */
public enum JudgeReplyMode {

    /** 引用期望答案判据 */
    QUOTE,

    /** 0-10 数值分 */
    NUMBER,

    /** boolean 判定 */
    BOOLEAN
}