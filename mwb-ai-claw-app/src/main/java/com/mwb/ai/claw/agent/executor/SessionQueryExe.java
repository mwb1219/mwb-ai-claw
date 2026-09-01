package com.mwb.ai.claw.agent.executor;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.agent.assembler.SessionAssembler;
import com.mwb.ai.claw.domain.core.SessionGateway;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.dto.data.SessionDTO;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 会话查询执行器
 */
@Component
public class SessionQueryExe {

    @Resource
    private SessionGateway sessionGateway;

    public SingleResponse<SessionDTO> execute(String sessionId) {
        // 展示口径：会话详情加载含归档的全量原文（供前端展示历史）；模型工作记忆仍走 getSession（仅未归档）
        Session session = sessionGateway.getSessionFull(AgentScopeContext.get(), sessionId);
        if (session == null) {
            throw new BizException(AgentErrorCode.B_AGENT_SESSION_NOT_FOUND.getErrCode(), "会话不存在: " + sessionId);
        }
        return SingleResponse.of(SessionAssembler.toDTO(session));
    }
}
