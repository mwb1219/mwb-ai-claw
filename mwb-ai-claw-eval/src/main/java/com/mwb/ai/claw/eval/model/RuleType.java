package com.mwb.ai.claw.eval.model;

/**
 * 确定性规则类型。
 * <ul>
 *   <li>{@link #EXACT}：Agent 输出逐字符精确匹配规则值；</li>
 *   <li>{@link #CONTAINS}：输出包含规则值子串（默认忽略首尾空白与大小写差异见 {@link EvalRule}，可按需配置）；</li>
 *   <li>{@link #REGEX}：输出命中规则值的正则表达式。</li>
 * </ul>
 */
public enum RuleType {

    /** 精确匹配 */
    EXACT,

    /** 子串包含 */
    CONTAINS,

    /** 正则匹配 */
    REGEX
}