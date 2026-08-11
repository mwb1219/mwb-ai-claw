package com.mwb.ai.claw.agent.executor;

import com.alibaba.cola.dto.SingleResponse;
import com.alibaba.cola.exception.BizException;
import com.mwb.ai.claw.agent.assembler.SessionAssembler;
import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.domain.core.Session;
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
    private MemoryGateway memoryGateway;

    public SingleResponse<SessionDTO> execute(String sessionId) {
        Session session = memoryGateway.getSession(sessionId);
        if (session == null) {
            throw new BizException(AgentErrorCode.B_AGENT_SESSION_NOT_FOUND.getErrCode(), "会话不存在: " + sessionId);
        }
        return SingleResponse.of(SessionAssembler.toDTO(session));
    }
}
