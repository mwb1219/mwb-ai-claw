package com.mwb.ai.claw.eval.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 单个用例的回归对比项。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalDiffItem {

    /** 用例 id */
    private String caseId;

    /** 用例名称 */
    private String name;

    /** 回归状态（improved / regressed / unchanged / new / missing） */
    private DiffStatus status;

    /** baseline 是否通过 */
    private boolean baselinePassed;

    /** current 是否通过 */
    private boolean currentPassed;

    /** baseline 得分（可为 null） */
    private Double baselineScore;

    /** current 得分（可为 null） */
    private Double currentScore;
}