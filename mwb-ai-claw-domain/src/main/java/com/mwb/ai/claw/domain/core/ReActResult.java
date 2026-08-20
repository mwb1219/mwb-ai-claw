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

    /** 执行是否成功（LLM 返回 error 终态 / 预算耗尽时置 false） */
    private boolean success = true;

    /** 失败时的明确错误信息（success=false 时有值） */
    private String errorMessage;

    /** 失败时的错误分类（success=false 时有值，供上层映射错误码） */
    private ErrorCategory errorCategory;
}
