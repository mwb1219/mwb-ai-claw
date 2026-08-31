package com.mwb.ai.claw.agent.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.agent.observability.RunUsageRecorder;
import com.mwb.ai.claw.domain.context.LlmRequestOptions;
import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;
import com.mwb.ai.claw.domain.collaboration.spi.AgentOrchestrator;
import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.observability.RunUsage;
import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStep;
import com.mwb.ai.claw.domain.observability.TraceStore;
import com.mwb.ai.claw.domain.rag.context.RagRequestContext;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.collaboration.common.OrchestratorRegistry;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.config.OrchestrationConfigLoader;
import com.mwb.ai.claw.infrastructure.llm.RunTokenBudget;

/**
 * 对话执行器（编排分发器）：装配上下文 → 委托编排插件执行。
 * <p>
 * 编排选择：显式指定（ChatCmd.orchestrationId）优先，未指定回退默认编排（agent.orchestration，默认 routing）。
 * 多 Agent 协作编排（conversational / delegate）不再由消息前置意图路由选择，
 * 而是封装为协作工具（invoke_discussion / invoke_delegate），由主 Agent 在 ReAct 中自主调用。
 * 具体编排逻辑由 AgentOrchestrator 插件实现。
 */
@Component
public class ChatCmdExe {

    private static final Logger log = LoggerFactory.getLogger(ChatCmdExe.class);

    @Resource
    private AgentGateway agentGateway;

    @Resource
    private OrchestrationConfigLoader orchestrationLoader;

    @Resource
    private OrchestratorRegistry orchestratorRegistry;

    @Resource
    private ExecutionUnit executionUnit;

    @Resource
    private AgentProperties agentProperties;

    @Resource
    private RunUsageRecorder usageRecorder;

    /** 步骤级 trace 存储（agent.observability.trace.*，默认本地 JSON；trace.enabled=false 时为空） */
    @Resource
    private ObjectProvider<TraceStore> traceStoreProvider;

    public SingleResponse<ChatResponseDTO> execute(ChatCmd cmd) {
        return execute(cmd, null);
    }

    /**
     * 执行对话（带进度回调）
     */
    public SingleResponse<ChatResponseDTO> execute(ChatCmd cmd, ProgressCallback callback) {
        return execute(cmd, callback, null);
    }

