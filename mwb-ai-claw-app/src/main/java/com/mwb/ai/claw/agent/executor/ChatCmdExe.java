package com.mwb.ai.claw.agent.executor;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.collaboration.AgentOrchestrator;
import com.mwb.ai.claw.domain.collaboration.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.ExecutionUnit;
import com.mwb.ai.claw.domain.collaboration.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.OrchestrationDefinition;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.collaboration.OrchestratorRegistry;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.config.OrchestrationConfigLoader;

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

        // 1. 选择编排：显式指定 > 默认（协作编排由主 Agent 经 invoke_* 工具自主发起，不再预选）
        String orchestrationId = resolveOrchestrationId(cmd);
        OrchestrationDefinition definition = orchestrationLoader.get(orchestrationId);
        log.info("编排选择: orchestrationId={}, 会话={}, 消息={}", orchestrationId,
                cmd.getSessionId(), cmd.getMessage());

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
        log.info("对话完成: orchestrationId={}, agentId={}, 会话={}", orchestrationId,
                result.getAgentId(), result.getSessionId());

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
     * 编排选择：显式指定优先，未指定回退默认编排（agent.orchestration）。
     */
    private String resolveOrchestrationId(ChatCmd cmd) {
        if (cmd.getOrchestrationId() != null && !cmd.getOrchestrationId().trim().isEmpty()) {
            return cmd.getOrchestrationId().trim();
        }
        return agentProperties.getOrchestration();
    }
}
