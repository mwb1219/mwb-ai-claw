package com.mwb.ai.claw.agent.executor;

import com.alibaba.cola.dto.SingleResponse;
import com.alibaba.cola.exception.BizException;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.core.ReActLoopService;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.UUID;

/**
 * 对话执行器：编排「加载会话 → 追加用户消息 → ReAct 循环 → 持久化 → 组装响应」用例。
 */
@Component
public class ChatCmdExe {

    @Resource
    private AgentGateway agentGateway;

    @Resource
    private MemoryGateway memoryGateway;

    @Resource
    private ReActLoopService reActLoopService;

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

        // 1. 加载 Agent 配置
        Agent agent = agentGateway.getAgent(cmd.getAgentId());

        // 2. 获取或创建会话
        Session session = getOrCreateSession(cmd.getSessionId(), agent);

        // 3. 追加用户消息
        session.addUserMessage(cmd.getMessage());

        // 4. 执行 ReAct 推理循环（根据是否有流式回调选择调用方式）
        ReActResult result;
        if (streamCallback != null) {
            result = reActLoopService.streamRun(session, agent, callback, streamCallback);
        } else {
            result = reActLoopService.run(session, agent, callback);
        }

        // 5. 持久化会话
        memoryGateway.saveSession(session);

        // 6. 组装响应
        ChatResponseDTO dto = new ChatResponseDTO();
        dto.setSessionId(session.getSessionId());
        dto.setReply(result.getReply());
        dto.setTraceSteps(result.getTraceSteps());
        return SingleResponse.of(dto);
    }

    private Session getOrCreateSession(String sessionId, Agent agent) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            Session session = memoryGateway.getSession(sessionId);
            if (session == null) {
                throw new BizException(AgentErrorCode.B_AGENT_SESSION_NOT_FOUND.getErrCode(), "会话不存在: " + sessionId);
            }
            return session;
        }
        Session session = new Session();
        session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        session.setAgentId(agent.getAgentId());
        session.setTitle("session-" + System.currentTimeMillis());
        return session;
    }
}
