package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwb.ai.claw.domain.collaboration.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.ExecutionUnit;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * 协作编排工具基类：把多 Agent 编排（conversational / delegate）封装为 ReAct 工具，
 * 由主 Agent 在推理过程中自主决定是否发起协作，替代原先「消息前置意图路由选编排」的方式。
 * <p>
 * 工具以 global=true 注册：对所有 Agent 可见，无需在 agents.json / application.yml 中显式声明。
 * 调用参数仅 message（交给协作子任务的任务描述），编排内部自建临时会话执行，产出作为工具结果回传给主 Agent。
 */
public abstract class AbstractCollaborationTool implements ToolExecutor {

    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"message\":{\"type\":\"string\",\"description\":\"交给协作子任务的任务描述，将作为协作编排的输入（沿用当前任务或补充细节）\"}"
            + "},"
            + "\"required\":[\"message\"]"
            + "}";

    private final ExecutionUnit executionUnit;

    protected AbstractCollaborationTool(ExecutionUnit executionUnit) {
        this.executionUnit = executionUnit;
    }

    /** 编排 id（引用 orchestrations.json 中的定义） */
    protected abstract String orchestrationId();

    /** 工具能力描述（供 LLM 判断何时调用） */
    protected abstract String description();

    @Override
    public ToolSpec getSpec() {
        ToolSpec spec = new ToolSpec(getName(), description(), PARAMS_SCHEMA);
        spec.setGlobal(true);
        return spec;
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        return execute(argumentsJson, null);
    }

    @Override
    public ToolResult execute(String argumentsJson, ProgressCallback callback) {
        try {
            JsonNode node = JsonUtils.readTree(argumentsJson == null ? "{}" : argumentsJson);
            String message = node == null ? null : node.path("message").asText(null);
            if (message == null || message.trim().isEmpty()) {
                return ToolResult.error("参数 message 不能为空（请传入交给协作子任务的任务描述）");
            }
            if (callback != null) {
                callback.onProgress("[Orchestration] 发起协作编排: " + orchestrationId());
            }
            CollaborationResult result = executionUnit.runOrchestration(message.trim(), orchestrationId(), callback);
            return ToolResult.success(result.getReply());
        } catch (Exception e) {
            return ToolResult.error("协作编排执行失败: " + e.getMessage());
        }
    }
}
