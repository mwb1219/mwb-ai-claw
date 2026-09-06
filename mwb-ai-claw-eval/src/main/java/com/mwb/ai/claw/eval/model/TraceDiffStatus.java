package com.mwb.ai.claw.eval.model;

/**
 * golden trace 对比回归状态：当前 run 与 baseline 步骤级归一化 diff 的结果。
 */
public enum TraceDiffStatus {

    /** 结构/关键步骤发生回归（缺失关键动作或步骤数量显著下降） */
    REGRESSED,

    /** 结构/关键步骤优于基线（新增了步骤） */
    IMPROVED,

    /** 结构/关键步骤与基线一致（归一化后无差异） */
    UNCHANGED,

    /** 无基线可对比（未采集基线，或 TraceStore 未装配/找不到 baseline） */
    NO_BASELINE
}