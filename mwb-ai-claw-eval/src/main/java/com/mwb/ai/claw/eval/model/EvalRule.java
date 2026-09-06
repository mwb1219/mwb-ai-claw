package com.mwb.ai.claw.eval.model;

import lombok.Data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 确定性判定规则：对 Agent 输出做低成本、可复现的通过/失败判定。
 * <p>
 * 用于「零 LLM 消耗」的快速回归：命中即判定，未命中再回退 LLM 裁判（见 {@link JudgeType}）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalRule {

    /** 规则类型（exact / contains / regex） */
    private RuleType type;

    /** 判定依据值（精确文本 / 子串 / 正则表达式） */
    private String value;

    /** 是否忽略大小写（exact/contains 生效，默认 false；regex 由用户自控） */
    private boolean ignoreCase = false;
}