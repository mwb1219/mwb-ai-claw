package com.mwb.ai.claw.eval.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 两份评测报告（baseline vs current）的回归对比结果。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalDiff {

    /** baseline 数据集 id */
    private String taskId;

    /** baseline 通过率（0-1） */
    private double baselinePassRate;

    /** current 通过率（0-1） */
    private double currentPassRate;

    /** 通过率变化（current - baseline，负值表回归） */
    private double passRateDelta;

    /** 是否判定为回归（current 通过率低于 baseline，或存在 REGRESSED 用例） */
    private boolean regression;

    /** 回归用例数 */
    private int regressedCount;

    /** 修复用例数 */
    private int improvedCount;

    /** 逐用例对比明细 */
    private List<EvalDiffItem> items = new ArrayList<>();
}