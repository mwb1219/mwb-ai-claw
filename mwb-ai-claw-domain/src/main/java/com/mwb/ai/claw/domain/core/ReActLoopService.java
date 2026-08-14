package com.mwb.ai.claw.domain.core;

import com.mwb.ai.claw.domain.context.ContextAssembler;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolResult;

import java.util.List;

/**
 * ReAct 推理循环领域服务：Agent 的核心引擎。
 * <p>
 * 驱动 Thought → Action → Observation 的迭代，直到 LLM 产出最终回答或达到最大步数。
 * 本类不依赖任何 Spring / LLM SDK，仅依赖 domain 内的 Gateway 接口（依赖倒置）。
 */
public class ReActLoopService {

    private final LlmGateway llmGateway;
    private final ToolGateway toolGateway;
    private final ContextAssembler contextAssembler;

    public ReActLoopService(LlmGateway llmGateway, ToolGateway toolGateway,
                            ContextAssembler contextAssembler) {
        this.llmGateway = llmGateway;
        this.toolGateway = toolGateway;
        this.contextAssembler = contextAssembler;
    }

    /**
     * 执行 ReAct 循环
     *
     * @param session 会话聚合根（已包含本轮 user 消息）
     * @param agent  Agent 配置
     * @return 执行结果（最终回复 + 执行轨迹）
     */
    public ReActResult run(Session session, Agent agent) {
        return run(session, agent, null);
    }

    /**
     * 执行 ReAct 循环（带进度回调）
     *
     * @param session  会话聚合根（已包含本轮 user 消息）
     * @param agent    Agent 配置
     * @param callback 进度回调，每产生一条 trace step 都会回调；为 null 则不回调
     * @return 执行结果（最终回复 + 执行轨迹）
     */
    public ReActResult run(Session session, Agent agent, ProgressCallback callback) {
        ReActResult result = new ReActResult();
        int maxSteps = agent.getMaxSteps();

        for (int step = 1; step <= maxSteps; step++) {
            // 1. 组装 LLM 请求（system + history + tools）
            LlmRequest request = contextAssembler.assemble(session, agent);

            // 2. 调用 LLM（依赖倒置）
            LlmResponse response = llmGateway.chat(request, agent.getModelConfig());

            List<ToolCall> toolCalls = response.getToolCalls();
            boolean noToolCalls = toolCalls == null || toolCalls.isEmpty();

            // 3. 无工具调用 → 终止并返回最终回复
            if (noToolCalls) {
                session.addAssistantMessage(response.getContent(), null);
                result.setReply(response.getContent());
                String thought = "[Thought] " + truncate(response.getContent());
                result.getTraceSteps().add(thought);
                notify(callback, thought);
                return result;
            }

            // 4. 有工具调用 → 记录 assistant 消息（含 tool_calls）
            session.addAssistantMessage(response.getContent(), toolCalls);
            String thought = "[Thought] 需要调用工具处理（第 " + step + " 步）";
            result.getTraceSteps().add(thought);
            notify(callback, thought);

            // 5. 依次执行每个工具调用，结果作为 Observation 反馈
            for (ToolCall toolCall : toolCalls) {
                String action = "[Action] 调用工具: " + toolCall.getName()
                        + " 参数: " + truncateArgs(toolCall.getArguments());
                result.getTraceSteps().add(action);
                notify(callback, action);

                ToolResult toolResult = toolGateway.execute(toolCall.getName(), toolCall.getArguments());
                String observation = toolResult.isSuccess()
                        ? toolResult.getOutput()
                        : "ERROR: " + toolResult.getError();

                session.addToolMessage(toolCall.getId(), observation);
                String obs = "[Observation] " + truncate(observation);
                result.getTraceSteps().add(obs);
                notify(callback, obs);
            }
            // 继续下一轮循环，让 LLM 根据 Observation 再次推理
        }

        // 达到最大步数仍未完成
        result.setMaxStepsReached(true);
        result.setReply("达到最大推理步数限制(" + maxSteps + ")，未能完成最终回复。");
        return result;
    }

    /**
     * 执行 ReAct 循环（流式版本，LLM token 实时推送到上层）
     *
     * @param session        会话聚合根
     * @param agent          Agent 配置
     * @param callback       进度回调（trace step）
     * @param streamCallback LLM 流式回调（token 级增量）
     * @return 执行结果
     */
    public ReActResult streamRun(Session session, Agent agent,
                                 ProgressCallback callback,
                                 LlmStreamCallback streamCallback) {
        ReActResult result = new ReActResult();
        int maxSteps = agent.getMaxSteps();

        for (int step = 1; step <= maxSteps; step++) {
            LlmRequest request = contextAssembler.assemble(session, agent);

            // 流式调用 LLM
            LlmResponse response = llmGateway.streamChat(request, agent.getModelConfig(), streamCallback);

            List<ToolCall> toolCalls = response.getToolCalls();
            boolean noToolCalls = toolCalls == null || toolCalls.isEmpty();

            if (noToolCalls) {
                session.addAssistantMessage(response.getContent(), null);
                result.setReply(response.getContent());
                String thought = "[Thought] " + truncate(response.getContent());
                result.getTraceSteps().add(thought);
                notify(callback, thought);
                return result;
            }

            session.addAssistantMessage(response.getContent(), toolCalls);
            String thought = "[Thought] 需要调用工具处理（第 " + step + " 步）";
            result.getTraceSteps().add(thought);
            notify(callback, thought);

            for (ToolCall toolCall : toolCalls) {
                String action = "[Action] 调用工具: " + toolCall.getName()
                        + " 参数: " + truncateArgs(toolCall.getArguments());
                result.getTraceSteps().add(action);
                notify(callback, action);

                ToolResult toolResult = toolGateway.execute(toolCall.getName(), toolCall.getArguments());
                String observation = toolResult.isSuccess()
                        ? toolResult.getOutput()
                        : "ERROR: " + toolResult.getError();

                session.addToolMessage(toolCall.getId(), observation);
                String obs = "[Observation] " + truncate(observation);
                result.getTraceSteps().add(obs);
                notify(callback, obs);
            }
        }

        result.setMaxStepsReached(true);
        result.setReply("达到最大推理步数限制(" + maxSteps + ")，未能完成最终回复。");
        return result;
    }

    private void notify(ProgressCallback callback, String step) {
        if (callback != null) {
            callback.onProgress(step);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    /** 截断工具参数以免 trace step / SSE 事件过大 */
    private String truncateArgs(String args) {
        if (args == null) {
            return "";
        }
        return args.length() > 300 ? args.substring(0, 300) + "..." : args;
    }
}
