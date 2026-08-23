package com.mwb.ai.claw.agent.executor;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.agent.assembler.SessionAssembler;
import com.mwb.ai.claw.domain.memory.gateway.MemoryGateway;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.UpdateSessionCmd;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.dto.data.SessionDTO;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 会话更新执行器（当前支持修改标题）
 */
@Component
public class SessionUpdateCmdExe {

    @Resource
    private MemoryGateway memoryGateway;

    public SingleResponse<SessionDTO> execute(String sessionId, UpdateSessionCmd cmd) {
        Session session = memoryGateway.getSession(AgentScopeContext.get(), sessionId);
        if (session == null) {
            throw new BizException(AgentErrorCode.B_AGENT_SESSION_NOT_FOUND.getErrCode(), "会话不存在: " + sessionId);
        }
        String title = cmd.getTitle() == null ? "" : cmd.getTitle().trim();
        session.setTitle(title);
        session.setUpdateTime(System.currentTimeMillis());
        memoryGateway.saveSession(session);
        return SingleResponse.of(SessionAssembler.toDTO(session));
    }
}
