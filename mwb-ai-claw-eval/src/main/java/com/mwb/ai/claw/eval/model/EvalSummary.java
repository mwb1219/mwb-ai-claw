package com.mwb.ai.claw.eval.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 一次评测的聚合摘要：通过率 / 平均延迟 / 平均 token / 估算成本。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalSummary {

    /** 用例总数 */
    private int total;

    /** 通过数 */
    private int passed;

    /** 失败数 */
    private int failed;

    /** 通过率（0-1） */
    private double passRate;

    /** 平均执行耗时（毫秒） */
    private double avgDurationMs;

    /** 平均 token（口径：执行 + 判定累计） */
    private double avgTokens;

    /** 累计 token */
    private long totalTokens;

    /** 估算成本（元；undefined 当未配置单价时仅做 token 统计） */
    private double cost;
}