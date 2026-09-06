package com.mwb.ai.claw.eval.model;

/**
 * 单个用例在 baseline → current 两次评测间的回归状态。
 */
public enum DiffStatus {

    /** 通过 → 失败（回归，需要告警） */
    REGRESSED,

    /** 失败 → 通过（修复） */
    IMPROVED,

    /** 状态一致（维持通过或维持失败） */
    UNCHANGED,

    /** 仅出现在 current（新增用例） */
    NEW,

    /** 仅出现在 baseline（旧用例已移除/改名） */
    MISSING
}