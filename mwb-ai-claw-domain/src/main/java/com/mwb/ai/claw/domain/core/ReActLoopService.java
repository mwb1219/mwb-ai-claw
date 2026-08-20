package com.mwb.ai.claw.domain.core;

import java.util.List;

import com.mwb.ai.claw.domain.context.ContextAssembler;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolResult;

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
    private final LayeredMemoryGateway memoryManager;

    /**
     * ReAct 步数扩展系数：初始预算（maxSteps）用尽且工具链未完成时自动追加步数，
     * 硬上限 = maxSteps × 系数（默认 2.0），防止死循环的同时让复杂工具链跑完。
     */
    private final double maxStepsExtensionFactor;

    public ReActLoopService(LlmGateway llmGateway, ToolGateway toolGateway,
                            ContextAssembler contextAssembler) {
        this(llmGateway, toolGateway, contextAssembler, null);
    }

    public ReActLoopService(LlmGateway llmGateway, ToolGateway toolGateway,
                            ContextAssembler contextAssembler,
                            LayeredMemoryGateway memoryManager) {
        this(llmGateway, toolGateway, contextAssembler, memoryManager, 2.0);
    }

    public ReActLoopService(LlmGateway llmGateway, ToolGateway toolGateway,
                            ContextAssembler contextAssembler,
                            LayeredMemoryGateway memoryManager,
                            double maxStepsExtensionFactor) {
        this.llmGateway = llmGateway;
        this.toolGateway = toolGateway;
        this.contextAssembler = contextAssembler;
        this.memoryManager = memoryManager;
        this.maxStepsExtensionFactor = maxStepsExtensionFactor > 1.0 ? maxStepsExtensionFactor : 2.0;
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
        // 软预算（初始 maxSteps）+ 硬上限（maxSteps × 扩展系数）：预算用尽且工具链未完成时自动扩展
        int hardCap = Math.max(maxSteps, (int) Math.ceil(maxSteps * maxStepsExtensionFactor));
        int effectiveSteps = maxSteps;
        int step = 0;

        while (step < effectiveSteps && !Thread.currentThread().isInterrupted()) {
            step++;
            // 1. 组装 LLM 请求（system + history + tools）
            LlmRequest request = contextAssembler.assemble(session, agent);

            // 2. 调用 LLM（依赖倒置）
            LlmResponse response = llmGateway.chat(request, agent.getModelConfig());

            // 2.1 LLM 返回 error 终态（重试+fallback 后仍失败 / 4xx 业务错误 / 预算耗尽）：
            //     中止循环，不写入 assistant 消息，由上层转错误响应（error 不得冒充最终回复）
            if ("error".equals(response.getFinishReason())) {
                return errorResult(response);
            }

            List<ToolCall> toolCalls = response.getToolCalls();
            boolean noToolCalls = toolCalls == null || toolCalls.isEmpty();

            // 3. 无工具调用 → 终止并返回最终回复
            if (noToolCalls) {
                session.addAssistantMessage(response.getContent(), null);
                result.setReply(response.getContent());
                String thought = "[Thought] " + truncate(response.getContent());
                result.getTraceSteps().add(thought);
                notify(callback, thought);
                afterTurn(session, agent);
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

                ToolResult toolResult = toolGateway.execute(toolCall.getName(), toolCall.getArguments(), callback);
                String observation = toolResult.isSuccess()
                        ? toolResult.getOutput()
                        : "ERROR: " + toolResult.getError();

                session.addToolMessage(toolCall.getId(), observation);
                String obs = "[Observation] " + truncate(observation);
                result.getTraceSteps().add(obs);
                notify(callback, obs);
            }
            afterTurn(session, agent);
            // 动态扩展预算：本轮仍调用了工具（工具链未收敛）且预算用尽 → 自动追加，不超硬上限
            int extended = extendedBudget(step, maxSteps, effectiveSteps, hardCap);
            if (extended > effectiveSteps) {
                effectiveSteps = extended;
                String ext = "[Info] 步数预算(" + maxSteps + ")已用尽且工具链未完成，自动扩展至 "
                        + effectiveSteps + "（硬上限 " + hardCap + "）";
                result.getTraceSteps().add(ext);
                notify(callback, ext);
            }
            // 继续下一轮循环，让 LLM 根据 Observation 再次推理
        }

        // 任务被取消（断连回收）：返回取消结果，不再当作步数上限处理
        if (Thread.currentThread().isInterrupted()) {
            result.setReply("任务已取消");
            return result;
        }

        // 达到硬上限仍未完成
        result.setMaxStepsReached(true);
        result.setReply("达到最大推理步数限制(" + hardCap + ")，未能完成最终回复。");
        afterTurn(session, agent);
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
        // 软预算（初始 maxSteps）+ 硬上限（maxSteps × 扩展系数）：预算用尽且工具链未完成时自动扩展
        int hardCap = Math.max(maxSteps, (int) Math.ceil(maxSteps * maxStepsExtensionFactor));
        int effectiveSteps = maxSteps;
        int step = 0;

        while (step < effectiveSteps && !Thread.currentThread().isInterrupted()) {
            step++;
            LlmRequest request = contextAssembler.assemble(session, agent);

            // 流式调用 LLM
            LlmResponse response = llmGateway.streamChat(request, agent.getModelConfig(), streamCallback);

            // LLM 返回 error 终态：若已流式输出部分内容则保留（作为部分结果正常返回）；
            // 否则中止循环，不写入 assistant 消息，由上层转 error 事件
            if ("error".equals(response.getFinishReason())) {
                if (response.getContent() != null && !response.getContent().trim().isEmpty()) {
                    // 已流式输出部分内容：保留已输出部分，写 assistant 消息后正常返回
                    session.addAssistantMessage(response.getContent(), null);
                    result.setReply(response.getContent());
                    afterTurn(session, agent);
                    return result;
                }
                return errorResult(response);
            }

            List<ToolCall> toolCalls = response.getToolCalls();
            boolean noToolCalls = toolCalls == null || toolCalls.isEmpty();

            if (noToolCalls) {
                session.addAssistantMessage(response.getContent(), null);
                result.setReply(response.getContent());
                String thought = "[Thought] " + truncate(response.getContent());
                result.getTraceSteps().add(thought);
                notify(callback, thought);
                afterTurn(session, agent);
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

                ToolResult toolResult = toolGateway.execute(toolCall.getName(), toolCall.getArguments(), callback);
                String observation = toolResult.isSuccess()
                        ? toolResult.getOutput()
                        : "ERROR: " + toolResult.getError();

                session.addToolMessage(toolCall.getId(), observation);
                String obs = "[Observation] " + truncate(observation);
                result.getTraceSteps().add(obs);
                notify(callback, obs);
            }
            afterTurn(session, agent);
            // 动态扩展预算：本轮仍调用了工具（工具链未收敛）且预算用尽 → 自动追加，不超硬上限
            int extended = extendedBudget(step, maxSteps, effectiveSteps, hardCap);
            if (extended > effectiveSteps) {
                effectiveSteps = extended;
                String ext = "[Info] 步数预算(" + maxSteps + ")已用尽且工具链未完成，自动扩展至 "
                        + effectiveSteps + "（硬上限 " + hardCap + "）";
                result.getTraceSteps().add(ext);
                notify(callback, ext);
            }
        }

        // 任务被取消（断连回收）：返回取消结果，不再当作步数上限处理
        if (Thread.currentThread().isInterrupted()) {
            result.setReply("任务已取消");
            return result;
        }

        result.setMaxStepsReached(true);
        result.setReply("达到最大推理步数限制(" + hardCap + ")，未能完成最终回复。");
        afterTurn(session, agent);
        return result;
    }

    /**
     * LLM error 终态 → 中止结果：success=false + 明确错误信息 + 错误分类，不写入 assistant 消息。
     */
    private ReActResult errorResult(LlmResponse response) {
        ReActResult result = new ReActResult();
        String errMsg = response.getContent() == null || response.getContent().trim().isEmpty()
                ? "LLM 调用失败（无错误详情）" : response.getContent();
        result.setSuccess(false);
        result.setErrorMessage(errMsg);
        result.setReply(errMsg);
        result.setErrorCategory(response.getErrorCategory() != null
                ? response.getErrorCategory() : ErrorCategory.TRANSIENT);
        return result;
    }

    /**
     * 计算扩展后的步数预算：当前步已用尽预算且仍有工具调用（工具链未收敛）时，
     * 每次追加 maxSteps/2 步（至少 1 步），不超过硬上限。
     */
    private int extendedBudget(int step, int maxSteps, int effectiveSteps, int hardCap) {
        if (step >= effectiveSteps && effectiveSteps < hardCap) {
            return Math.min(hardCap, effectiveSteps + Math.max(1, maxSteps / 2));
        }
        return effectiveSteps;
    }

    private void afterTurn(Session session, Agent agent) {
        if (memoryManager != null) {
            try {
                memoryManager.afterTurn(session, agent);
            } catch (Exception e) {
                // 换页/提炼失败不影响主对话链路
            }
        }
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
