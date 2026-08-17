package com.mwb.ai.claw.agent.executor;

import com.alibaba.cola.dto.SingleResponse;
import com.alibaba.cola.exception.BizException;
import com.mwb.ai.claw.domain.collaboration.AgentOrchestrator;
import com.mwb.ai.claw.domain.collaboration.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.ExecutionUnit;
import com.mwb.ai.claw.domain.collaboration.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.OrchestrationDefinition;
import com.mwb.ai.claw.domain.collaboration.OrchestrationSelector;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.infrastructure.collaboration.OrchestratorRegistry;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.config.OrchestrationConfigLoader;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 对话执行器（编排分发器）：选择编排 → 装配上下文 → 委托编排插件执行。
 * <p>
 * 编排选择优先级：显式指定（ChatCmd.orchestrationId） > 意图选择（OrchestrationSelector） > 默认编排（agent.orchestration）。
 * 具体编排逻辑（路由 / 流水线 / 对话式）由 AgentOrchestrator 插件实现。
 */
@Component
public class ChatCmdExe {

    @Resource
    private AgentGateway agentGateway;

    @Resource
    private OrchestrationConfigLoader orchestrationLoader;

    @Resource
    private OrchestrationSelector orchestrationSelector;

    @Resource
    private OrchestratorRegistry orchestratorRegistry;

    @Resource
    private ExecutionUnit executionUnit;

    @Resource
    private AgentProperties agentProperties;

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
     * 执行对话（带进度回调 + LLM 流式回调）
     */
    public SingleResponse<ChatResponseDTO> execute(ChatCmd cmd, ProgressCallback callback,
                                                   LlmStreamCallback streamCallback) {
        if (cmd.getMessage() == null || cmd.getMessage().trim().isEmpty()) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(), "消息内容不能为空");
        }

        // 1. 选择编排：显式指定 > 意图选择 > 默认
        String orchestrationId = resolveOrchestrationId(cmd);
        OrchestrationDefinition definition = orchestrationLoader.get(orchestrationId);

        // 2. 装配编排上下文
        OrchestrationContext ctx = new OrchestrationContext();
        ctx.setMessage(cmd.getMessage());
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

        // 4. 组装响应
        ChatResponseDTO dto = new ChatResponseDTO();
        dto.setSessionId(result.getSessionId());
        dto.setAgentId(result.getAgentId());
        dto.setOrchestrationId(result.getOrchestrationId());
        dto.setReply(result.getReply());
        dto.setTraceSteps(result.getTraceSteps());
        return SingleResponse.of(dto);
    }

    /**
     * 编排选择：显式指定优先，其次意图匹配（未命中返回 null），最后回退默认编排。
     */
    private String resolveOrchestrationId(ChatCmd cmd) {
        if (cmd.getOrchestrationId() != null && !cmd.getOrchestrationId().trim().isEmpty()) {
            return cmd.getOrchestrationId().trim();
        }
        String matched = orchestrationSelector.select(cmd.getMessage(), orchestrationLoader.loadDefinitions());
        if (matched != null && !matched.trim().isEmpty()) {
            return matched;
        }
        return agentProperties.getOrchestration();
    }
}
