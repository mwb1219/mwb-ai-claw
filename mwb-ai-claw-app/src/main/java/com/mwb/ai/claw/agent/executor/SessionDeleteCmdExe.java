package com.mwb.ai.claw.agent.executor;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.domain.core.SessionGateway;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 会话删除执行器
 */
@Component
public class SessionDeleteCmdExe {

    @Resource
    private SessionGateway sessionGateway;

    public SingleResponse<Void> execute(String sessionId) {
        if (sessionGateway.getSession(AgentScopeContext.get(), sessionId) == null) {
            throw new BizException(AgentErrorCode.B_AGENT_SESSION_NOT_FOUND.getErrCode(),
                    "会话不存在: " + sessionId);
        }
        sessionGateway.deleteSession(AgentScopeContext.get(), sessionId);
        return SingleResponse.buildSuccess();
    }
}
