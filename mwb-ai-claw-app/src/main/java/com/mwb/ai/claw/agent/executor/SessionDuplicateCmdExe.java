package com.mwb.ai.claw.agent.executor;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.agent.assembler.SessionAssembler;
import com.mwb.ai.claw.domain.memory.gateway.MemoryGateway;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.dto.data.SessionDTO;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.UUID;

/**
 * 会话复制执行器：基于现有会话创建副本（新会话 id，标题加「副本」后缀，归属当前用户）。
 */
@Component
public class SessionDuplicateCmdExe {

    @Resource
    private MemoryGateway memoryGateway;

    public SingleResponse<SessionDTO> execute(String sessionId) {
        Session src = memoryGateway.getSession(AgentScopeContext.get(), sessionId);
        if (src == null) {
            throw new BizException(AgentErrorCode.B_AGENT_SESSION_NOT_FOUND.getErrCode(), "会话不存在: " + sessionId);
        }

        String baseTitle = (src.getTitle() == null || src.getTitle().trim().isEmpty())
                ? "未命名会话" : src.getTitle();

        Session copy = new Session();
        copy.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        copy.setAgentId(src.getAgentId());
        copy.setTitle(baseTitle + " - 副本");
        copy.setTenantId(src.getTenantId());
        copy.setUserId(src.getUserId());
        copy.setMessages(new ArrayList<>(src.getMessages()));
        copy.setTraceSteps(new ArrayList<>(src.getTraceSteps()));
        memoryGateway.saveSession(copy);
        return SingleResponse.of(SessionAssembler.toDTO(copy));
    }
}
