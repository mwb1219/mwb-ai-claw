package com.mwb.ai.claw.domain.observability;

import lombok.Data;

/**
 * 步骤级 trace 单元：一次 Agent 执行迭代产生的单条推理步骤。
 * <p>
 * 类型与 ReAct 轨迹段落对应：thought（思考）/ action（工具调用）/ observation（工具结果）/ info（步数扩展等）。
 */
@Data
public class TraceStep {

    /** 步骤序号（从 1 递增，用于链路排序） */
    private int index;

    /** 步骤类型：thought | action | observation | info | step */
    private String type;

    /** 步骤内容（保留原始轨迹文本，形如 "[Action] 调用工具: xxx 参数: …"） */
    private String content;
}