    /**
     * 执行对话（带进度回调 + LLM 流式回调），并在执行前后记录一次运行用量（成功/异常均记录）。
     */
    public SingleResponse<ChatResponseDTO> execute(ChatCmd cmd, ProgressCallback callback,
                                                   LlmStreamCallback streamCallback) {
        long start = System.currentTimeMillis();
        String orchestrationId = null;
        // 结构化输出（D2）：请求级 responseFormat/jsonSchema 经线程上下文注入 ReAct 循环内每次 LLM 请求
        boolean boundOptions = cmd.getResponseFormat() != null && !cmd.getResponseFormat().trim().isEmpty();
        if (boundOptions) {
            LlmRequestOptions.bind(cmd.getResponseFormat(), cmd.getJsonSchema());
        }
        RagRequestContext.bind(cmd.getKnowledgeBaseIds());
        // 单次运行 token 预算：>0 时在当前线程绑定，LLM 韧性装饰器按次累计，超限中止
        long budgetTokens = agentProperties.getLlm().getRunBudgetTokens();
        RunTokenBudget budget = budgetTokens > 0 ? RunTokenBudget.bind(budgetTokens) : null;
        try {
            if (cmd.getMessage() == null || cmd.getMessage().trim().isEmpty()) {
                throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(), "消息内容不能为空");
            }

            // 1. 选择编排：显式指定 > 默认（协作编排由主 Agent 经 invoke_* 工具自主发起，不再预选）
            orchestrationId = resolveOrchestrationId(cmd);
            OrchestrationDefinition definition = orchestrationLoader.get(orchestrationId);
            log.info("编排选择: orchestrationId={}, 会话={}, 消息={}", orchestrationId,
                    cmd.getSessionId(), cmd.getMessage());

            // 2. 装配编排上下文
            OrchestrationContext ctx = new OrchestrationContext();
            ctx.setScope(AgentScopeContext.get());
            ctx.setMessage(cmd.getMessage());
            ctx.setParts(cmd.getParts());
            ctx.setSessionId(cmd.getSessionId());
            ctx.setExplicitAgentId(cmd.getAgentId());
            ctx.setExplicitOrchestrationId(cmd.getOrchestrationId());
            ctx.setDefinition(definition);
            ctx.setAgentGateway(agentGateway);
            ctx.setExecutionUnit(executionUnit);
            ctx.setCallback(callback);
            ctx.setStreamCallback(streamCallback);

            // 3. 委托编排插件执行
            AgentOrchestrator orchestrator = orchestratorRegistry.resolve(definition);
            CollaborationResult result = orchestrator.orchestrate(ctx);
            log.info("对话完成: orchestrationId={}, agentId={}, 会话={}", orchestrationId,
                    result.getAgentId(), result.getSessionId());

            // 4. 组装响应（ReAct error 终态 → 失败响应，不冒充最终回复）
            if (!result.isSuccess()) {
                String code = mapErrorCode(result.getErrorCategory(), result.getErrorMessage());
                recordUsage(cmd, orchestrationId, result, false, code, start);
                recordTrace(cmd, orchestrationId, result, false, code, start);
                return SingleResponse.buildFailure(code, result.getErrorMessage());
            }
            ChatResponseDTO dto = new ChatResponseDTO();
            dto.setSessionId(result.getSessionId());
            dto.setAgentId(result.getAgentId());
            dto.setOrchestrationId(result.getOrchestrationId());
            dto.setReply(result.getReply());
            dto.setTraceSteps(result.getTraceSteps());

            recordUsage(cmd, orchestrationId, result, true, null, start);
            recordTrace(cmd, orchestrationId, result, true, null, start);
            return SingleResponse.of(dto);
        } catch (BizException e) {
            recordUsage(cmd, orchestrationId, null, false, e.getErrCode(), start);
            recordTrace(cmd, orchestrationId, null, false, e.getErrCode(), start);
            throw e;
        } catch (Exception e) {
            recordUsage(cmd, orchestrationId, null, false, "SYSTEM_ERROR", start);
            recordTrace(cmd, orchestrationId, null, false, "SYSTEM_ERROR", start);
            throw e;
        } finally {
            if (boundOptions) {
                LlmRequestOptions.unbind();
            }
            RagRequestContext.unbind();
            if (budget != null) {
                RunTokenBudget.unbind();
            }
        }
    }

    /**
     * 记录一次运行用量摘要（失败也记录，便于排查与成本核算）。
     */
    private void recordUsage(ChatCmd cmd, String orchestrationId, CollaborationResult result,
                             boolean success, String errorCode, long start) {
        try {
            RunUsage usage = new RunUsage();
            usage.setTraceId(resolveTraceId());
            usage.setSessionId(result != null ? result.getSessionId() : cmd.getSessionId());
            usage.setAgentId(result != null ? result.getAgentId() : cmd.getAgentId());
            usage.setOrchestration(orchestrationId);
            usage.setModel(agentProperties.getModel());
            usage.setDurationMs(System.currentTimeMillis() - start);
            usage.setSuccess(success);
            usage.setSteps(result != null ? result.getTraceSteps().size() : 0);
            usage.setErrorCode(errorCode);
            usage.setCreateTime(System.currentTimeMillis());
            usageRecorder.record(usage);
        } catch (Exception e) {
            log.warn("记录运行用量失败: {}", e.getMessage());
        }
    }

    /**
     * 记录一次全链路步骤 trace（成功/失败均记录；trace.enabled=false 或未装配 TraceStore 时静默跳过）。
     * traceId 复用请求链路 MDC，缺失时自动生成，保证任意入口（REST / SSE / WS / Shell）都可关联查询。
     */
    private void recordTrace(ChatCmd cmd, String orchestrationId, CollaborationResult result,
                             boolean success, String errorCode, long start) {
        try {
            TraceStore store = traceStoreProvider.getIfAvailable();
            if (store == null) {
                return;
            }
            TraceRun run = new TraceRun();
            run.setTraceId(resolveTraceId());
            run.setTenantId(scopeField(0));
            run.setUserId(scopeField(1));
            run.setSessionId(result != null ? result.getSessionId() : cmd.getSessionId());
            run.setAgentId(result != null ? result.getAgentId() : cmd.getAgentId());
            run.setOrchestration(orchestrationId);
            run.setModel(agentProperties.getModel());
            run.setStartTime(start);
            run.setDurationMs(System.currentTimeMillis() - start);
            run.setSuccess(success);
            run.setErrorCode(errorCode);
            run.setSteps(buildSteps(result));
            store.saveTrace(run);
        } catch (Exception e) {
            log.warn("记录 trace 失败: {}", e.getMessage());
        }
    }

    private java.util.List<TraceStep> buildSteps(CollaborationResult result) {
        java.util.List<TraceStep> steps = new ArrayList<>();
        if (result == null || result.getTraceSteps() == null) {
            return steps;
        }
        int idx = 1;
        for (String s : result.getTraceSteps()) {
            TraceStep step = new TraceStep();
            step.setIndex(idx++);
            step.setContent(s);
            step.setType(classifyStep(s));
            steps.add(step);
        }
        return steps;
    }

    private String classifyStep(String content) {
        if (content != null) {
            String u = content.toUpperCase(Locale.ROOT);
            if (u.startsWith("[THOUGHT]")) {
                return "thought";
            }
            if (u.startsWith("[ACTION]")) {
                return "action";
            }
            if (u.startsWith("[OBSERVATION]")) {
                return "observation";
            }
            if (u.startsWith("[INFO]")) {
                return "info";
            }
        }
        return "step";
    }

    private String resolveTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.trim().isEmpty()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return traceId;
    }

    /** 0=tenantId，1=userId；取当前请求 scope，cope 为空时返回空串 */
    private String scopeField(int kind) {
        com.mwb.ai.claw.domain.scope.AgentScope scope = com.mwb.ai.claw.domain.scope.AgentScopeContext.get();
        if (scope == null) {
            return "";
        }
        String v = kind == 0 ? scope.getTenantId() : scope.getUserId();
        return v == null ? "" : v;
    }

    /**
     * 编排选择：显式指定优先，未指定回退默认编排（agent.orchestration）。
     */
    private String resolveOrchestrationId(ChatCmd cmd) {
        if (cmd.getOrchestrationId() != null && !cmd.getOrchestrationId().trim().isEmpty()) {
            return cmd.getOrchestrationId().trim();
        }
        return agentProperties.getOrchestration();
    }

    /**
     * 错误码映射（C3）：按错误分类 + 错误信息映射统一错误码，供失败响应与运行记录使用。
     * <ul>
     *   <li>BUDGET → BUDGET_EXCEEDED（预算耗尽）；</li>
     *   <li>TRANSIENT → LLM_UNAVAILABLE（重试+fallback 后仍失败），超时 → LLM_TIMEOUT，429 → RATE_LIMITED；</li>
     *   <li>BUSINESS / 未知 → SYSTEM_ERROR（错误详情在 errMessage 透传）。</li>
     * </ul>
     */
    private String mapErrorCode(com.mwb.ai.claw.domain.core.ErrorCategory category, String errorMessage) {
        if (category == null) {
            return "SYSTEM_ERROR";
        }
        switch (category) {
            case BUDGET:
                return "BUDGET_EXCEEDED";
            case TRANSIENT:
                if (errorMessage != null && errorMessage.contains("超时")) {
                    return "LLM_TIMEOUT";
                }
                if (errorMessage != null && errorMessage.contains("429")) {
                    return "RATE_LIMITED";
                }
                return "LLM_UNAVAILABLE";
            default:
                return "SYSTEM_ERROR";
        }
    }
}
