package com.mwb.ai.claw.domain.core;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 循环执行结果
 */
@Data
public class ReActResult {

    /** 最终回复内容 */
    private String reply;

    /** 执行轨迹（Thought / Action / Observation 摘要） */
    private List<String> traceSteps = new ArrayList<>();

    /** 是否达到最大步数限制 */
    private boolean maxStepsReached;
}
