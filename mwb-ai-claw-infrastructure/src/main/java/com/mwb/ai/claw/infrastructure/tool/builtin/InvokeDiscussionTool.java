package com.mwb.ai.claw.infrastructure.tool.builtin;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;

/**
 * 协作工具：多方专家讨论（invoke_discussion）。
 * 对应编排 team-discussion：架构师 / 编码专家 / 审查专家多轮讨论，决策主持收敛为明确结论。
 */
@Component
public class InvokeDiscussionTool extends AbstractCollaborationTool {

    public InvokeDiscussionTool(@Lazy ExecutionUnit executionUnit) {
        super(executionUnit);
    }

    @Override
    public String getName() {
        return "invoke_discussion";
    }

    @Override
    protected String orchestrationId() {
        return "team-discussion";
    }

    @Override
    protected String description() {
        return "发起多方专家对话式讨论（架构师 / 编码专家 / 审查专家多轮观点碰撞，最后由决策主持收敛为明确结论）。"
                + "适合技术选型、方案对比、权衡决策类问题（如「A 方案还是 B 方案好」「如何选择技术栈」）。";
    }
}
