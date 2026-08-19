package com.mwb.ai.claw.agent.executor;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 会话删除执行器
 */
@Component
public class SessionDeleteCmdExe {

    @Resource
    private MemoryGateway memoryGateway;

    public SingleResponse<Void> execute(String sessionId) {
        if (memoryGateway.getSession(sessionId) == null) {
            throw new BizException(AgentErrorCode.B_AGENT_SESSION_NOT_FOUND.getErrCode(),
                    "会话不存在: " + sessionId);
        }
        memoryGateway.deleteSession(sessionId);
        return SingleResponse.buildSuccess();
    }
}
