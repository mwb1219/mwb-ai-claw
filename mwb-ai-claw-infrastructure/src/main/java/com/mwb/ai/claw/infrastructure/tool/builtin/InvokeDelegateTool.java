package com.mwb.ai.claw.infrastructure.tool.builtin;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.collaboration.ExecutionUnit;

/**
 * 协作工具：任务拆解委派（invoke_delegate）。
 * 对应编排 todo-delegate：主 Agent 思考规划并拆解为 Todo 列表，委托子 Agent 执行，可递归再委托，逐层汇总。
 */
@Component
public class InvokeDelegateTool extends AbstractCollaborationTool {

    public InvokeDelegateTool(@Lazy ExecutionUnit executionUnit) {
        super(executionUnit);
    }

    @Override
    public String getName() {
        return "invoke_delegate";
    }

    @Override
    protected String orchestrationId() {
        return "todo-delegate";
    }

    @Override
    protected String description() {
        return "发起任务拆解委派协作：规划拆解为 Todo 列表并委托多个子 Agent 分步执行，逐层汇总结果。"
                + "适合复杂、多步骤、跨领域任务（如「规划并实现」「拆解并完成」）；"
                + "单轮完成后返回各 Todo 的产出汇总。";
    }
}
