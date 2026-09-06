package com.mwb.ai.claw.eval.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mwb.ai.claw.eval.model.TraceDiffStatus;
import lombok.Data;

/**
 * golden trace 对比结果：一次归一化 diff 的状态与说明。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TraceDiff {

    /** 对比状态（见 {@link TraceDiffStatus}，无基线时为 NO_BASELINE） */
    private TraceDiffStatus status;

    /** 对比说明（两侧步数 + 缺失/新增步骤摘要，便于人工复核） */
    private String details;
}