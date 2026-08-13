package com.mwb.ai.claw.domain.core;

import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ReAct 推理循环领域服务：Agent 的核心引擎。
 * <p>
 * 驱动 Thought → Action → Observation 的迭代，直到 LLM 产出最终回答或达到最大步数。
 * 本类不依赖任何 Spring / LLM SDK，仅依赖 domain 内的 Gateway 接口（依赖倒置）。
 */
public class ReActLoopService {

    private final LlmGateway llmGateway;
    private final ToolGateway toolGateway;
    private final LongTermMemoryGateway memoryGateway;

    public ReActLoopService(LlmGateway llmGateway, ToolGateway toolGateway,
                            LongTermMemoryGateway memoryGateway) {
        this.llmGateway = llmGateway;
        this.toolGateway = toolGateway;
        this.memoryGateway = memoryGateway;
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
            LlmRequest request = buildRequest(session, agent);

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
            LlmRequest request = buildRequest(session, agent);

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

    /**
     * 组装 LLM 请求：system prompt + AGENT.md + MEMORY.md + 历史消息 + 可用工具规格
     */
    private LlmRequest buildRequest(Session session, Agent agent) {
        LlmRequest request = new LlmRequest();
        request.setModel(agent.getModelConfig().getModel());
        request.setTemperature(agent.getModelConfig().getTemperature());
        request.setMaxTokens(agent.getModelConfig().getMaxTokens());

        List<LlmMessage> messages = new ArrayList<>();

        // 组装 system prompt：配置 systemPrompt + AGENT.md（长期指令） + MEMORY.md（长期记忆）
        StringBuilder systemPrompt = new StringBuilder(agent.getSystemPrompt());
        if (agent.getAgentInstructions() != null && !agent.getAgentInstructions().trim().isEmpty()) {
            systemPrompt.append("\n\n## Agent 扩展指令\n")
                    .append(agent.getAgentInstructions());
        }
        String memContent = memoryGateway.loadMemory();
        if (memContent != null && !memContent.trim().isEmpty()) {
            systemPrompt.append("\n\n## 长期记忆（跨会话）：\n")
                    .append(memContent);
        }
        messages.add(LlmMessage.system(systemPrompt.toString()));

        for (Message msg : session.getMessages()) {
            messages.add(toLlmMessage(msg));
        }
        request.setMessages(messages);

        List<ToolSpec> tools = new ArrayList<>();
        Set<String> added = new HashSet<>();
        // 1. Agent 显式配置的工具
        for (String toolName : agent.getToolNames()) {
            ToolSpec spec = toolGateway.getToolSpec(toolName);
            if (spec != null && added.add(spec.getName())) {
                tools.add(spec);
            }
        }
        // 2. 全局工具（MCP 动态注册），默认对所有 Agent 可见，无需在配置中声明
        for (ToolSpec spec : toolGateway.listTools()) {
            if (spec.isGlobal() && added.add(spec.getName())) {
                tools.add(spec);
            }
        }
        request.setTools(tools);
        return request;
    }

    private LlmMessage toLlmMessage(Message msg) {
        LlmMessage m = new LlmMessage();
        m.setRole(msg.getRole());
        m.setContent(msg.getContent());
        m.setToolCalls(msg.getToolCalls());
        m.setToolCallId(msg.getToolCallId());
        return m;
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
