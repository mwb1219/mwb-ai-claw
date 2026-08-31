package com.mwb.ai.claw.agent.executor;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.agent.assembler.SessionAssembler;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.SessionGateway;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.CreateSessionCmd;
import com.mwb.ai.claw.dto.data.SessionDTO;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.UUID;

/**
 * 创建会话执行器
 */
@Component
public class CreateSessionCmdExe {

    @Resource
    private AgentGateway agentGateway;

    @Resource
    private SessionGateway sessionGateway;

    public SingleResponse<SessionDTO> execute(CreateSessionCmd cmd) {
        Agent agent = agentGateway.getAgent(cmd.getAgentId());

        Session session = newSession(agent, cmd.getTitle());
        sessionGateway.saveSession(session);

        return SingleResponse.of(SessionAssembler.toDTO(session));
    }

    private Session newSession(Agent agent, String title) {
        Session session = new Session();
        session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        session.setAgentId(agent.getAgentId());
        session.setTenantId(AgentScopeContext.get().getTenantId());
        session.setUserId(AgentScopeContext.get().getUserId());
        session.setTitle(title == null || title.isEmpty() ? "session-" + System.currentTimeMillis() : title);
        return session;
    }
}
