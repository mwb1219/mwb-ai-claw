package com.mwb.ai.claw.eval.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 单条用例的执行 + 判定结果。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseResult {

    /** 用例 id */
    private String caseId;

    /** 用例名称 */
    private String name;

    /** 是否通过 */
    private boolean passed;

    /** 实际采用的判定方式（rule | llm） */
    private JudgeType judge;

    /** 得分（LLM 裁判为 0-10 或 0-1，规则判定为 0/1）；可为 null（如 boolean 判定不产分） */
    private Double score;

    /** 判定原文 / 理由（LLM 裁判 verdict 或规则命中说明） */
    private String verdict;

    /** Agent 最终输出 */
    private String reply;

    /** 执行耗时（毫秒，不含 LLM 裁判） */
    private long durationMs;

    /** 执行 + 判定累计 token（约数，供成本分摊） */
    private long tokens;

    /** 执行或判定异常信息（成功为空） */
    private String error;

    /** golden trace 对比回归状态（TraceStore 未装配或无基线时为 NO_BASELINE） */
    private TraceDiffStatus traceDiffStatus;

    /** golden trace 对比详情（归一化 diff 说明；无对比时为空） */
    private String traceDiffDetails;
}